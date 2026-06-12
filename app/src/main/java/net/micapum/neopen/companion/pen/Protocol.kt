package net.micapum.neopen.companion.pen

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Protocolo v2 del Neo smartpen M1+ (NWP-F51).
 *
 * Constantes y framing reconstruidos del SDK oficial (web_pen_sdk, GPL-3.0), que es
 * la implementación que ya funciona en la web de neopen:
 *  - Servicio BLE 0x19F1, write 0x2BA0, notify 0x2BA1.
 *  - Frame: STX, CMD, LEN(2 LE), DATA, CHECKSUM(suma de DATA & 0xFF), ETX.
 *  - Escape DLE: 0x02/0x03/0x07 → [0x07, b XOR 0x20].
 *  - Enum de comandos: dist/PenCotroller/CMD.js.
 *
 * ⚠️ Los PAYLOADS exactos de algunos comandos (VERSION_REQUEST, el layout interno
 * de los paquetes offline) deben verificarse contra PenRequestV2.js /
 * PenClientParserV2.js con el boli real. Marcado con "VERIFY".
 */
object Protocol {
    // --- UUIDs (servicio v2 19F1 del M1+; v5 para pens nuevos, solo fallback de scan) ---
    val SERVICE_V2: UUID = uuid16(0x19F1)
    val CHAR_WRITE: UUID = uuid16(0x2BA0)
    val CHAR_NOTIFY: UUID = uuid16(0x2BA1)
    val SERVICE_V5: UUID = UUID.fromString("4f99f138-9d53-5bfa-9e50-b147491afe68")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val STX = 0x02
    const val ETX = 0x03
    const val DLE = 0x07

    // --- Comandos host → pen (CMD.js) ---
    const val VERSION_REQUEST = 0x01
    const val PASSWORD_RESPONSE = 0x82
    const val SETTING_INFO_REQUEST = 0x04
    const val SETTING_CHANGE_REQUEST = 0x05
    const val ONLINE_DATA_REQUEST = 0x11
    const val OFFLINE_NOTE_LIST_REQUEST = 0x21
    const val OFFLINE_PAGE_LIST_REQUEST = 0x22
    const val OFFLINE_DATA_REQUEST = 0x23
    const val OFFLINE_PACKET_RESPONSE = 0xa4
    const val OFFLINE_DATA_DELETE_REQUEST = 0x25

    // --- Eventos pen → host (CMD.js) ---
    const val VERSION_RESPONSE = 0x81
    const val PASSWORD_REQUEST = 0x02
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

    /** Construye un frame v2 (con escape DLE). */
    fun buildFrame(cmd: Int, data: ByteArray): ByteArray {
        val checksum = data.fold(0) { acc, b -> acc + (b.toInt() and 0xff) } and 0xff
        val out = ArrayList<Byte>(data.size + 8)
        out.add(STX.toByte())
        putEscaped(out, cmd)
        putEscaped(out, data.size and 0xff)
        putEscaped(out, (data.size shr 8) and 0xff)
        for (b in data) putEscaped(out, b.toInt() and 0xff)
        putEscaped(out, checksum)
        out.add(ETX.toByte())
        return out.toByteArray()
    }

    private fun putEscaped(out: ArrayList<Byte>, b: Int) {
        val v = b and 0xff
        if (v == STX || v == ETX || v == DLE) {
            out.add(DLE.toByte()); out.add((v xor 0x20).toByte())
        } else out.add(v.toByte())
    }

    /** Un frame ya des-escapado y troceado. */
    data class Frame(val cmd: Int, val data: ByteArray)

    /**
     * Reensambla frames a partir del stream de notificaciones BLE. Mantén una
     * instancia por conexión: las notificaciones pueden venir partidas o agrupadas.
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
            // buf = CMD, LEN(2 LE), DATA, CHECKSUM
            if (buf.size < 4) return
            val cmd = buf[0].toInt() and 0xff
            val len = (buf[1].toInt() and 0xff) or ((buf[2].toInt() and 0xff) shl 8)
            val end = 3 + len
            if (end > buf.size) return // frame incompleto/corrupto
            val data = ByteArray(len) { buf[3 + it] }
            onFrame(Frame(cmd, data))
        }
    }

    // ----------------------------------------------------------------------------------
    // Builders de comandos (host → pen)
    // ----------------------------------------------------------------------------------

    /** Pide versión/identidad. VERIFY: el SDK incluye nombre de app/dispositivo en el payload. */
    fun reqVersion(): ByteArray = buildFrame(VERSION_REQUEST, ByteArray(0))

    /** Responde a la petición de contraseña (M1+ por defecto NO tiene). */
    fun resPassword(password: String): ByteArray {
        val pw = password.toByteArray(Charsets.US_ASCII)
        val data = ByteArray(16)
        System.arraycopy(pw, 0, data, 0, minOf(pw.size, 16))
        return buildFrame(PASSWORD_RESPONSE, data)
    }

    fun reqSettingInfo(): ByteArray = buildFrame(SETTING_INFO_REQUEST, ByteArray(0))

    /** Cambia un ajuste. type: ver SETTING_TYPE; value LE. VERIFY layout. */
    fun reqSettingChange(type: Int, value: ByteArray): ByteArray =
        buildFrame(SETTING_CHANGE_REQUEST, byteArrayOf(type.toByte()) + value)

    /** Activa la recepción de escritura en tiempo real de todas las libretas. */
    fun reqOnlineDataAll(): ByteArray {
        // VERIFY: el SDK envía la lista de notebooks permitidos; aquí "todos".
        return buildFrame(ONLINE_DATA_REQUEST, byteArrayOf(0x00))
    }

    fun reqOfflineNoteList(section: Int = 0, owner: Int = 0): ByteArray {
        val bb = le(8).putInt(section).putInt(owner)
        return buildFrame(OFFLINE_NOTE_LIST_REQUEST, bb.array())
    }

    fun reqOfflinePageList(section: Int, owner: Int, note: Int): ByteArray {
        val bb = le(12).putInt(section).putInt(owner).putInt(note)
        return buildFrame(OFFLINE_PAGE_LIST_REQUEST, bb.array())
    }

    /** Descarga datos offline de una libreta. deleteOnFinished SIEMPRE false en el companion. */
    fun reqOfflineData(section: Int, owner: Int, note: Int): ByteArray {
        // VERIFY layout: section,owner,note (LE) + flags (compress/delete) + pageCount(0=todas).
        val bb = le(16)
            .putInt(section).putInt(owner).putInt(note)
            .put(1).put(0) // [compress=1][deleteOnFinished=0]
            .putShort(0)   // pageCount 0 = todas
        return buildFrame(OFFLINE_DATA_REQUEST, bb.array())
    }

    /** ACK de un chunk offline (APP→PEN). VERIFY: índice del paquete. */
    fun resOfflinePacket(index: Int): ByteArray =
        buildFrame(OFFLINE_PACKET_RESPONSE, le(4).putInt(index).array())

    fun reqOfflineDelete(section: Int, owner: Int, notes: IntArray): ByteArray {
        val bb = le(9 + notes.size * 4)
            .putInt(section).putInt(owner).put(notes.size.toByte())
        notes.forEach { bb.putInt(it) }
        return buildFrame(OFFLINE_DATA_DELETE_REQUEST, bb.array())
    }

    // ----------------------------------------------------------------------------------
    // Parsers (pen → host)
    // ----------------------------------------------------------------------------------

    /** Parsea un dot de 17 bytes (online) dado el SOBP actual y el t base del stroke. */
    fun parseDot(data: ByteArray, offset: Int, baseTime: Long): Pair<Dot, Int> {
        val bb = ByteBuffer.wrap(data, offset, 17).order(ByteOrder.LITTLE_ENDIAN)
        val timeDelta = bb.short.toInt() and 0xffff
        val force = bb.float
        val x = bb.float
        val y = bb.float
        bb.get(); bb.get(); bb.get() // tilt x, tilt y, rotation (no usados)
        val t = baseTime + timeDelta
        // El tipo lo marca el flujo DOWN/MOVE/UP del parser, no el propio dot.
        return Dot(x, y, force, t, DOT_MOVE) to timeDelta
    }

    /** SETTING_INFO_RESPONSE → ajustes. VERIFY offsets exactos contra PenClientParserV2. */
    fun parseSettingInfo(data: ByteArray): PenSettings {
        // Layout aproximado: el primer byte suele ser batería; memoria usada un byte;
        // resto de flags. Se ajusta con el boli real.
        fun byteAt(i: Int) = if (i < data.size) data[i].toInt() and 0xff else 0
        val battery = byteAt(0)
        return PenSettings(
            battery = if (battery == 128) 100 else battery,
            charging = battery == 128,
            usedMem = byteAt(1),
        )
    }

    /** OFFLINE_NOTE_LIST_RESPONSE → lista de (section, owner, note). */
    fun parseNoteList(data: ByteArray): List<OfflineNote> {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.remaining() < 2) return emptyList()
        val count = bb.short.toInt() and 0xffff
        val out = ArrayList<OfflineNote>(count)
        repeat(count) {
            if (bb.remaining() < 8) return out
            val rb = ByteArray(4); bb.get(rb)
            val section = rb[3].toInt() and 0xff
            val owner = (rb[0].toInt() and 0xff) or
                ((rb[1].toInt() and 0xff) shl 8) or ((rb[2].toInt() and 0xff) shl 16)
            val note = bb.int
            out.add(OfflineNote(section, owner, note))
        }
        return out
    }

    private fun le(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
