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
)

/** Cuerpo de PUT /api/notebooks/{id}/paper (formato del cuaderno; re-sella sus hojas). */
data class NotebookPaperBody(val paper: PaperDto, val source: String? = null)

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

/** Cliente del servidor neopen. La contraseña va en la cabecera por petición. */
interface NeopenApi {
    /** Healthcheck público (sin auth): comprueba que el servidor responde. */
    @GET("api/health")
    suspend fun health(): HealthDto

    /** Endpoint con auth: sirve para validar la contraseña (200 ok / 401 incorrecta). */
    @GET("api/capabilities")
    suspend fun capabilities(@Header("X-Auth-Password") password: String): Response<Unit>

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
