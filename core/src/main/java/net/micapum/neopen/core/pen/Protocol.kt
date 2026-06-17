package net.micapum.neopen.core.pen

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Protocolo v2 del Neo smartpen.
 *
 * Reconstruido fielmente del SDK oficial `web_pen_sdk` (el que usa la web de
 * neopen, que conecta correctamente). Puntos clave que difieren de versiones
 * anteriores de este archivo:
 *  - Servicio BLE 0x19F1, write 0x2BA0, notify 0x2BA1.
 *  - Frame: STX(0xC0) CMD LEN(2 LE) DATA ETX(0xC1).  **SIN checksum.**
 *  - Escape DLE(0x7D): 0xC0/0xC1/0x7D → [0x7D, b XOR 0x20].
 *  - Las RESPUESTAS del boli traen un byte `result` tras el CMD (salvo eventos
 *    0x6X, 0x73, 0x24, 0x32).
 *  - reqVersion lleva 42 bytes (no vacío) o el boli cierra la conexión.
 */
object Protocol {
    // --- UUIDs (servicio v2 19F1; v5 para pens nuevos, solo fallback de scan) ---
    val SERVICE_V2: UUID = uuid16(0x19F1)
    val CHAR_WRITE: UUID = uuid16(0x2BA0)
    val CHAR_NOTIFY: UUID = uuid16(0x2BA1)
    val SERVICE_V5: UUID = UUID.fromString("4f99f138-9d53-5bfa-9e50-b147491afe68")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val STX = 0xC0
    const val ETX = 0xC1
    const val DLE = 0x7D

    // --- Comandos host → pen ---
    const val VERSION_REQUEST = 0x01
    const val PASSWORD_INPUT = 0x02          // host envía el PIN (SDK: PASSWORD_REQUEST)
    const val PASSWORD_CHANGE_REQUEST = 0x03 // poner/cambiar/eliminar contraseña
    const val SETTING_INFO_REQUEST = 0x04
    const val SETTING_CHANGE_REQUEST = 0x05
    const val ONLINE_DATA_REQUEST = 0x11
    const val OFFLINE_NOTE_LIST_REQUEST = 0x21
    const val OFFLINE_PAGE_LIST_REQUEST = 0x22
    const val OFFLINE_DATA_REQUEST = 0x23
    const val OFFLINE_PACKET_RESPONSE = 0xa4
    const val OFFLINE_DATA_DELETE_REQUEST = 0x25

    // --- Eventos pen → host ---
    const val VERSION_RESPONSE = 0x81
    const val PASSWORD_RESPONSE = 0x82          // resultado de introducir el PIN
    const val PASSWORD_CHANGE_RESPONSE = 0x83   // resultado de cambiar/eliminar
    const val SETTING_INFO_RESPONSE = 0x84
    const val SETTING_CHANGE_RESPONSE = 0x85
    const val ONLINE_PEN_UPDOWN = 0x63
    const val ONLINE_PAPER_INFO = 0x64
    const val ONLINE_PEN_DOT = 0x65
    const val ONLINE_NEW_PEN_DOWN = 0x69
    const val ONLINE_NEW_PEN_UP = 0x6a
    const val ONLINE_NEW_PAPER_INFO = 0x6b
    const val ONLINE_NEW_PEN_DOT = 0x6c
    const val OFFLINE_NOTE_LIST_RESPONSE = 0xa1
    const val OFFLINE_PAGE_LIST_RESPONSE = 0xa2
    const val OFFLINE_DATA_RESPONSE = 0xa3
    const val OFFLINE_PACKET_REQUEST = 0x24
    const val OFFLINE_DATA_DELETE_RESPONSE = 0xa5

    // --- SettingType (para SETTING_CHANGE_REQUEST), del SDK ---
    const val SET_TIMESTAMP = 1
    const val SET_AUTO_POWER_OFF_TIME = 2
    const val SET_PEN_CAP_OFF = 3
    const val SET_AUTO_POWER_ON = 4
    const val SET_BEEP = 5
    const val SET_HOVER = 6
    const val SET_OFFLINE_DATA = 7
    const val SET_LED_COLOR = 8
    const val SET_SENSITIVITY = 9

    // Tipos de dot
    const val DOT_DOWN = 0
    const val DOT_MOVE = 1
    const val DOT_UP = 2
    const val DOT_HOVER = 3

    private fun uuid16(v: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

    // ----------------------------------------------------------------------------------
    // Framing
    // ----------------------------------------------------------------------------------

    /** Construye un frame v2: STX, CMD, LEN(2 LE), DATA, ETX. STX/ETX sin escapar. */
    fun buildFrame(cmd: Int, data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size + 8)
        out.add(STX.toByte())
        putEscaped(out, cmd)
        putEscaped(out, data.size and 0xff)
        putEscaped(out, (data.size shr 8) and 0xff)
        for (b in data) putEscaped(out, b.toInt() and 0xff)
        out.add(ETX.toByte())
        return out.toByteArray()
    }

    private fun putEscaped(out: ArrayList<Byte>, b: Int) {
        val v = b and 0xff
        if (v == STX || v == ETX || v == DLE) {
            out.add(DLE.toByte()); out.add((v xor 0x20).toByte())
        } else out.add(v.toByte())
    }

    /** Un frame ya des-escapado: cmd, result (o -1) y data (sin result ni longitud). */
    data class Frame(val cmd: Int, val result: Int, val data: ByteArray)

    /** ¿El comando entrante incluye byte `result` tras el CMD? (regla del SDK). */
    private fun hasResult(cmd: Int): Boolean =
        (cmd shr 4) != 0x6 && cmd != 0x73 && cmd != 0x24 && cmd != 0x32

    /**
     * Reensambla frames del stream de notificaciones BLE. Mantén una instancia por
     * conexión. Detecta STX/ETX, des-escapa DLE y extrae cmd/result/longitud/data.
     */
    class FrameReassembler(private val onFrame: (Frame) -> Unit) {
        private val buf = ArrayList<Byte>()
        private var inFrame = false
        private var escaped = false

        fun feed(chunk: ByteArray) {
            for (raw in chunk) {
                val b = raw.toInt() and 0xff
                when {
                    b == STX -> { inFrame = true; escaped = false; buf.clear() }
                    !inFrame -> { /* ruido fuera de frame */ }
                    b == ETX -> { inFrame = false; emit() }
                    escaped -> { buf.add((b xor 0x20).toByte()); escaped = false }
                    b == DLE -> escaped = true
                    else -> buf.add(b.toByte())
                }
            }
        }

        private fun emit() {
            if (buf.isEmpty()) return
            val cmd = buf[0].toInt() and 0xff
            var idx = 1
            val result = if (hasResult(cmd)) {
                if (buf.size <= idx) return
                (buf[idx++].toInt() and 0xff)
            } else -1
            if (buf.size < idx + 2) return
            val len = (buf[idx].toInt() and 0xff) or ((buf[idx + 1].toInt() and 0xff) shl 8)
            idx += 2
            val end = minOf(idx + len, buf.size)
            val data = ByteArray(end - idx) { buf[idx + it] }
            onFrame(Frame(cmd, result, data))
        }
    }

    // ----------------------------------------------------------------------------------
    // Builders de comandos (host → pen) — fieles a PenRequestV2 del SDK
    // ----------------------------------------------------------------------------------

    /** reqVersion: 42 bytes (16 nulos, 0xF0, 0x01, appVer[16], protoVer[8]). */
    fun reqVersion(): ByteArray {
        val data = ByteArray(42)
        data[16] = 0xF0.toByte()
        data[17] = 0x01
        putAscii(data, 18, "0.0.0.0", 16)   // versión de app
        putAscii(data, 34, "2.18", 8)       // versión de protocolo soportada
        return buildFrame(VERSION_REQUEST, data)
    }

    /** Envía el PIN para desbloquear (host→pen, cmd 0x02). PIN ASCII en 16 bytes. */
    fun reqInputPassword(password: String): ByteArray {
        val data = ByteArray(16)
        putAscii(data, 0, password, 16)
        return buildFrame(PASSWORD_INPUT, data)
    }

    /**
     * Poner/cambiar/eliminar contraseña (cmd 0x03, len 33).
     * data = [flag][old 16][new 16]. flag=1 poner/cambiar, flag=0 eliminar.
     */
    fun reqChangePassword(oldPassword: String, newPassword: String): ByteArray {
        val data = ByteArray(33)
        data[0] = if (newPassword.isEmpty()) 0 else 1
        putAscii(data, 1, oldPassword, 16)
        putAscii(data, 17, newPassword, 16)
        return buildFrame(PASSWORD_CHANGE_REQUEST, data)
    }

    /** PASSWORD_RESPONSE (0x82) → (status, retry, reset). status 1 = correcto. */
    fun parsePasswordResponse(data: ByteArray): Triple<Int, Int, Int> =
        Triple(u8(data, 0), u8(data, 1), u8(data, 2))

    /** PASSWORD_CHANGE_RESPONSE (0x83) → (status, retry, reset). status 0 = OK. */
    fun parsePasswordChange(data: ByteArray): Triple<Int, Int, Int> =
        Triple(u8(data, 2), u8(data, 0), u8(data, 1))

    /** ReqPenStatus: pide ajustes/estado (batería, memoria…). Payload vacío. */
    fun reqSettingInfo(): ByteArray = buildFrame(SETTING_INFO_REQUEST, ByteArray(0))

    /** Activa la escritura en tiempo real de TODAS las libretas: data = [0xFF,0xFF]. */
    fun reqOnlineDataAll(): ByteArray =
        buildFrame(ONLINE_DATA_REQUEST, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))

    /** Cambio de ajuste on/off: data = [type, 0|1]. */
    fun reqSettingOnOff(type: Int, on: Boolean): ByteArray =
        buildFrame(SETTING_CHANGE_REQUEST, byteArrayOf(type.toByte(), if (on) 1 else 0))

    /** Cambio de ajuste con valor de un byte: data = [type, value]. */
    fun reqSettingByte(type: Int, value: Int): ByteArray =
        buildFrame(SETTING_CHANGE_REQUEST, byteArrayOf(type.toByte(), value.coerceIn(0, 255).toByte()))

    /** Cambio de ajuste con valor short LE: data = [type, lo, hi]. */
    fun reqSettingShort(type: Int, value: Int): ByteArray =
        buildFrame(SETTING_CHANGE_REQUEST, byteArrayOf(type.toByte(), (value and 0xff).toByte(), ((value shr 8) and 0xff).toByte()))

    fun reqOfflineNoteList(): ByteArray =
        buildFrame(OFFLINE_NOTE_LIST_REQUEST, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

    fun reqOfflinePageList(section: Int, owner: Int, note: Int): ByteArray {
        val bb = le(8)
        bb.put(sectionOwner(section, owner)).putInt(note)
        return buildFrame(OFFLINE_PAGE_LIST_REQUEST, bb.array())
    }

    /** Descarga datos offline. deleteOnFinished SIEMPRE false en el companion. */
    fun reqOfflineData(section: Int, owner: Int, note: Int, pages: IntArray = IntArray(0)): ByteArray {
        val bb = le(14 + pages.size * 4)
        bb.put(if (false) 1 else 2)     // delete=2 (no borrar)
            .put(0x01)
            .put(sectionOwner(section, owner))
            .putInt(note)
            .putInt(pages.size)
        pages.forEach { bb.putInt(it) }
        return buildFrame(OFFLINE_DATA_REQUEST, bb.array())
    }

    /**
     * ACK de un chunk offline (APP→PEN), fiel al SDK (`ReqOfflineData2`): tras el
     * CMD lleva un byte de éxito ANTES de la longitud, y el payload es
     * [index(2 LE), endByte]. `endByte` = 0 si es el último, 1 si quedan más.
     */
    fun resOfflinePacket(index: Int, end: Boolean = false): ByteArray {
        val out = ArrayList<Byte>(10)
        out.add(STX.toByte())
        putEscaped(out, OFFLINE_PACKET_RESPONSE)
        putEscaped(out, 0)                       // result/éxito = 0 (OK)
        putEscaped(out, 3); putEscaped(out, 0)   // longitud = 3 (LE short)
        putEscaped(out, index and 0xff)
        putEscaped(out, (index shr 8) and 0xff)  // index (LE short)
        putEscaped(out, if (end) 0 else 1)       // endByte
        out.add(ETX.toByte())
        return out.toByteArray()
    }

    fun reqOfflineDelete(section: Int, owner: Int, notes: IntArray): ByteArray {
        val bb = le(5 + notes.size * 4)
        bb.put(sectionOwner(section, owner)).put(notes.size.toByte())
        notes.forEach { bb.putInt(it) }
        return buildFrame(OFFLINE_DATA_DELETE_REQUEST, bb.array())
    }

    /** Empaqueta (section, owner) en 4 bytes: owner(3 LE) + section. */
    private fun sectionOwner(section: Int, owner: Int): ByteArray = byteArrayOf(
        (owner and 0xff).toByte(),
        ((owner shr 8) and 0xff).toByte(),
        ((owner shr 16) and 0xff).toByte(),
        (section and 0xff).toByte(),
    )

    // ----------------------------------------------------------------------------------
    // Parsers (pen → host) — offsets del SDK
    // ----------------------------------------------------------------------------------

    /** Un dot online ya parseado (coordenadas Ncode combinadas, tiempo relativo). */
    data class DotEvent(val timeAdd: Int, val x: Float, val y: Float, val force: Float)

    /** Parsea un dot de evento (0x65/0x6c). isNew omite el byte de count inicial. */
    fun parseDotEvent(data: ByteArray, isNew: Boolean): DotEvent {
        var o = if (isNew) 1 else 0
        val timeAdd = u8(data, o); o += 1
        val force = u16(data, o); o += 2
        val xi = u16(data, o); o += 2
        val yi = u16(data, o); o += 2
        val fx = u8(data, o); o += 1
        val fy = u8(data, o); o += 1
        // tx, ty, twist a continuación (no usados)
        val x = xi + fx * 0.01f
        val y = yi + fy * 0.01f
        return DotEvent(timeAdd, x, y, force.toFloat())
    }

    /** Tiempo absoluto (ms) de un PEN_DOWN. isNew = evento 0x69 (con count). */
    fun parsePenDownTime(data: ByteArray, cmd: Int): Long {
        // 0x69 (NEW): [count(1)][time(8)]. 0x63 (viejo): [isDown(1)][time(8)] solo si down.
        val o = if (cmd == ONLINE_NEW_PEN_DOWN) 1 else 1
        return u64(data, o)
    }

    /** ¿El evento 0x63 es un PEN_DOWN? (data[0]==0). */
    fun isUpDownDown(data: ByteArray): Boolean = u8(data, 0) == 0x00

    /** Parsea la info de papel (0x64/0x6b) → SOBP. isNew omite el count inicial. */
    fun parsePaperInfo(data: ByteArray, isNew: Boolean): Sobp {
        var o = if (isNew) 1 else 0
        val section = u8(data, o + 3)
        val owner = u8(data, o) or (u8(data, o + 1) shl 8) or (u8(data, o + 2) shl 16)
        o += 4
        val book = i32(data, o); o += 4
        val page = i32(data, o)
        return Sobp(section, owner, book, page)
    }

    /** SETTING_INFO_RESPONSE → ajustes (offsets del SDK: SettingInfo). */
    fun parseSettingInfo(data: ByteArray): PenSettings {
        // lock(1) pwdMax(1) pwdRetry(1) time(8) autoOff(2) maxForce(2) usedMem(1)
        // capOff(1) autoPowerOn(1) beep(1) hover(1) battery(1) useOffline(1) fsr(1)
        val battery = if (data.size > 20) u8(data, 20) else 0
        return PenSettings(
            battery = if (battery == 128) 100 else battery,
            charging = battery == 128,
            usedMem = data.u8OrNull(15),
            autoShutdownTime = data.u16OrNull(11),
            autoPowerOn = data.u8OrNull(17)?.let { it == 1 },
            beep = data.u8OrNull(18)?.let { it == 1 },
            offlineData = data.u8OrNull(21)?.let { it == 1 },
            sensitivity = data.u8OrNull(22)?.coerceIn(0, 4),
            maxForce = data.u16OrNull(13)?.takeIf { it > 0 },
        )
    }

    /** SETTING_INFO_RESPONSE → estado de contraseña: (locked, maxRetry, retry). */
    fun parseLockInfo(data: ByteArray): Triple<Boolean, Int, Int> =
        Triple(u8(data, 0) == 1, u8(data, 1), u8(data, 2))

    /** VERSION_RESPONSE → identidad (nombre, firmware, MAC). */
    fun parseVersionInfo(data: ByteArray): Triple<String, String, String> {
        val name = ascii(data, 0, 16)
        val firmware = ascii(data, 16, 16)
        // protocol(8)@32, deviceType(2)@40, mac(6)@42
        val mac = (42 until 48).joinToString(":") { "%02X".format(u8(data, it)) }
        return Triple(name, firmware, mac)
    }

    /** OFFLINE_NOTE_LIST_RESPONSE → lista de (section, owner, note). */
    fun parseNoteList(data: ByteArray): List<OfflineNote> {
        if (data.size < 2) return emptyList()
        val count = u16(data, 0)
        val out = ArrayList<OfflineNote>(count)
        var o = 2
        repeat(count) {
            if (o + 8 > data.size) return out
            val section = u8(data, o + 3)
            val owner = u8(data, o) or (u8(data, o + 1) shl 8) or (u8(data, o + 2) shl 16)
            val note = i32(data, o + 4)
            out.add(OfflineNote(section, owner, note))
            o += 8
        }
        return out
    }

    /** OFFLINE_DATA_RESPONSE (0xa3) → nº total de trazos del volcado (para el %). */
    fun parseOfflineTotalStrokes(data: ByteArray): Int = i32(data, 0)

    /** Cabecera + datos de un chunk offline (OFFLINE_PACKET_REQUEST, 0x24). */
    data class OfflineChunk(
        val packetId: Int,
        val isCompressed: Boolean,
        val beforeSize: Int,
        val afterSize: Int,
        val transPosition: Int, // 0 inicio, 1 medio, 2 fin
        val section: Int,
        val owner: Int,
        val note: Int,
        val strokeCount: Int,
        val data: ByteArray,
    )

    /** Parsea la cabecera del chunk offline y extrae sus bytes de datos (crudos). */
    fun parseOfflineChunk(d: ByteArray): OfflineChunk {
        var o = 0
        val packetId = u16(d, o); o += 2
        val isCompressed = u8(d, o) == 1; o += 1
        val beforeSize = u16(d, o); o += 2
        val afterSize = u16(d, o); o += 2
        val transPosition = u8(d, o); o += 1
        val owner = u8(d, o) or (u8(d, o + 1) shl 8) or (u8(d, o + 2) shl 16)
        val section = u8(d, o + 3); o += 4
        val note = i32(d, o); o += 4
        val strokeCount = u16(d, o); o += 2
        val size = if (isCompressed) afterSize else beforeSize
        val end = minOf(o + size, d.size)
        return OfflineChunk(
            packetId, isCompressed, beforeSize, afterSize, transPosition,
            section, owner, note, strokeCount, d.copyOfRange(o, end),
        )
    }

    /**
     * Parsea los strokes/dots de los datos offline (ya descomprimidos). section,
     * owner y note vienen del chunk; cada stroke trae su `page`, así que se
     * etiqueta cada trazo con su SOBP de página. Layout fiel a
     * `ParseSDK2OfflinePenData` del SDK.
     */
    fun parseOfflineStrokes(
        d: ByteArray,
        section: Int,
        owner: Int,
        note: Int,
        strokeCount: Int,
    ): List<Pair<Sobp, Stroke>> {
        val out = ArrayList<Pair<Sobp, Stroke>>(strokeCount)
        var o = 0
        repeat(strokeCount) {
            if (o + 27 > d.size) return out // cabecera de stroke = 27 bytes
            val page = i32(d, o); o += 4
            var time = u64(d, o); o += 8 // downTime
            o += 8 // upTime (no usado)
            o += 1 // penTipType (no usado)
            o += 4 // penTipColor (no usado)
            val dotCount = u16(d, o); o += 2
            val start = time
            val sobp = Sobp(section, owner, note, page)
            val dots = ArrayList<Dot>(dotCount)
            for (j in 0 until dotCount) {
                if (o + 16 > d.size) return out // cada dot = 16 bytes
                val nTimeDelta = u8(d, o); o += 1
                val force = u16(d, o); o += 2
                val xi = u16(d, o); o += 2
                val yi = u16(d, o); o += 2
                val fx = u8(d, o); o += 1
                val fy = u8(d, o); o += 1
                o += 1 // xtilt
                o += 1 // ytilt
                o += 2 // twist
                o += 2 // reservado
                o += 1 // checksum
                time += nTimeDelta
                val type = when {
                    j == 0 -> DOT_DOWN
                    j == dotCount - 1 -> DOT_UP
                    else -> DOT_MOVE
                }
                dots.add(Dot(xi + fx * 0.01f, yi + fy * 0.01f, force.toFloat(), time, type))
            }
            out.add(sobp to Stroke(start, time, dots))
        }
        return out
    }

    // --- helpers de lectura LE / escritura ---

    private fun le(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun u8(d: ByteArray, i: Int) = if (i < d.size) d[i].toInt() and 0xff else 0
    private fun u16(d: ByteArray, i: Int) = u8(d, i) or (u8(d, i + 1) shl 8)
    private fun ByteArray.u8OrNull(i: Int): Int? = if (i < size) u8(this, i) else null
    private fun ByteArray.u16OrNull(i: Int): Int? = if (i + 1 < size) u16(this, i) else null
    private fun i32(d: ByteArray, i: Int) =
        u8(d, i) or (u8(d, i + 1) shl 8) or (u8(d, i + 2) shl 16) or (u8(d, i + 3) shl 24)
    private fun u64(d: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = v or ((d.getOrElse(i + k) { 0 }.toLong() and 0xff) shl (8 * k))
        return v
    }

    private fun ascii(d: ByteArray, off: Int, len: Int): String {
        if (off >= d.size) return ""
        return String(d, off, minOf(len, d.size - off), Charsets.US_ASCII)
            .takeWhile { it.code != 0 }.trim()
    }

    private fun putAscii(d: ByteArray, off: Int, s: String, max: Int) {
        val b = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(b, 0, d, off, minOf(b.size, max))
    }
}
