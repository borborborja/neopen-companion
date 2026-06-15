package net.micapum.neopen.core.data

import android.os.Build
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit

// --- Modelos del contrato (docs/openapi.yaml del repo neopen) ---

data class SobpDto(val section: Int, val owner: Int, val book: Int, val page: Int)

// `t` es opcional en el servidor (las capturas de la web pueden no traerlo).
data class DotDto(val x: Float, val y: Float, val f: Float, val t: Long? = null)

data class StrokeDto(val startTime: Long, val endTime: Long, val dots: List<DotDto>)

data class CreateCaptureInput(
    val sobp: SobpDto,
    val source: String,
    val capturedAt: String,
    val name: String,
    val tags: List<String> = emptyList(),
    val strokes: List<StrokeDto>,
    // Formato detectado (NeoLAB) para sembrar el del cuaderno nuevo en el servidor.
    val paper: PaperDto? = null,
    // Nombre legible de la libreta física (para nombrar el cuaderno auto-creado).
    val notebook: String? = null,
    // Archivado explícito (lo usa la migración local→servidor). Normalmente null:
    // el servidor archiva por SOBP.
    val notebookId: String? = null,
    val destination: String? = null,
)

data class RegisterPenInput(val mac: String, val name: String? = null, val firmware: String? = null)

data class PenInfoDto(val mac: String, val alias: String? = null)

data class HealthDto(val status: String? = null)

// --- Lectura / sincronización incremental ---

data class PaperDto(
    val x0: Float,
    val y0: Float,
    val width: Float,
    val height: Float,
    val source: String? = null,
)

data class OcrTodoDto(val text: String, val done: Boolean? = null)

data class OcrDateDto(val date: String? = null, val text: String? = null)

data class OcrResultDto(
    val title: String? = null,
    val text: String? = null,
    val language: String? = null,
    val tags: List<String> = emptyList(),
    val todos: List<OcrTodoDto> = emptyList(),
    val dates: List<OcrDateDto> = emptyList(),
)

/** Captura completa devuelta por el servidor (incluye rev/updatedAt del delta-sync). */
data class CaptureDto(
    val id: String,
    val rev: Long = 0,
    val sobp: SobpDto,
    val notebook: String? = null,
    val source: String,
    val capturedAt: String,
    val importedAt: String,
    val updatedAt: String? = null,
    val name: String,
    val tags: List<String> = emptyList(),
    val strokes: List<StrokeDto> = emptyList(),
    val transcription: OcrResultDto? = null,
    val destination: String = "inbox",
    val notebookId: String? = null,
    val position: Int = 0,
    val paper: PaperDto? = null,
    val kind: String? = null,
    val sealed: Boolean = false,
    val overlap: OverlapDto? = null,
)

/** Aviso de trazos superpuestos detectado en la última fusión (espejo de OverlapInfo). */
data class OverlapDto(
    val strokeIndex: Int = 0,
    val atTime: Long = 0,
    val overlappingStrokes: Int = 0,
    val areaPercent: Int = 0,
)

data class NotebookDto(
    val id: String,
    val rev: Long = 0,
    val name: String,
    val color: String,
    val tags: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val paper: PaperDto? = null,
    val paperSource: String? = null,
    val bookKey: String? = null,
    val coverUrl: String? = null,
    val unclassified: Boolean = false,
    val custom: Boolean = false,
    val category: String? = null,
)

data class DeletionDto(val id: String, val kind: String, val rev: Long)

/** Respuesta de GET /api/changes?since=<cursor>. */
data class ChangesResponseDto(
    val cursor: Long,
    val hasMore: Boolean = false,
    val reset: Boolean = false,
    val captures: List<CaptureDto> = emptyList(),
    val notebooks: List<NotebookDto> = emptyList(),
    val deletions: List<DeletionDto> = emptyList(),
)

data class CapturesResponseDto(val captures: List<CaptureDto> = emptyList())

data class NotebooksResponseDto(val notebooks: List<NotebookDto> = emptyList())

// --- Edición (outbox → REST) ---

data class CapturePatchDto(
    val name: String? = null,
    val tags: List<String>? = null,
    val destination: String? = null,
    val notebookId: String? = null,
    val position: Int? = null,
    val kind: String? = null,
    val paper: PaperDto? = null,
    val sealed: Boolean? = null,
)

/** Cuerpo de PUT /api/notebooks/{id}/paper (formato del cuaderno; re-sella sus hojas). */
data class NotebookPaperBody(val paper: PaperDto, val source: String? = null)

/** Modo al finalizar una hoja (GET/PUT /api/finish-mode). */
data class FinishModeDto(val mode: String = "newSheet")
data class FinishModeBody(val mode: String)

/** Umbral de detección de trazos superpuestos (GET/PATCH /api/overlap). */
data class OverlapConfigDto(
    val enabled: Boolean = true,
    val minStrokes: Int = 3,
    val minAreaPercent: Int = 25,
)

/** Cuerpo de POST /api/captures/{id}/split (índice de corte + modo). */
data class SplitBody(val cutIndex: Int, val mode: String)

/** Respuesta del split: la hoja original (sellada) + la hoja nueva. */
data class SplitResultDto(val original: CaptureDto, val created: CaptureDto)

data class NotebookCreateDto(
    val name: String,
    val color: String,
    val tags: List<String> = emptyList(),
    val category: String? = null,
    // Para la migración local→servidor: el servidor deduplica físicos por bookKey.
    val bookKey: String? = null,
    val paper: PaperDto? = null,
    val custom: Boolean? = null,
    val unclassified: Boolean? = null,
)

data class NotebookPatchDto(
    val name: String? = null,
    val color: String? = null,
    val tags: List<String>? = null,
    val category: String? = null,
)

data class ReorderDto(val order: List<String>)

// Historial de SOBP escaneados (herramienta "Leer SOBP").
data class SobpScanDto(
    val id: String,
    val label: String,
    val sobp: SobpDto,
    val pen: String? = null,
    val scannedAt: String,
)
data class CreateSobpScanInput(val label: String, val sobp: SobpDto, val pen: String? = null)

// --- Fondos / anclajes de diseño (plantilla detrás de los trazos) ---

/** Capacidades del servidor (solo leemos si Studio está activo). */
data class StudioCapDto(val enabled: Boolean = false)
data class CapabilitiesDto(val studio: StudioCapDto? = null)

/**
 * Anclaje de diseño del servidor (DesignAnchor). Solo usamos imagen/preset/ámbito;
 * `studio` (config vectorial de la web) se ignora en Android. Moshi descarta los
 * campos no declarados, así que no hace falta modelar `studio` ni `pageBySobp` salvo
 * para deducir la página del ámbito "page".
 */
data class AnchorDto(
    val id: String,
    val label: String = "",
    val bookKey: String,
    val scope: String? = null,
    val bgImage: String? = null,
    val bgPreset: String? = null,
    val pageBySobp: Map<String, Int>? = null,
    val source: String = "custom",
    val createdAt: String,
)

data class BackgroundUploadInput(val dataUrl: String)
data class BackgroundUploadDto(val url: String)

/** Generación reciente / preset guardado en Studio (solo necesitamos la imagen). */
data class StudioItemDto(
    val id: String,
    val label: String = "",
    val bgImage: String? = null,
    val createdAt: String? = null,
)

/** Cliente del servidor neopen. La contraseña va en la cabecera por petición. */
interface NeopenApi {
    /** Healthcheck público (sin auth): comprueba que el servidor responde. */
    @GET("api/health")
    suspend fun health(): HealthDto

    /** Endpoint con auth: sirve para validar la contraseña (200 ok / 401 incorrecta). */
    @GET("api/capabilities")
    suspend fun capabilities(@Header("X-Auth-Password") password: String): Response<Unit>

    /** Capacidades tipadas (p. ej. si Studio está activo → recientes/presets). */
    @GET("api/capabilities")
    suspend fun capabilitiesInfo(@Header("X-Auth-Password") password: String): CapabilitiesDto

    // --- Fondos / anclajes de diseño ---

    @GET("api/anchors")
    suspend fun listAnchors(@Header("X-Auth-Password") password: String): List<AnchorDto>

    @PUT("api/anchors/{id}")
    suspend fun putAnchor(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
        @Body body: AnchorDto,
    ): Response<Unit>

    @DELETE("api/anchors/{id}")
    suspend fun deleteAnchor(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
    ): Response<Unit>

    @POST("api/backgrounds")
    suspend fun uploadBackground(
        @Header("X-Auth-Password") password: String,
        @Body body: BackgroundUploadInput,
    ): BackgroundUploadDto

    @GET("api/studio/recents")
    suspend fun listRecents(@Header("X-Auth-Password") password: String): List<StudioItemDto>

    @GET("api/studio/presets")
    suspend fun listPresets(@Header("X-Auth-Password") password: String): List<StudioItemDto>

    // --- Lectura / sincronización ---

    @GET("api/captures")
    suspend fun listCaptures(@Header("X-Auth-Password") password: String): CapturesResponseDto

    @GET("api/notebooks")
    suspend fun listNotebooks(@Header("X-Auth-Password") password: String): NotebooksResponseDto

    /** Cuaderno reservado "Sin clasificar" (se crea en el servidor si no existe). */
    @GET("api/notebooks/unclassified")
    suspend fun unclassifiedNotebook(@Header("X-Auth-Password") password: String): NotebookDto

    @GET("api/changes")
    suspend fun changes(
        @Header("X-Auth-Password") password: String,
        @Query("since") since: Long,
        @Query("limit") limit: Int,
    ): ChangesResponseDto

    // --- Escritura ---

    @POST("api/captures")
    suspend fun createCapture(
        @Header("X-Auth-Password") password: String,
        @Body body: CreateCaptureInput,
    ): CaptureDto

    @PATCH("api/captures/{id}")
    suspend fun patchCapture(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
        @Body body: CapturePatchDto,
    ): CaptureDto

    @DELETE("api/captures/{id}")
    suspend fun deleteCapture(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
    ): Response<Unit>

    @POST("api/notebooks")
    suspend fun createNotebook(
        @Header("X-Auth-Password") password: String,
        @Body body: NotebookCreateDto,
    ): NotebookDto

    @PATCH("api/notebooks/{id}")
    suspend fun patchNotebook(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
        @Body body: NotebookPatchDto,
    ): NotebookDto

    @DELETE("api/notebooks/{id}")
    suspend fun deleteNotebook(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
    ): Response<Unit>

    @PUT("api/notebooks/{id}/order")
    suspend fun reorderNotebook(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
        @Body body: ReorderDto,
    ): Response<Unit>

    /** Fija el formato del cuaderno y re-sella todas sus hojas (proporciones). */
    @PUT("api/notebooks/{id}/paper")
    suspend fun setNotebookPaper(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
        @Body body: NotebookPaperBody,
    ): NotebookDto

    /** Finaliza el cuaderno (modo "nuevo cuaderno"): sella sus hojas y crea una copia. */
    @POST("api/notebooks/{id}/finalize")
    suspend fun finalizeNotebook(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
    ): NotebookDto

    @GET("api/finish-mode")
    suspend fun getFinishMode(@Header("X-Auth-Password") password: String): FinishModeDto

    @PUT("api/finish-mode")
    suspend fun setFinishMode(
        @Header("X-Auth-Password") password: String,
        @Body body: FinishModeBody,
    ): FinishModeDto

    @GET("api/overlap")
    suspend fun getOverlapConfig(@Header("X-Auth-Password") password: String): OverlapConfigDto

    @PATCH("api/overlap")
    suspend fun setOverlapConfig(
        @Header("X-Auth-Password") password: String,
        @Body body: OverlapConfigDto,
    ): OverlapConfigDto

    /** Corta una hoja con trazos superpuestos: sella la original y crea una nueva. */
    @POST("api/captures/{id}/split")
    suspend fun splitCapture(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
        @Body body: SplitBody,
    ): SplitResultDto

    /** Descarta el aviso de superposición sin cortar. */
    @POST("api/captures/{id}/dismiss-overlap")
    suspend fun dismissOverlap(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
    ): CaptureDto

    @Multipart
    @POST("api/captures/{id}/transcribe")
    suspend fun transcribe(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
        @Part image: MultipartBody.Part,
        @Part("instructions") instructions: RequestBody?,
    ): CaptureDto

    @POST("api/pens")
    suspend fun registerPen(
        @Header("X-Auth-Password") password: String,
        @Body body: RegisterPenInput,
    ): PenInfoDto

    // --- Historial de SOBP escaneados ---

    @GET("api/sobp-scans")
    suspend fun listSobpScans(@Header("X-Auth-Password") password: String): List<SobpScanDto>

    @POST("api/sobp-scans")
    suspend fun createSobpScan(
        @Header("X-Auth-Password") password: String,
        @Body body: CreateSobpScanInput,
    ): SobpScanDto

    @DELETE("api/sobp-scans/{id}")
    suspend fun deleteSobpScan(
        @Header("X-Auth-Password") password: String,
        @Path("id") id: String,
    ): Response<Unit>

    // --- Backup (copia completa del contenido del servidor) ---

    /** Descarga toda la copia (captures + notebooks + covers) como JSON. */
    @Streaming
    @GET("api/export")
    suspend fun exportData(@Header("X-Auth-Password") password: String): ResponseBody

    /** Restaura una copia: REEMPLAZA todo el contenido del servidor. */
    @POST("api/import")
    suspend fun importData(
        @Header("X-Auth-Password") password: String,
        @Body body: RequestBody,
    ): Response<Unit>
}

object ApiFactory {
    /** Nombre legible del dispositivo (p. ej. "Google Pixel 7") para X-Neopen-Device. */
    val deviceName: String by lazy {
        val maker = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }.orEmpty()
        val model = Build.MODEL.orEmpty()
        val name = when {
            model.isBlank() -> maker
            model.startsWith(maker, ignoreCase = true) -> model
            else -> "$maker $model".trim()
        }
        name.ifBlank { "Android" }
    }

    fun create(serverUrl: String): NeopenApi {
        val base = serverUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Identifica el dispositivo en cada petición; el servidor anota el
            // último sync (X-Neopen-Device) al recibir capturas/bolis.
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("X-Neopen-Device", deviceName)
                        .build(),
                )
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(NeopenApi::class.java)
    }
}
