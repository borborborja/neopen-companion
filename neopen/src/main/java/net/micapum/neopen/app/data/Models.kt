package net.micapum.neopen.app.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import net.micapum.neopen.core.data.CapturePatchDto
import net.micapum.neopen.core.data.TranscriptionPushDto
import net.micapum.neopen.core.data.CreateCaptureInput
import net.micapum.neopen.core.data.NotebookCreateDto
import net.micapum.neopen.core.data.NotebookPatchDto
import net.micapum.neopen.core.data.OcrResultDto
import net.micapum.neopen.core.data.OverlapDto
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.ReorderDto
import net.micapum.neopen.core.data.StrokeDto

/** Captura (hoja) en el modelo de dominio de la app, con los campos ya parseados. */
data class Capture(
    val id: String,
    val rev: Long,
    val section: Int,
    val owner: Int,
    val book: Int,
    val page: Int,
    val source: String,
    val capturedAt: String,
    val importedAt: String,
    val updatedAt: String,
    val name: String,
    val tags: List<String>,
    val strokes: List<StrokeDto>,
    val transcription: OcrResultDto?,
    val destination: String,
    val notebookId: String?,
    val position: Int,
    val paper: PaperDto?,
    val kind: String?,
    val sealed: Boolean = false,
    val overlap: OverlapDto? = null,
    /** Id de la hoja origen si es un clon; no compite por el enrutado del boli. */
    val clonedFrom: String? = null,
) {
    val sobpLabel get() = "$section.$owner.$book.$page"
}

/** Aviso de trazos reimportados desde la memoria del boli que requieren decisión. */
data class OfflineImportWarning(
    val id: String,
    val section: Int,
    val owner: Int,
    val book: Int,
    val page: Int,
    val capturedAt: String,
    val importedAt: String,
    val name: String,
    val strokes: List<StrokeDto>,
    val paper: PaperDto?,
    val reasonAction: String,
    val actionCaptureName: String?,
    val actionDestination: String?,
    val actionNotebookName: String?,
    val targetCaptureId: String?,
) {
    val sobpLabel get() = "$section.$owner.$book.$page"
    val canMerge get() = targetCaptureId != null
}

/** Cuaderno en el modelo de dominio (con el contador de hojas resuelto). */
data class Notebook(
    val id: String,
    val rev: Long,
    val name: String,
    val color: String,
    val tags: List<String>,
    val createdAt: String,
    val updatedAt: String,
    val paper: PaperDto?,
    val paperSource: String?,
    val bookKey: String?,
    val coverUrl: String?,
    val unclassified: Boolean,
    val custom: Boolean,
    val category: String? = null,
    val pageCount: Int = 0,
)

data class PhotoScanTarget(
    val capture: Capture?,
    val notebook: Notebook?,
    val paper: PaperDto?,
)

/** Fondo (plantilla) resuelto para un cuaderno o una hoja. */
data class PageBackground(
    val id: String,
    val bookKey: String,
    val page: Int?,
    val scope: String,
    val bgPreset: String?,
    val bgImage: String?,
    val label: String,
    val source: String,
    val remote: Boolean,
)

/** Adaptadores Moshi compartidos para (de)serializar los campos JSON de Room. */
object Json {
    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val stringListType = Types.newParameterizedType(List::class.java, String::class.java)
    private val strokeListType = Types.newParameterizedType(List::class.java, StrokeDto::class.java)

    val stringList = moshi.adapter<List<String>>(stringListType)
    val strokeList = moshi.adapter<List<StrokeDto>>(strokeListType)
    val paper = moshi.adapter(PaperDto::class.java)
    val ocr = moshi.adapter(OcrResultDto::class.java)
    val overlap = moshi.adapter(OverlapDto::class.java)

    // Adaptadores de los payloads del outbox (Fase 3/4).
    val capturePatch = moshi.adapter(CapturePatchDto::class.java)
    val notebookCreate = moshi.adapter(NotebookCreateDto::class.java)
    val notebookPatch = moshi.adapter(NotebookPatchDto::class.java)
    val reorder = moshi.adapter(ReorderDto::class.java)
    val createCapture = moshi.adapter(CreateCaptureInput::class.java)
    val transcriptionPush = moshi.adapter(TranscriptionPushDto::class.java)

    fun tags(json: String?): List<String> =
        json?.let { runCatching { stringList.fromJson(it) }.getOrNull() }.orEmpty()

    fun strokes(json: String?): List<StrokeDto> =
        json?.let { runCatching { strokeList.fromJson(it) }.getOrNull() }.orEmpty()

    fun paperOf(json: String?): PaperDto? =
        json?.let { runCatching { paper.fromJson(it) }.getOrNull() }

    fun ocrOf(json: String?): OcrResultDto? =
        json?.let { runCatching { ocr.fromJson(it) }.getOrNull() }

    fun overlapOf(json: String?): OverlapDto? =
        json?.let { runCatching { overlap.fromJson(it) }.getOrNull() }

    fun overlapJson(o: OverlapDto?): String? = o?.let { overlap.toJson(it) }

    fun tagsJson(tags: List<String>): String = stringList.toJson(tags)
    fun strokesJson(strokes: List<StrokeDto>): String = strokeList.toJson(strokes)
    fun paperJson(p: PaperDto?): String? = p?.let { paper.toJson(it) }
    fun ocrJson(o: OcrResultDto?): String? = o?.let { ocr.toJson(it) }
}
