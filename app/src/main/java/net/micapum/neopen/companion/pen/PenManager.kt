package net.micapum.neopen.companion.pen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Capa BLE del Neo smartpen M1+. Escanea por el servicio v2 (0x19F1), conecta,
 * hace el handshake, y traduce los frames del protocolo a páginas (`StrokePage`)
 * que entrega por `onPage`. No conoce nada del servidor neopen.
 *
 * Las páginas completadas se suben aparte (ver sync/Uploader).
 */
@SuppressLint("MissingPermission")
class PenManager(
    private val context: Context,
    private val onPage: (StrokePage) -> Unit,
) {
    private val _state = MutableStateFlow(PenState())
    val state: StateFlow<PenState> = _state

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var reassembler: Protocol.FrameReassembler? = null

    // Conexión: evita reentradas del scan y permite reintentar el clásico GATT 133.
    private var connecting = false
    private var target: BluetoothDevice? = null
    private var connectAttempts = 0

    // --- estado de captura online ---
    private var currentSobp: Sobp? = null
    private var strokeStart = 0L
    private val strokeDots = ArrayList<Dot>()
    private val pageStrokes = ArrayList<Stroke>()
    private var pageSobp: Sobp? = null
    private val idleFlush = Runnable { flushPage() }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    val isReady get() = adapter?.isEnabled == true

    private companion object {
        const val TAG = "PenManager"
        // VERIFY contra SettingType del SDK; valores provisionales.
        const val SET_AUTO_POWER_ON = 0x05
        const val SET_BEEP = 0x07
        const val SET_OFFLINE = 0x09
        const val SET_AUTO_SHUTDOWN = 0x04
    }

    // ----------------------------------------------------------------------------------
    // Scan + connect
    // ----------------------------------------------------------------------------------

    fun scanAndConnect() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            update { it.copy(status = PenStatus.IDLE, error = "bluetooth-off") }; return
        }
        update { it.copy(status = PenStatus.SCANNING, error = null) }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE_V2)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE_V5)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // El scan puede entregar varios resultados (incluso tras stopScan, por la
                // cola pendiente). Solo actuamos en el primero.
                if (connecting) return
                connecting = true
                scanner.stopScan(this)
                // ¡Importante! NO conectar en el hilo del callback de scan: es la causa
                // típica del GATT error 133. Saltamos al hilo principal.
                main.post { connect(result.device) }
            }
            override fun onScanFailed(errorCode: Int) {
                update { it.copy(status = PenStatus.IDLE, error = "scan-failed") }
            }
        }
        scanner.startScan(filters, settings, cb)
        // timeout de scan: 15 s
        main.postDelayed({ if (!connecting) scanner.stopScan(cb) }, 15_000)
    }

    private fun connect(device: BluetoothDevice) {
        target = device
        connectAttempts = 0
        reassembler = Protocol.FrameReassembler(::onFrame)
        openGatt(device)
    }

    private fun openGatt(device: BluetoothDevice) {
        connectAttempts++
        update { it.copy(status = PenStatus.CONNECTING, mac = device.address, error = null) }
        // autoConnect=false + TRANSPORT_LE: conexión directa, lo más rápido.
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Cierra el GATT actual y reintenta (hasta 3 veces) ante un fallo de conexión (133). */
    private fun retryOrFail() {
        gatt?.close()
        gatt = null
        val device = target
        if (device != null && connectAttempts < 3) {
            // Pequeño respiro para que la pila BLE libere el slot antes de reintentar.
            main.postDelayed({ openGatt(device) }, 600)
        } else {
            connecting = false
            update { it.copy(status = PenStatus.DISCONNECTED, error = "connect-failed") }
        }
    }

    fun disconnect() {
        connecting = false
        target = null
        main.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        update { it.copy(status = PenStatus.DISCONNECTED, offlinePhase = OfflinePhase.IDLE) }
    }

    private fun write(frame: ByteArray) {
        val g = gatt ?: return
        val ch = writeChar ?: run { Log.w(TAG, "write() sin característica de escritura"); return }
        // Usa NO_RESPONSE si la característica lo soporta (lo habitual en NeoLAB);
        // si no, escritura con respuesta.
        val noResp = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ch.writeType = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = frame
        g.writeCharacteristic(ch)
    }

    // ----------------------------------------------------------------------------------
    // GATT callback
    // ----------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Fallo de conexión (típicamente 133): cierra y reintenta.
                retryOrFail()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectAttempts = 0
                Log.d(TAG, "GATT conectado, descubriendo servicios…")
                // Descubrir servicios en el hilo principal mejora la estabilidad en
                // algunos fabricantes; un pequeño retardo evita carreras tras conectar.
                main.postDelayed({ g.discoverServices() }, 250)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connecting = false
                g.close()
                if (gatt === g) gatt = null
                update { it.copy(status = PenStatus.DISCONNECTED) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(Protocol.SERVICE_V2) ?: g.getService(Protocol.SERVICE_V5)
            if (service == null) {
                Log.e(TAG, "Servicio 19F1/v5 no encontrado. Servicios: " +
                    g.services.joinToString { it.uuid.toString() })
                update { it.copy(status = PenStatus.DISCONNECTED, error = "service-not-found") }
                return
            }

            // Descubre las características por sus PROPIEDADES, no por UUID adivinado:
            // así funciona aunque el número exacto (19F2/19F3) varíe entre firmwares.
            val chars = service.characteristics
            writeChar = chars.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            } ?: service.getCharacteristic(Protocol.CHAR_WRITE)
            val notify = chars.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            } ?: service.getCharacteristic(Protocol.CHAR_NOTIFY)

            Log.d(TAG, "Características → write=${writeChar?.uuid} notify=${notify?.uuid}")
            if (writeChar == null || notify == null) {
                Log.e(TAG, "Faltan características. Disponibles: " +
                    chars.joinToString { "${it.uuid}(props=${it.properties})" })
                update { it.copy(status = PenStatus.DISCONNECTED, error = "chars-not-found") }
                return
            }

            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(Protocol.CCCD)
            if (cccd != null) {
                // Habilita notificaciones y ESPERA a onDescriptorWrite antes de escribir:
                // Android solo permite una operación GATT a la vez.
                val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                cccd.value = value
                g.writeDescriptor(cccd)
                Log.d(TAG, "Escribiendo CCCD para habilitar notificaciones…")
            } else {
                // Sin CCCD: arranca el handshake directamente.
                Log.w(TAG, "Notify sin CCCD; arrancando handshake sin descriptor")
                write(Protocol.reqVersion())
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Notificaciones habilitadas: ahora sí es seguro arrancar el handshake.
            Log.d(TAG, "CCCD escrito (status=$status), arrancando handshake (reqVersion)")
            write(Protocol.reqVersion())
        }

        // Android 13+ (API 33): entrega el valor por parámetro.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            reassembler?.feed(value)
        }

        // Android ≤ 12: el valor va en ch.value.
        @Deprecated("Compat <33", ReplaceWith(""))
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            reassembler?.feed(ch.value ?: return)
        }
    }

    // ----------------------------------------------------------------------------------
    // Dispatch de frames
    // ----------------------------------------------------------------------------------

    private fun onFrame(frame: Protocol.Frame) {
        Log.d(TAG, "Frame recibido: cmd=0x%02X len=%d".format(frame.cmd, frame.data.size))
        when (frame.cmd) {
            Protocol.VERSION_RESPONSE -> {
                // VERIFY: extraer MAC/nombre/firmware del payload. Tras versión, autorizado.
                update { it.copy(status = PenStatus.AUTHORIZED, error = null) }
                write(Protocol.reqSettingInfo())
                write(Protocol.reqOnlineDataAll())
            }
            Protocol.PASSWORD_REQUEST -> write(Protocol.resPassword("")) // M1+ sin contraseña
            Protocol.SETTING_INFO_RESPONSE ->
                update { it.copy(settings = Protocol.parseSettingInfo(frame.data)) }

            Protocol.ONLINE_PAPER_INFO, Protocol.ONLINE_NEW_PAPER_INFO ->
                currentSobp = parsePaperInfo(frame.data)
            Protocol.ONLINE_PEN_UPDOWN, Protocol.ONLINE_NEW_PEN_DOWN -> beginStroke()
            Protocol.ONLINE_PEN_DOT, Protocol.ONLINE_NEW_PEN_DOT -> appendDots(frame.data)
            Protocol.ONLINE_NEW_PEN_UP -> endStroke()

            Protocol.OFFLINE_NOTE_LIST_RESPONSE -> {
                val notes = Protocol.parseNoteList(frame.data)
                update { it.copy(offlinePhase = OfflinePhase.IDLE, notes = notes) }
            }
            Protocol.OFFLINE_DATA_RESPONSE ->
                update { it.copy(offlinePhase = OfflinePhase.TRANSFERRING, offlineProgress = 0) }
            Protocol.OFFLINE_PACKET_REQUEST -> handleOfflinePacket(frame.data)
            Protocol.OFFLINE_DATA_DELETE_RESPONSE ->
                update { it.copy(notes = emptyList()) }
        }
    }

    // ----------------------------------------------------------------------------------
    // Captura online → strokes → páginas
    // ----------------------------------------------------------------------------------

    private fun beginStroke() {
        strokeStart = System.currentTimeMillis()
        strokeDots.clear()
    }

    private fun appendDots(data: ByteArray) {
        // VERIFY: cabecera del evento + N×17 bytes de dot. Aquí asumimos dots crudos de 17B.
        var off = 0
        while (off + 17 <= data.size) {
            val (dot, _) = Protocol.parseDot(data, off, strokeStart)
            strokeDots.add(dot)
            off += 17
        }
        val sobp = currentSobp ?: return
        if (pageSobp != null && pageSobp != sobp) flushPage()
        pageSobp = sobp
        update { it.copy(capturedPages = pageStrokes.size) }
    }

    private fun endStroke() {
        if (strokeDots.isEmpty()) return
        pageStrokes.add(Stroke(strokeStart, strokeDots.last().t, strokeDots.toList()))
        strokeDots.clear()
        // Cierra la página tras 8 s sin escribir (igual que la web).
        main.removeCallbacks(idleFlush)
        main.postDelayed(idleFlush, 8_000)
    }

    private fun flushPage() {
        val sobp = pageSobp ?: return
        if (pageStrokes.isEmpty()) return
        onPage(StrokePage(sobp, "realtime", iso.format(System.currentTimeMillis()), pageStrokes.toList()))
        pageStrokes.clear()
        pageSobp = null
    }

    private fun parsePaperInfo(data: ByteArray): Sobp {
        // VERIFY offsets. Estructura típica: section(1) owner(3) book(4) page(4) LE.
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val rb = ByteArray(4); if (bb.remaining() >= 4) bb.get(rb)
        val section = rb[3].toInt() and 0xff
        val owner = (rb[0].toInt() and 0xff) or ((rb[1].toInt() and 0xff) shl 8) or
            ((rb[2].toInt() and 0xff) shl 16)
        val book = if (bb.remaining() >= 4) bb.int else 0
        val page = if (bb.remaining() >= 4) bb.int else 0
        return Sobp(section, owner, book, page)
    }

    // ----------------------------------------------------------------------------------
    // Offline (memoria del boli)
    // ----------------------------------------------------------------------------------

    // ----------------------------------------------------------------------------------
    // Ajustes del boli (pestaña Configuración). VERIFY: ids de SETTING_TYPE contra el SDK.
    // El estado se actualiza de forma optimista para que la UI responda.
    // ----------------------------------------------------------------------------------

    fun setAutoPowerOn(on: Boolean) = setSetting(SET_AUTO_POWER_ON, if (on) 1 else 0) {
        it.copy(settings = it.settings.copy(autoPowerOn = on))
    }
    fun setBeep(on: Boolean) = setSetting(SET_BEEP, if (on) 1 else 0) {
        it.copy(settings = it.settings.copy(beep = on))
    }
    fun setOfflineData(on: Boolean) = setSetting(SET_OFFLINE, if (on) 1 else 0) {
        it.copy(settings = it.settings.copy(offlineData = on))
    }
    fun setAutoShutdown(minutes: Int) = setSetting(SET_AUTO_SHUTDOWN, minutes) {
        it.copy(settings = it.settings.copy(autoShutdownTime = minutes))
    }

    private fun setSetting(type: Int, value: Int, optimistic: (PenState) -> PenState) {
        write(Protocol.reqSettingChange(type, byteArrayOf(value.toByte())))
        update(optimistic)
    }

    fun listOffline() {
        update { it.copy(offlinePhase = OfflinePhase.LISTING, error = null) }
        write(Protocol.reqOfflineNoteList(0, 0))
    }

    fun importOffline(note: OfflineNote) {
        update { it.copy(offlinePhase = OfflinePhase.TRANSFERRING, offlineProgress = 0) }
        write(Protocol.reqOfflineData(note.section, note.owner, note.note))
    }

    fun importAllOffline() {
        _state.value.notes?.forEach { importOffline(it) }
    }

    fun clearOffline(notes: List<OfflineNote>) {
        notes.groupBy { it.section to it.owner }.forEach { (k, ns) ->
            write(Protocol.reqOfflineDelete(k.first, k.second, ns.map { it.note }.toIntArray()))
        }
    }

    private var offlinePacketIndex = 0
    private fun handleOfflinePacket(data: ByteArray) {
        // VERIFY: el chunk trae [index][total][sobp][dots... posiblemente zlib].
        // 1) ACK del paquete para que el boli envíe el siguiente.
        write(Protocol.resOfflinePacket(offlinePacketIndex++))
        // 2) Extraer dots (descomprimir si procede) y agruparlos en páginas offline.
        //    TODO: implementar el parseo exacto del chunk con el boli real y
        //    java.util.zip.Inflater para los datos comprimidos.
        update {
            val pct = minOf(99, it.offlineProgress + 5)
            it.copy(offlineProgress = pct)
        }
    }

    // ----------------------------------------------------------------------------------

    private inline fun update(f: (PenState) -> PenState) {
        _state.value = f(_state.value)
    }
}
