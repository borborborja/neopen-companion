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
)

/** Estilo de trazo (color ARGB + grueso) configurable por boli. Defaults = render actual. */
data class StrokeStyle(val color: Int = 0xFF111111.toInt(), val width: Float = 1.6f)

/** Boli emparejado: identidad (MAC), alias y su estilo de trazo actual. */
data class KnownPen(val mac: String, val alias: String, val style: StrokeStyle = StrokeStyle())

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

enum class PenStatus { IDLE, SCANNING, CONNECTING, AUTHORIZED, DISCONNECTED }
enum class OfflinePhase { IDLE, LISTING, TRANSFERRING }

/** Ajustes reportados por el boli (SETTING_INFO). Sin color ni sensibilidad (no los usamos). */
data class PenSettings(
    val battery: Int? = null,
    val charging: Boolean = false,
    val usedMem: Int? = null,
    val autoPowerOn: Boolean? = null,
    val beep: Boolean? = null,
    val offlineData: Boolean? = null,
    val autoShutdownTime: Int? = null,
)

/** Estado completo expuesto a la UI. */
data class PenState(
    val status: PenStatus = PenStatus.IDLE,
    val name: String? = null,
    val mac: String? = null,
    val firmware: String? = null,
    val settings: PenSettings = PenSettings(),
    val offlinePhase: OfflinePhase = OfflinePhase.IDLE,
    val offlineProgress: Int = 0,
    val notes: List<OfflineNote>? = null,
    val capturedPages: Int = 0,
    val error: String? = null,
) {
    val connected get() = status == PenStatus.AUTHORIZED
}
