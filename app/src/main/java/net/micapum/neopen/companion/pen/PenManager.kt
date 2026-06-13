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

    /** Registro de diagnóstico en memoria (visible desde Ajustes → Debug). */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var reassembler: Protocol.FrameReassembler? = null

    // Conexión: evita reentradas del scan y permite reintentar el clásico GATT 133.
    private var connecting = false
    private var target: BluetoothDevice? = null
    private var connectAttempts = 0
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null

    private val logClock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Escribe en logcat y en el buffer en memoria (cap. 300 líneas). */
    private fun log(msg: String) {
        Log.d(TAG, msg)
        val line = "${logClock.format(System.currentTimeMillis())}  $msg"
        _logs.value = (_logs.value + line).takeLast(300)
    }

    fun clearLogs() { _logs.value = emptyList() }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    // --- estado de captura online ---
    private var currentSobp: Sobp? = null
    private var strokeStart = 0L
    private var penTime = 0L // tiempo absoluto (ms) acumulado del boli durante un stroke
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
    }

    // ----------------------------------------------------------------------------------
    // Scan + connect
    // ----------------------------------------------------------------------------------

    fun scanAndConnect() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            log("Bluetooth apagado: no hay scanner")
            update { it.copy(status = PenStatus.IDLE, error = "bluetooth-off") }; return
        }
        this.scanner = scanner
        log("Escaneando boli (servicio 19F1)…")
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
                log("Boli encontrado: ${result.device.address}")
                // ¡Importante! NO conectar en el hilo del callback de scan: es la causa
                // típica del GATT error 133. Saltamos al hilo principal.
                main.post { connect(result.device) }
            }
            override fun onScanFailed(errorCode: Int) {
                log("Scan falló (código $errorCode)")
                update { it.copy(status = PenStatus.IDLE, error = "scan-failed") }
            }
        }
        scanCb = cb
        scanner.startScan(filters, settings, cb)
        // timeout de scan: 15 s
        main.postDelayed({
            if (!connecting) {
                log("Scan agotado (15 s) sin encontrar el boli")
                stopScan()
                update { it.copy(status = PenStatus.IDLE) }
            }
        }, 15_000)
    }

    private fun stopScan() {
        scanCb?.let { runCatching { scanner?.stopScan(it) } }
        scanCb = null
    }

    private fun connect(device: BluetoothDevice) {
        target = device
        connectAttempts = 0
        reassembler = Protocol.FrameReassembler(::onFrame)
        openGatt(device)
    }

    private fun openGatt(device: BluetoothDevice) {
        connectAttempts++
        log("Conectando GATT (intento $connectAttempts) a ${device.address}…")
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
            log("Reintentando conexión en 600 ms…")
            main.postDelayed({ openGatt(device) }, 600)
        } else {
            log("✗ Conexión fallida tras $connectAttempts intentos")
            connecting = false
            update { it.copy(status = PenStatus.DISCONNECTED, error = "connect-failed") }
        }
    }

    fun disconnect() {
        log("Detener / desconectar solicitado")
        connecting = false
        target = null
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        update { it.copy(status = PenStatus.IDLE, offlinePhase = OfflinePhase.IDLE) }
    }

    private fun write(frame: ByteArray) {
        val g = gatt ?: return
        val ch = writeChar ?: run { log("⚠ write() sin característica de escritura"); return }
        // Usa NO_RESPONSE si la característica lo soporta (lo habitual en NeoLAB);
        // si no, escritura con respuesta.
        val noResp = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ch.writeType = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = frame
        val ok = g.writeCharacteristic(ch)
        log("→ TX (${if (noResp) "noRsp" else "rsp"}, ok=$ok): ${frame.toHex()}")
    }

    // ----------------------------------------------------------------------------------
    // GATT callback
    // ----------------------------------------------------------------------------------

    /** Habilita las notificaciones (CCCD) y, al confirmarse, arranca el handshake. */
    private fun enableNotifications(g: BluetoothGatt) {
        val notify = notifyChar ?: run {
            log("⚠ enableNotifications sin característica notify")
            return
        }
        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(Protocol.CCCD)
        if (cccd != null) {
            // Espera a onDescriptorWrite antes de escribir: Android sólo permite una
            // operación GATT a la vez.
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            cccd.value = value
            g.writeDescriptor(cccd)
            log("Escribiendo CCCD para habilitar notificaciones…")
        } else {
            log("⚠ Notify sin CCCD; arrancando handshake sin descriptor")
            write(Protocol.reqVersion())
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Fallo de conexión (típicamente 133): cierra y reintenta.
                log("⚠ Cambio de estado con error (status=$status, newState=$newState)")
                retryOrFail()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectAttempts = 0
                log("GATT conectado, descubriendo servicios…")
                // Descubrir servicios en el hilo principal mejora la estabilidad en
                // algunos fabricantes; un pequeño retardo evita carreras tras conectar.
                main.postDelayed({ g.discoverServices() }, 250)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT desconectado")
                connecting = false
                g.close()
                if (gatt === g) gatt = null
                update { it.copy(status = PenStatus.DISCONNECTED) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(Protocol.SERVICE_V2) ?: g.getService(Protocol.SERVICE_V5)
            if (service == null) {
                log("✗ Servicio 19F1/v5 no encontrado. Servicios: " +
                    g.services.joinToString { it.uuid.toString() })
                update { it.copy(status = PenStatus.DISCONNECTED, error = "service-not-found") }
                return
            }
            log("Servicio encontrado: ${service.uuid}")

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

            log("Características → write=${writeChar?.uuid} notify=${notify?.uuid}")
            if (writeChar == null || notify == null) {
                log("✗ Faltan características. Disponibles: " +
                    chars.joinToString { "${it.uuid}(props=${it.properties})" })
                update { it.copy(status = PenStatus.DISCONNECTED, error = "chars-not-found") }
                return
            }
            notifyChar = notify

            // ¡CLAVE! El MTU por defecto de Android es 23 (máx. 20 bytes por escritura), pero
            // el frame reqVersion mide 51 bytes y se truncaría → el boli nunca vería un frame
            // completo y cerraría la conexión (status 19). Web Bluetooth (la webapp) negocia
            // el MTU solo; en Android hay que pedirlo a mano ANTES del handshake.
            log("Solicitando MTU 247…")
            if (!g.requestMtu(247)) {
                log("⚠ requestMtu devolvió false; continúo con MTU por defecto")
                enableNotifications(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU negociado: $mtu (status=$status)")
            enableNotifications(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Notificaciones habilitadas: ahora sí es seguro arrancar el handshake.
            log("CCCD escrito (status=$status) → enviando reqVersion (0x01)")
            write(Protocol.reqVersion())
        }

        // Android 13+ (API 33): entrega el valor por parámetro.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (_state.value.status != PenStatus.AUTHORIZED) log("← RX crudo: ${value.toHex()}")
            reassembler?.feed(value)
        }

        // Android ≤ 12: el valor va en ch.value.
        @Deprecated("Compat <33", ReplaceWith(""))
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val value = ch.value ?: return
            if (_state.value.status != PenStatus.AUTHORIZED) log("← RX crudo: ${value.toHex()}")
            reassembler?.feed(value)
        }
    }

    // ----------------------------------------------------------------------------------
    // Dispatch de frames
    // ----------------------------------------------------------------------------------

    private fun onFrame(frame: Protocol.Frame) {
        log("← Frame cmd=0x%02X result=%d len=%d".format(frame.cmd, frame.result, frame.data.size))
        when (frame.cmd) {
            Protocol.VERSION_RESPONSE -> {
                val (name, fw, mac) = Protocol.parseVersionInfo(frame.data)
                log("✓ VERSION_RESPONSE name=$name fw=$fw mac=$mac → ReqPenStatus")
                update {
                    it.copy(
                        name = name.ifBlank { it.name },
                        firmware = fw.ifBlank { it.firmware },
                        mac = mac.ifBlank { it.mac },
                    )
                }
                write(Protocol.reqSettingInfo()) // ReqPenStatus: la respuesta autoriza
            }
            Protocol.PASSWORD_REQUEST -> {
                log("PASSWORD_REQUEST → enviando contraseña vacía (M1+ por defecto)")
                write(Protocol.resPassword(""))
            }
            Protocol.SETTING_INFO_RESPONSE -> {
                val settings = Protocol.parseSettingInfo(frame.data)
                val firstTime = _state.value.status != PenStatus.AUTHORIZED
                update { it.copy(status = PenStatus.AUTHORIZED, error = null, settings = settings) }
                if (firstTime) {
                    log("✓ AUTORIZADO (batería ${settings.battery}%) → pido datos en tiempo real")
                    write(Protocol.reqOnlineDataAll())
                }
            }

            Protocol.ONLINE_PAPER_INFO -> currentSobp = Protocol.parsePaperInfo(frame.data, false)
            Protocol.ONLINE_NEW_PAPER_INFO -> currentSobp = Protocol.parsePaperInfo(frame.data, true)
            Protocol.ONLINE_PEN_UPDOWN ->
                if (Protocol.isUpDownDown(frame.data)) beginStroke(Protocol.parsePenDownTime(frame.data, frame.cmd))
                else endStroke()
            Protocol.ONLINE_NEW_PEN_DOWN -> beginStroke(Protocol.parsePenDownTime(frame.data, frame.cmd))
            Protocol.ONLINE_PEN_DOT -> appendDot(frame.data, false)
            Protocol.ONLINE_NEW_PEN_DOT -> appendDot(frame.data, true)
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

    private fun beginStroke(downTime: Long) {
        penTime = if (downTime > 0) downTime else System.currentTimeMillis()
        strokeStart = penTime
        strokeDots.clear()
    }

    private fun appendDot(data: ByteArray, isNew: Boolean) {
        val d = Protocol.parseDotEvent(data, isNew)
        penTime += d.timeAdd
        if (strokeDots.isEmpty() && strokeStart == 0L) strokeStart = penTime
        strokeDots.add(Dot(d.x, d.y, d.force, penTime, Protocol.DOT_MOVE))
    }

    private fun endStroke() {
        if (strokeDots.isEmpty()) return
        val sobp = currentSobp
        // Cambio de página → cierra la anterior antes de acumular en la nueva.
        if (sobp != null && pageSobp != null && pageSobp != sobp) flushPage()
        if (sobp != null) pageSobp = sobp
        pageStrokes.add(Stroke(strokeStart, strokeDots.last().t, strokeDots.toList()))
        strokeDots.clear()
        strokeStart = 0L
        update { it.copy(capturedPages = pageStrokes.size) }
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
        update { it.copy(capturedPages = 0) }
    }

    // ----------------------------------------------------------------------------------
    // Offline (memoria del boli)
    // ----------------------------------------------------------------------------------

    // ----------------------------------------------------------------------------------
    // Ajustes del boli (pestaña Configuración). VERIFY: ids de SETTING_TYPE contra el SDK.
    // El estado se actualiza de forma optimista para que la UI responda.
    // ----------------------------------------------------------------------------------

    fun setAutoPowerOn(on: Boolean) {
        write(Protocol.reqSettingOnOff(Protocol.SET_AUTO_POWER_ON, on))
        update { it.copy(settings = it.settings.copy(autoPowerOn = on)) }
    }
    fun setBeep(on: Boolean) {
        write(Protocol.reqSettingOnOff(Protocol.SET_BEEP, on))
        update { it.copy(settings = it.settings.copy(beep = on)) }
    }
    fun setOfflineData(on: Boolean) {
        write(Protocol.reqSettingOnOff(Protocol.SET_OFFLINE_DATA, on))
        update { it.copy(settings = it.settings.copy(offlineData = on)) }
    }
    fun setAutoShutdown(minutes: Int) {
        write(Protocol.reqSettingShort(Protocol.SET_AUTO_POWER_OFF_TIME, minutes))
        update { it.copy(settings = it.settings.copy(autoShutdownTime = minutes)) }
    }

    fun listOffline() {
        update { it.copy(offlinePhase = OfflinePhase.LISTING, error = null) }
        write(Protocol.reqOfflineNoteList())
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
