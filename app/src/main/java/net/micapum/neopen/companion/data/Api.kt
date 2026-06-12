package net.micapum.neopen.companion.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// --- Modelos del contrato (docs/openapi.yaml del repo neopen) ---

data class SobpDto(val section: Int, val owner: Int, val book: Int, val page: Int)

data class DotDto(val x: Float, val y: Float, val f: Float, val t: Long)

data class StrokeDto(val startTime: Long, val endTime: Long, val dots: List<DotDto>)

data class CreateCaptureInput(
    val sobp: SobpDto,
    val source: String,
    val capturedAt: String,
    val name: String,
    val tags: List<String> = emptyList(),
    val strokes: List<StrokeDto>,
    // notebookId/destination se OMITEN: el servidor archiva por SOBP.
)

data class CaptureDto(val id: String, val notebookId: String? = null)

data class RegisterPenInput(val mac: String, val name: String? = null, val firmware: String? = null)

data class PenInfoDto(val mac: String, val alias: String? = null)

data class HealthDto(val status: String? = null)

/** Cliente del servidor neopen. La contraseña va en la cabecera por petición. */
interface NeopenApi {
    /** Healthcheck público (sin auth): comprueba que el servidor responde. */
    @GET("api/health")
    suspend fun health(): HealthDto

    /** Endpoint con auth: sirve para validar la contraseña (200 ok / 401 incorrecta). */
    @GET("api/capabilities")
    suspend fun capabilities(@Header("X-Auth-Password") password: String): Response<Unit>

    @POST("api/captures")
    suspend fun createCapture(
        @Header("X-Auth-Password") password: String,
        @Body body: CreateCaptureInput,
    ): CaptureDto

    @POST("api/pens")
    suspend fun registerPen(
        @Header("X-Auth-Password") password: String,
        @Body body: RegisterPenInput,
    ): PenInfoDto
}

object ApiFactory {
    fun create(serverUrl: String): NeopenApi {
        val base = serverUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(NeopenApi::class.java)
    }
}
