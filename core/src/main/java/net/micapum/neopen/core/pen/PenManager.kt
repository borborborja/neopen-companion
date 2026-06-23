package net.micapum.neopen.core.pen

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
 * Capa BLE del Neo smartpen. Escanea por el servicio v2 (0x19F1), conecta,
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

    // Cola de escritura: Android sólo admite UNA operación GATT a la vez; sin cola,
    // dos write() seguidos hacen que el segundo falle (ok=false).
    private val txQueue = ArrayDeque<ByteArray>()
    private var txBusy = false

    // Conexión: evita reentradas del scan y permite reintentar el clásico GATT 133.
    private var connecting = false
    private var target: BluetoothDevice? = null
    private var connectAttempts = 0
    private var foregroundReconnect = false

    /**
     * Reconexión automática ante desconexiones inesperadas (no solicitadas por el
     * usuario). La activa el servicio en primer plano para mantener el boli vivo.
     */
    var autoReconnect = false

    /** Volcar la memoria offline automáticamente al autorizar (ajuste del usuario). */
    var autoImportOnConnect = false

    /**
     * Gate de re-descarga (Problema 2): nº de trazos ya importado por nota, indexado por
     * `"section.owner.note"`. Lo mantiene el grafo desde Room. Si al pedir una nota el total
     * de trazos que reporta el boli coincide con el guardado, se omite su transferencia (no
     * destructivo: el boli conserva su copia). El primer volcado nunca tiene contador → se
     * importa entero; el gate solo actúa en re-volcados.
     */
    @Volatile var knownOfflineCounts: Map<String, Int> = emptyMap()
    /** Una nota se ha importado por completo: (nota, total de trazos) para persistir su contador. */
    var onOfflineNoteImported: ((OfflineNote, Int) -> Unit)? = null
    /** El volcado completo (toda la cola de notas) ha terminado: dispara UN solo sync al final. */
    var onOfflineImportComplete: (() -> Unit)? = null

    /**
     * Modo escritura en vivo: cada trazo completo se entrega de inmediato por `onStroke`
     * (en vez de esperar al `flushPage` de 8 s), y NO se acumulan ni suben páginas — así el
     * texto de transcripción no ensucia el cuaderno de la app. Lo activa/desactiva el IME.
     */
    var onStroke: ((Stroke) -> Unit)? = null
    private var liveMode = false

    /** Estilo de trazo del boli (por MAC). Lo provee el grafo desde los ajustes. */
    var styleProvider: (String?) -> StrokeStyle = { StrokeStyle() }
    /** Notifica un boli recién autorizado (MAC, nombre) para registrarlo si es nuevo. */
    var onPenSeen: (String, String?) -> Unit = { _, _ -> }

    /** PIN recordado para un boli (MAC) → null si no hay. Lo provee el grafo (cifrado). */
    var savedPinFor: (String) -> String? = { null }
    /** Persiste (o borra con pin=null) el PIN recordado de un boli. */
    var onPinRemembered: (String, String?) -> Unit = { _, _ -> }

    /** Decide si una conexión está permitida para este scan (manual=true desde la UI). */
    var connectionAllowed: (String, Boolean) -> Boolean = { _, _ -> true }

    /** Estilo a sellar en los trazos capturados por el boli conectado. */
    private fun currentStyle(): StrokeStyle = styleProvider(_state.value.mac)
    /** Valor de `offlineData` antes de entrar en modo-vivo, para restaurarlo al salir. */
    private var offlineDataBeforeLive: Boolean? = null

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

    /** Permite a otros componentes (p. ej. la subida al servidor) escribir en el registro. */
    fun pushLog(msg: String) = log(msg)

    // --- Herramienta "Leer SOBP": captura puntual del SOBP del próximo trazo. ---
    private var sobpRequest: ((Sobp) -> Unit)? = null
    /** Entrega (una sola vez) el SOBP del siguiente dot que escriba el usuario. */
    fun captureNextSobp(cb: (Sobp) -> Unit) { sobpRequest = cb }
    fun cancelSobpCapture() { sobpRequest = null }

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
        const val AUTO_RECONNECT_DELAY_MS = 8_000L
        /** Backstop: si una nota offline no progresa en este tiempo, se pasa a la siguiente. */
        const val OFFLINE_NOTE_TIMEOUT_MS = 8_000L
    }

    private val autoReconnectScan = Runnable {
        if (shouldAutoReconnect() && !_state.value.connected) {
            scanAndConnectAuto()
        }
    }

    // ----------------------------------------------------------------------------------
    // Scan + connect
    // ----------------------------------------------------------------------------------

    fun scanAndConnect() = scanAndConnect(manual = true)

    /** Escaneo usado por servicios/reconexiones automáticas: respeta olvidados y bloqueo. */
    fun scanAndConnectAuto() = scanAndConnect(manual = false)

    /** Reconexión automática mientras la app está visible, sin servicio en primer plano. */
    fun setForegroundReconnect(enabled: Boolean) {
        foregroundReconnect = enabled
        if (enabled) {
            log("Reconexión automática en primer plano activada")
            scheduleAutoReconnect(0L)
        } else {
            log("Reconexión automática en primer plano detenida")
            main.removeCallbacks(autoReconnectScan)
            if (!autoReconnect) update { it.copy(autoReconnecting = false) }
        }
    }

    private fun scanAndConnect(manual: Boolean) {
        main.removeCallbacks(autoReconnectScan)
        if (connecting || _state.value.status == PenStatus.SCANNING || _state.value.status == PenStatus.CONNECTING) {
            log("Scan ignorado: ya hay una conexión en curso")
            return
        }
        if (!manual && _state.value.connected) return
        val scanner = adapter?.bluetoothLeScanner ?: run {
            log("Bluetooth apagado: no hay scanner")
            update { it.copy(status = PenStatus.IDLE, error = "bluetooth-off", autoReconnecting = !manual) }
            if (!manual) scheduleAutoReconnect()
            return
        }
        this.scanner = scanner
        log("Escaneando boli (servicio 19F1, ${if (manual) "manual" else "auto"})…")
        update { it.copy(status = PenStatus.SCANNING, error = null, autoReconnecting = !manual) }
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
                if (!connectionAllowed(result.device.address, manual)) {
                    log("Boli ignorado por política de conexión: ${result.device.address}")
                    return
                }
                connecting = true
                scanner.stopScan(this)
                log("Boli encontrado: ${result.device.address}")
                // ¡Importante! NO conectar en el hilo del callback de scan: es la causa
                // típica del GATT error 133. Saltamos al hilo principal.
                main.post { connect(result.device) }
            }
            override fun onScanFailed(errorCode: Int) {
                log("Scan falló (código $errorCode)")
                update { it.copy(status = PenStatus.IDLE, error = "scan-failed", autoReconnecting = !manual) }
                if (!manual) scheduleAutoReconnect()
            }
        }
        scanCb = cb
        scanner.startScan(filters, settings, cb)
        // timeout de scan: 15 s
        main.postDelayed({
            if (!connecting) {
                log("Scan agotado (15 s) sin encontrar el boli")
                stopScan()
                update { it.copy(status = PenStatus.IDLE, autoReconnecting = !manual) }
                if (!manual) scheduleAutoReconnect()
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
        autoUnlockTried = false
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
            update { it.copy(status = PenStatus.DISCONNECTED, error = "connect-failed", autoReconnecting = shouldAutoReconnect()) }
            if (shouldAutoReconnect()) scheduleAutoReconnect()
        }
    }

    fun disconnect() {
        log("Detener / desconectar solicitado")
        connecting = false
        target = null
        main.removeCallbacksAndMessages(null)
        stopScan()
        txQueue.clear()
        txBusy = false
        resetOfflineState()
        pwOp = PwOp.NONE
        pendingNewPassword = ""
        rememberPin = null
        unlockIsAuto = false
        autoUnlockTried = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        update { it.copy(status = PenStatus.IDLE, offlinePhase = OfflinePhase.IDLE, autoReconnecting = false) }
    }

    private fun shouldAutoReconnect(): Boolean = autoReconnect || foregroundReconnect

    private fun scheduleAutoReconnect(delayMs: Long = AUTO_RECONNECT_DELAY_MS) {
        if (!shouldAutoReconnect()) return
        main.removeCallbacks(autoReconnectScan)
        val delay = delayMs.coerceAtLeast(0L)
        log(if (delay == 0L) "Reconexión automática: buscando…" else "Reconexión automática en ${delay / 1000} s…")
        main.postDelayed(autoReconnectScan, delay)
    }

    /** Encola un frame para escribir. Se serializa en el hilo principal. */
    private fun write(frame: ByteArray) {
        main.post { txQueue.addLast(frame); pumpTx() }
    }

    /** Envía el siguiente frame de la cola si no hay otra escritura en curso. */
    private fun pumpTx() {
        if (txBusy) return
        val g = gatt ?: run { txQueue.clear(); return }
        val ch = writeChar ?: run { log("⚠ pumpTx sin característica de escritura"); txQueue.clear(); return }
        val frame = if (txQueue.isEmpty()) return else txQueue.removeFirst()
        txBusy = true
        val noResp = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ch.writeType = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = frame
        val ok = g.writeCharacteristic(ch)
        log("→ TX (${if (noResp) "noRsp" else "rsp"}, ok=$ok): ${frame.toHex()}")
        if (!ok) {
            // No se pudo encolar en la pila: reintenta el bombeo tras un respiro.
            txBusy = false
            main.postDelayed({ pumpTx() }, 60)
        }
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
                resetOfflineState()
                val device = target
                // Desconexión inesperada (target != null = no la pidió el usuario):
                // reintenta sola si la reconexión automática está activa.
                if (shouldAutoReconnect() && device != null && connectionAllowed(device.address, false)) {
                    log("Reconexión automática en 1,5 s…")
                    connectAttempts = 0
                    update { it.copy(status = PenStatus.CONNECTING, error = null, autoReconnecting = true) }
                    main.postDelayed({ if (shouldAutoReconnect() && target === device) openGatt(device) }, 1500)
                } else {
                    update { it.copy(status = PenStatus.DISCONNECTED, autoReconnecting = false) }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(Protocol.SERVICE_V2) ?: g.getService(Protocol.SERVICE_V5)
            if (service == null) {
                log("✗ Servicio 19F1/v5 no encontrado. Servicios: " +
                    g.services.joinToString { it.uuid.toString() })
                update { it.copy(status = PenStatus.DISCONNECTED, error = "service-not-found", autoReconnecting = shouldAutoReconnect()) }
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
                update { it.copy(status = PenStatus.DISCONNECTED, error = "chars-not-found", autoReconnecting = shouldAutoReconnect()) }
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

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            // Escritura completada → libera la cola y manda el siguiente frame.
            main.post { txBusy = false; pumpTx() }
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
                val (name, fw, _) = Protocol.parseVersionInfo(frame.data)
                log("✓ VERSION_RESPONSE name=$name fw=$fw → ReqPenStatus")
                // La MAC se mantiene la del escaneo (device.address); la del payload varía
                // de offset entre firmwares y aquí no es fiable.
                update {
                    it.copy(name = name.ifBlank { it.name }, firmware = fw.ifBlank { it.firmware })
                }
                write(Protocol.reqSettingInfo()) // ReqPenStatus: la respuesta autoriza
            }
            Protocol.SETTING_INFO_RESPONSE -> {
                val settings = Protocol.parseSettingInfo(frame.data)
                val (locked, maxRetry, retry) = Protocol.parseLockInfo(frame.data)
                val firstTime = _state.value.status != PenStatus.AUTHORIZED
                update {
                    it.copy(
                        settings = settings, error = null,
                        hasPassword = locked, passwordMaxRetry = maxRetry, passwordRetry = retry,
                    )
                }
                if (firstTime && locked) {
                    update { it.copy(status = PenStatus.LOCKED, autoReconnecting = false) }
                    // ¿Tenemos PIN guardado para este boli? Desbloqueo automático (1 intento).
                    val saved = _state.value.mac?.let { savedPinFor(it) }
                    if (!saved.isNullOrEmpty() && !autoUnlockTried) {
                        autoUnlockTried = true
                        autoUnlock(saved)
                    } else {
                        log("🔒 Boli con contraseña (intentos $retry/$maxRetry) → se requiere PIN")
                    }
                } else if (firstTime) {
                    onAuthorized(settings.battery)
                }
            }
            Protocol.PASSWORD_RESPONSE -> {
                val (status, retry, reset) = Protocol.parsePasswordResponse(frame.data)
                log("← PASSWORD_RESPONSE status=$status retry=$retry reset=$reset op=$pwOp")
                if (pwOp == PwOp.SETUP) {
                    // Resultado de reautenticar tras poner/cambiar contraseña.
                    pwOp = PwOp.NONE
                    if (status == 1) {
                        update { it.copy(hasPassword = pendingNewPassword.isNotEmpty(), error = null) }
                        pendingNewPassword = ""
                        onAuthorized(_state.value.settings.battery)
                    } else {
                        update { it.copy(error = "password-setup-failed") }
                    }
                } else {
                    // Resultado de desbloquear con el PIN.
                    when (status) {
                        1 -> {
                            pwOp = PwOp.NONE
                            log("✓ PIN correcto")
                            // Guarda el PIN si el usuario lo pidió ("recordar").
                            rememberPin?.let { p -> _state.value.mac?.let { onPinRemembered(it, p) } }
                            rememberPin = null
                            onAuthorized(_state.value.settings.battery)
                        }
                        2 -> update { it.copy(error = "password-reset", passwordRetry = retry) } // boli restablecido
                        else -> {
                            // PIN incorrecto. Si venía del PIN guardado, olvídalo para no
                            // gastar más intentos automáticamente; pasa a pedirlo a mano.
                            if (unlockIsAuto) {
                                log("⚠ El PIN guardado es incorrecto → lo olvido y pido el PIN")
                                _state.value.mac?.let { onPinRemembered(it, null) }
                            }
                            rememberPin = null
                            update { it.copy(status = PenStatus.LOCKED, error = "password-wrong", passwordRetry = retry, passwordMaxRetry = if (reset > 0) reset else it.passwordMaxRetry) }
                        }
                    }
                    unlockIsAuto = false
                }
            }
            Protocol.PASSWORD_CHANGE_RESPONSE -> {
                val (status, retry, reset) = Protocol.parsePasswordChange(frame.data)
                log("← PASSWORD_CHANGE_RESPONSE status=$status retry=$retry reset=$reset")
                if (status == 0) {
                    if (pendingNewPassword.isEmpty()) {
                        // Eliminación de contraseña completada.
                        log("✓ Contraseña eliminada")
                        update { it.copy(hasPassword = false, error = null) }
                        onAuthorized(_state.value.settings.battery)
                    } else {
                        // Poner/cambiar OK → reautenticar con el nuevo PIN.
                        pwOp = PwOp.SETUP
                        write(Protocol.reqInputPassword(pendingNewPassword))
                    }
                } else {
                    pendingNewPassword = ""
                    pwOp = PwOp.NONE
                    update { it.copy(error = if (status == 2) "password-reset" else "password-change-failed", passwordRetry = retry) }
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
            Protocol.OFFLINE_DATA_RESPONSE -> onOfflineDataResponse(Protocol.parseOfflineTotalStrokes(frame.data))
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
        // Herramienta "Leer SOBP": entrega el SOBP actual una sola vez.
        val req = sobpRequest
        val s = currentSobp
        if (req != null && s != null) {
            sobpRequest = null
            main.post { req(s) }
        }
    }

    private fun endStroke() {
        if (strokeDots.isEmpty()) return
        // Sella el estilo del boli en el trazo (congelado al capturar).
        val s = currentStyle()
        val stroke = Stroke(strokeStart, strokeDots.last().t, strokeDots.toList(), s.color, s.width)
        // Modo escritura en vivo: entrega el trazo de inmediato y NO acumula/sube páginas.
        if (liveMode) {
            strokeDots.clear()
            strokeStart = 0L
            val cb = onStroke
            if (cb != null) main.post { cb(stroke) }
            return
        }
        val sobp = currentSobp
        // Cambio de página → cierra la anterior antes de acumular en la nueva.
        if (sobp != null && pageSobp != null && pageSobp != sobp) flushPage()
        if (sobp != null) pageSobp = sobp
        pageStrokes.add(stroke)
        log("✏ Trazo: ${strokeDots.size} dots (página ${pageStrokes.size}, sobp ${sobp?.section}.${sobp?.owner}.${sobp?.book}.${sobp?.page})")
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
        log("📄 Página completa sobp ${sobp.section}.${sobp.owner}.${sobp.book}.${sobp.page}: ${pageStrokes.size} trazos → subiendo al servidor…")
        onPage(StrokePage(sobp, "realtime", iso.format(System.currentTimeMillis()), pageStrokes.toList(), _state.value.mac))
        pageStrokes.clear()
        pageSobp = null
        update { it.copy(capturedPages = 0) }
    }

    // ----------------------------------------------------------------------------------
    // Contraseña / PIN del boli
    // ----------------------------------------------------------------------------------

    private enum class PwOp { NONE, UNLOCK, SETUP }
    private var pwOp = PwOp.NONE
    private var pendingNewPassword = ""
    private var rememberPin: String? = null   // PIN a recordar si el desbloqueo va bien
    private var unlockIsAuto = false           // el intento actual vino del PIN guardado
    private var autoUnlockTried = false        // ya probamos el PIN guardado en esta conexión

    /** Tras autenticar: marca AUTORIZADO, pide datos en tiempo real y auto-vuelca offline. */
    private fun onAuthorized(battery: Int?) {
        update { it.copy(status = PenStatus.AUTHORIZED, error = null, autoReconnecting = false) }
        log("✓ AUTORIZADO (batería ${battery}%) → pido datos en tiempo real")
        _state.value.mac?.let { onPenSeen(it, _state.value.name) }
        write(Protocol.reqOnlineDataAll())
        if (autoImportOnConnect) {
            log("Auto-volcado al conectar: leyendo memoria offline…")
            main.postDelayed({ listOffline() }, 1_500)
            main.postDelayed({ importAllOffline() }, 4_000)
        }
    }

    /** Envía el PIN para desbloquear (manual). remember = guardarlo si es correcto. */
    fun unlockPen(pin: String, remember: Boolean = false) {
        pwOp = PwOp.UNLOCK
        unlockIsAuto = false
        rememberPin = if (remember) pin else null
        log("Enviando PIN para desbloquear…")
        write(Protocol.reqInputPassword(pin))
    }

    /** Desbloqueo automático con el PIN guardado (no reintenta si falla). */
    private fun autoUnlock(pin: String) {
        pwOp = PwOp.UNLOCK
        unlockIsAuto = true
        rememberPin = null // ya está guardado
        log("PIN guardado → desbloqueo automático")
        write(Protocol.reqInputPassword(pin))
    }

    /** Pone (current="") o cambia (current=actual) la contraseña por una nueva. */
    fun setOrChangePenPassword(current: String, newPassword: String) {
        pendingNewPassword = newPassword
        write(Protocol.reqChangePassword(current, newPassword))
    }

    /** Elimina la contraseña del boli (queda sin PIN). */
    fun removePenPassword(current: String) {
        pendingNewPassword = ""
        write(Protocol.reqChangePassword(current, ""))
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
    fun setSensitivity(level: Int) {
        val v = level.coerceIn(0, 4)
        write(Protocol.reqSettingByte(Protocol.SET_SENSITIVITY, v))
        update { it.copy(settings = it.settings.copy(sensitivity = v)) }
    }

    // ----------------------------------------------------------------------------------
    // Modo escritura en vivo (lo conduce el IME)
    // ----------------------------------------------------------------------------------

    /**
     * Entra en modo escritura en vivo. Desactiva el grabado offline del boli (que, si está
     * activo, guarda los trazos en memoria a la vez que los transmite online) guardando su
     * valor previo, para que lo escrito en modo-vivo no reaparezca en la próxima importación.
     */
    fun enterLiveMode() {
        if (liveMode) return
        liveMode = true
        // Descarta cualquier página a medio acumular: en modo-vivo no se suben.
        main.removeCallbacks(idleFlush)
        pageStrokes.clear()
        pageSobp = null
        update { it.copy(capturedPages = 0) }
        offlineDataBeforeLive = _state.value.settings.offlineData
        if (_state.value.settings.offlineData != false) {
            log("Modo escritura en vivo: desactivando grabado offline del boli")
            setOfflineData(false)
        }
    }

    /** Sale del modo en vivo y restaura el grabado offline al valor que tenía antes. */
    fun exitLiveMode() {
        if (!liveMode) return
        liveMode = false
        val prev = offlineDataBeforeLive
        offlineDataBeforeLive = null
        if (prev == true && _state.value.settings.offlineData != true) {
            log("Saliendo de modo en vivo: restaurando grabado offline del boli")
            setOfflineData(true)
        }
    }

    fun listOffline() {
        update { it.copy(offlinePhase = OfflinePhase.LISTING, error = null) }
        write(Protocol.reqOfflineNoteList())
    }

    /** Importa una sola nota (botón por-nota de la UI). */
    fun importOffline(note: OfflineNote) {
        startOfflineImport(listOf(note))
    }

    /** Importa todas las notas listadas, una a una (con gate por contador). */
    fun importAllOffline() {
        startOfflineImport(_state.value.notes ?: emptyList())
    }

    fun clearOffline(notes: List<OfflineNote>) {
        notes.groupBy { it.section to it.owner }.forEach { (k, ns) ->
            write(Protocol.reqOfflineDelete(k.first, k.second, ns.map { it.note }.toIntArray()))
        }
    }

    // ----------------------------------------------------------------------------------
    // Volcado offline secuencial (una nota a la vez)
    //
    // Por cada nota: pedir datos → leer total de trazos (0xa3) → [gate por contador]
    // importar o saltar → al cerrar la nota (transPosition==2) emitir UNA StrokePage por
    // SOBP (bufferizado, sin parpadeo) y pasar a la siguiente. Al vaciar la cola, un único
    // aviso de "volcado completo" para que el grafo sincronice una sola vez.
    // ----------------------------------------------------------------------------------
    private val offlineQueue = ArrayDeque<OfflineNote>()
    @Volatile private var currentNote: OfflineNote? = null
    @Volatile private var skipCurrentNote = false
    private val offlineBuffer = LinkedHashMap<Sobp, MutableList<Stroke>>()
    @Volatile private var offlineTotalStrokes = 0
    private var offlineReceivedStrokes = 0

    private fun noteKey(n: OfflineNote) = "${n.section}.${n.owner}.${n.note}"

    /** Watchdog: si la nota en curso no progresa, avanza para no quedarse colgado. */
    private val offlineWatchdog = Runnable {
        if (currentNote != null) {
            log("⏱ Nota offline sin progreso → paso a la siguiente")
            requestNextOfflineNote()
        }
    }
    private fun armOfflineWatchdog() {
        main.removeCallbacks(offlineWatchdog)
        main.postDelayed(offlineWatchdog, OFFLINE_NOTE_TIMEOUT_MS)
    }

    /** Limpia el estado del volcado (al desconectar o terminar). */
    private fun resetOfflineState() {
        main.removeCallbacks(offlineWatchdog)
        offlineQueue.clear()
        currentNote = null
        skipCurrentNote = false
        offlineBuffer.clear()
        offlineTotalStrokes = 0
        offlineReceivedStrokes = 0
    }

    private fun startOfflineImport(notes: List<OfflineNote>) {
        if (notes.isEmpty()) {
            main.post { onOfflineImportComplete?.invoke() }
            return
        }
        offlineQueue.clear()
        offlineQueue.addAll(notes)
        requestNextOfflineNote()
    }

    private fun requestNextOfflineNote() {
        offlineBuffer.clear()
        skipCurrentNote = false
        offlineReceivedStrokes = 0
        offlineTotalStrokes = 0
        val note = if (offlineQueue.isEmpty()) null else offlineQueue.removeFirst()
        if (note == null) {
            main.removeCallbacks(offlineWatchdog)
            currentNote = null
            update { it.copy(offlinePhase = OfflinePhase.IDLE, offlineProgress = 100) }
            main.post { onOfflineImportComplete?.invoke() }
            return
        }
        currentNote = note
        update { it.copy(offlinePhase = OfflinePhase.TRANSFERRING, offlineProgress = 0) }
        write(Protocol.reqOfflineData(note.section, note.owner, note.note))
        armOfflineWatchdog()
    }

    /** 0xa3: total de trazos de la nota en curso. Aplica el gate por contador. */
    private fun onOfflineDataResponse(total: Int) {
        offlineTotalStrokes = total
        offlineReceivedStrokes = 0
        val note = currentNote
        val known = note?.let { knownOfflineCounts[noteKey(it)] }
        if (note != null && canSkipOfflineNote(known, total)) {
            // Nota sin cambios: no la transferimos (no acusaremos sus paquetes) y avanzamos.
            skipCurrentNote = true
            log("⏭ Nota ${noteKey(note)} sin cambios ($total trazos) → omito descarga")
            requestNextOfflineNote()
        } else {
            update { it.copy(offlinePhase = OfflinePhase.TRANSFERRING, offlineProgress = 0) }
            armOfflineWatchdog()
        }
    }

    /**
     * Recibe un chunk offline (0x24): lo acumula en el buffer de la nota en curso (agrupado
     * por SOBP) y acusa para recibir el siguiente. NO emite páginas por paquete: lo hace una
     * sola vez al cerrar la nota (transPosition==2), para que la bandeja no muestre páginas a
     * medias ni se reorganice a la vista. Paquetes de una nota que ya no transferimos (gate)
     * se ignoran sin acusar, para que el boli deje de enviarla.
     */
    private fun handleOfflinePacket(data: ByteArray) {
        try {
            val chunk = Protocol.parseOfflineChunk(data)
            val note = currentNote
            if (note != null &&
                (chunk.section != note.section || chunk.owner != note.owner || chunk.note != note.note)
            ) {
                return // paquete de otra nota (resto de una omitida): ni procesar ni acusar
            }
            if (skipCurrentNote) return
            armOfflineWatchdog()
            val raw = if (chunk.isCompressed) inflateZlib(chunk.data, chunk.beforeSize) else chunk.data
            val parsed = Protocol.parseOfflineStrokes(raw, chunk.section, chunk.owner, chunk.note, chunk.strokeCount)
            // Híbrido: sella en los trazos volcados el estilo ACTUAL del boli al volcar.
            val s = currentStyle()
            parsed.forEach { (sobp, stroke) ->
                offlineBuffer.getOrPut(sobp) { ArrayList() }.add(stroke.copy(color = s.color, width = s.width))
            }
            // Acusa para recibir el siguiente (el último no se acusa, como el SDK).
            if (chunk.transPosition != 2) write(Protocol.resOfflinePacket(chunk.packetId))
            offlineReceivedStrokes += chunk.strokeCount
            val pct =
                if (offlineTotalStrokes > 0) (offlineReceivedStrokes * 100 / offlineTotalStrokes).coerceIn(0, 100)
                else if (chunk.transPosition == 2) 100 else 50
            update { it.copy(offlineProgress = pct) }
            if (chunk.transPosition == 2) finishOfflineNote()
        } catch (e: Exception) {
            log("⚠ Error parseando chunk offline: ${e.message}")
            update { it.copy(error = "offline-parse") }
        }
    }

    /** Cierre de la nota en curso: emite UNA página por SOBP, persiste su contador y sigue. */
    private fun finishOfflineNote() {
        main.removeCallbacks(offlineWatchdog)
        val note = currentNote
        val mac = _state.value.mac
        val now = iso.format(System.currentTimeMillis())
        offlineBuffer.forEach { (sobp, strokes) ->
            log("📄 Offline sobp ${sobp.section}.${sobp.owner}.${sobp.book}.${sobp.page}: ${strokes.size} trazos")
            onPage(StrokePage(sobp, "offline", now, strokes.toList(), mac))
        }
        offlineBuffer.clear()
        if (note != null) {
            val total = offlineTotalStrokes
            main.post { onOfflineNoteImported?.invoke(note, total) }
        }
        requestNextOfflineNote()
    }

    /** Descomprime datos zlib (cabecera estándar) a su tamaño original. */
    private fun inflateZlib(data: ByteArray, expectedSize: Int): ByteArray {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(maxOf(expectedSize, data.size))
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    // ----------------------------------------------------------------------------------

    private inline fun update(f: (PenState) -> PenState) {
        _state.value = f(_state.value)
    }
}
