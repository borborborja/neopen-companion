package net.micapum.neopen.core.pen

/** Identidad Ncode de la página física (Section.Owner.Book.Page). */
data class Sobp(val section: Int, val owner: Int, val book: Int, val page: Int) {
    val bookKey get() = "$section.$owner.$book"
}

/** Un punto: coordenada en celdas Ncode, presión, timestamp absoluto (ms) y tipo. */
data class Dot(val x: Float, val y: Float, val f: Float, val t: Long, val type: Int)

/** Trazo continuo: PEN_DOWN, N×PEN_MOVE, PEN_UP. */
data class Stroke(val startTime: Long, val endTime: Long, val dots: List<Dot>)

/** Página de escritura agrupada por SOBP, lista para subir al servidor neopen. */
data class StrokePage(
    val sobp: Sobp,
    val source: String,           // "realtime" | "offline"
    val capturedAt: String,       // ISO-8601
    val strokes: List<Stroke>,
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
