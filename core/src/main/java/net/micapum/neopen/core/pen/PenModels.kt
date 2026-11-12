package net.micapum.neopen.core.pen

/** Identidad Ncode de la página física (Section.Owner.Book.Page). */
data class Sobp(val section: Int, val owner: Int, val book: Int, val page: Int) {
    val bookKey get() = "$section.$owner.$book"
}

/** Un punto: coordenada en celdas Ncode, presión, timestamp absoluto (ms) y tipo. */
data class Dot(val x: Float, val y: Float, val f: Float, val t: Long, val type: Int)

/**
 * Trazo continuo: PEN_DOWN, N×PEN_MOVE, PEN_UP.
 *
 * `color` (ARGB) y `width` son el estilo del boli "congelado" al capturar el trazo (ver
 * [StrokeStyle]); null → defaults de render (#111 / 1.6). Cambiar el estilo del boli no
 * re-colorea trazos ya capturados.
 */
data class Stroke(
    val startTime: Long,
    val endTime: Long,
    val dots: List<Dot>,
    val color: Int? = null,
    val width: Float? = null,
    val penId: String? = null,
)

/** Estilo de trazo (color ARGB + grueso) configurable por boli. Defaults = render actual. */
data class StrokeStyle(val color: Int = 0xFF111111.toInt(), val width: Float = 1.6f)

/**
 * Boli emparejado: identidad (MAC), alias, estilo de trazo y, opcionalmente, el PIN
 * recordado para desbloquearlo automáticamente (se guarda CIFRADO en reposo).
 */
data class KnownPen(
    val mac: String,
    val alias: String,
    val style: StrokeStyle = StrokeStyle(),
    val pin: String = "",
)

/**
 * Presets de estilo compartidos por la UI (paleta) y la notificación de control (ciclado), para
 * que ambos ofrezcan exactamente las mismas opciones.
 */
object StrokePresets {
    /** Paleta: (color ARGB, nombre). El primero (#111) es el por defecto. */
    val COLORS: List<Pair<Int, String>> = listOf(
        0xFF111111.toInt() to "Negro",
        0xFF2563EB.toInt() to "Azul",
        0xFFDC2626.toInt() to "Rojo",
        0xFF16A34A.toInt() to "Verde",
        0xFFEA580C.toInt() to "Naranja",
        0xFF7C3AED.toInt() to "Morado",
    )
    /** Gruesos: (width, etiqueta). */
    val WIDTHS: List<Pair<Float, String>> = listOf(
        1.0f to "Fino",
        1.6f to "Medio",
        2.6f to "Grueso",
    )

    fun colorName(argb: Int): String = COLORS.firstOrNull { it.first == argb }?.second ?: "Personalizado"
    fun widthLabel(w: Float): String = WIDTHS.firstOrNull { it.first == w }?.second ?: "$w"

    /** Siguiente color de la paleta (cicla). */
    fun nextColor(argb: Int): Int {
        val i = COLORS.indexOfFirst { it.first == argb }
        return COLORS[(if (i < 0) 0 else i + 1) % COLORS.size].first
    }

    /** Siguiente grueso (cicla). */
    fun nextWidth(w: Float): Float {
        val i = WIDTHS.indexOfFirst { it.first == w }
        return WIDTHS[(if (i < 0) 0 else i + 1) % WIDTHS.size].first
    }
}

/** Página de escritura agrupada por SOBP, lista para subir al servidor neopen. */
data class StrokePage(
    val sobp: Sobp,
    val source: String,           // "realtime" | "offline"
    val capturedAt: String,       // ISO-8601
    val strokes: List<Stroke>,
    val penId: String? = null,    // MAC del boli que capturó la página (diagnóstico/dedup)
)

/** Una libreta guardada en la memoria del boli (offline). */
data class OfflineNote(val section: Int, val owner: Int, val note: Int, val pages: Int? = null)

enum class PenStatus { IDLE, SCANNING, CONNECTING, LOCKED, AUTHORIZED, DISCONNECTED }
enum class OfflinePhase { IDLE, LISTING, TRANSFERRING }

/** Ajustes del boli cuyo cambio se confirma mediante SETTING_CHANGE_RESPONSE. */
enum class PenSettingKey(val protocolType: Int) {
    AUTO_POWER_ON(Protocol.SET_AUTO_POWER_ON),
    BEEP(Protocol.SET_BEEP),
    OFFLINE_DATA(Protocol.SET_OFFLINE_DATA),
    AUTO_SHUTDOWN(Protocol.SET_AUTO_POWER_OFF_TIME),
    SENSITIVITY(Protocol.SET_SENSITIVITY),
}

/** Motivo por el que un cambio de ajuste no llegó a confirmarse. */
enum class PenSettingFailure { REJECTED, TIMEOUT, PROTOCOL }

/** Último cambio de ajuste que falló; la UI lo presenta junto a los controles. */
data class PenSettingIssue(val setting: PenSettingKey, val failure: PenSettingFailure)

/** Ajustes reportados por el boli (SETTING_INFO). */
data class PenSettings(
    val battery: Int? = null,
    val charging: Boolean = false,
    val usedMem: Int? = null,
    val autoPowerOn: Boolean? = null,
    val beep: Boolean? = null,
    val offlineData: Boolean? = null,
    val autoShutdownTime: Int? = null,
    /** Sensibilidad de presión 0..4 (0 = más sensible). Null si el boli no la reporta. */
    val sensitivity: Int? = null,
    /** Fuerza máxima reportada por el sensor FSR. Null si no viene en SETTING_INFO. */
    val maxForce: Int? = null,
) {
    val pressureSupported: Boolean get() = sensitivity != null || (maxForce ?: 0) > 0
}

/** Estado completo expuesto a la UI. */
data class PenState(
    val status: PenStatus = PenStatus.IDLE,
    val name: String? = null,
    val mac: String? = null,
    val firmware: String? = null,
    val settings: PenSettings = PenSettings(),
    /** Ajustes solicitados al boli cuya confirmación aún no ha llegado. */
    val pendingSettings: Set<PenSettingKey> = emptySet(),
    val settingIssue: PenSettingIssue? = null,
    val offlinePhase: OfflinePhase = OfflinePhase.IDLE,
    val offlineProgress: Int = 0,
    val notes: List<OfflineNote>? = null,
    val capturedPages: Int = 0,
    val error: String? = null,
    /** El boli tiene contraseña configurada (del flag Locked de SETTING_INFO). */
    val hasPassword: Boolean = false,
    /** Intentos de PIN usados y máximo (al llegar al máximo el boli se restablece). */
    val passwordRetry: Int = 0,
    val passwordMaxRetry: Int = 10,
    /** La app está buscando/reintentando sin acción manual del usuario. */
    val autoReconnecting: Boolean = false,
) {
    val connected get() = status == PenStatus.AUTHORIZED
    /** Conectado pero esperando el PIN para poder usar el boli. */
    val locked get() = status == PenStatus.LOCKED
}
