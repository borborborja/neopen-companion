package net.micapum.neopen.core.capability

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.micapum.neopen.core.data.OcrDateDto
import net.micapum.neopen.core.data.OcrLocalConfig
import net.micapum.neopen.core.data.OcrResultDto
import net.micapum.neopen.core.data.OcrTodoDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OCR/HTR en el teléfono contra un endpoint compatible con OpenAI
 * (`/chat/completions`). Port 1:1 de `apps/server/src/ocr/provider.ts`: mismo
 * SYSTEM_PROMPT, `temperature` 0.2, `response_format` json_object, y el mismo
 * parseo tolerante (`parseLoose` + `normalize`). Vale para OpenRouter,
 * Ollama-cloud y Ollama local sin cambios.
 */
class LocalOcrProvider(
    private val cfg: OcrLocalConfig,
    private val client: OkHttpClient = defaultClient,
) : OcrProvider {

    override suspend fun transcribe(pngBytes: ByteArray, instructions: String): OcrResultDto =
        withContext(Dispatchers.IO) {
            val dataUrl = "data:image/png;base64," + Base64.encodeToString(pngBytes, Base64.NO_WRAP)

            val userContent = JSONArray()
                .put(JSONObject().put("type", "text").put("text", instructions))
                .put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", dataUrl)),
                )
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", userContent))
            val payload = JSONObject()
                .put("model", cfg.model)
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("response_format", JSONObject().put("type", "json_object"))

            val builder = Request.Builder()
                .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
            if (cfg.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${cfg.apiKey}")

            client.newCall(builder.build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw RuntimeException("OCR ${res.code}: ${body.take(300)}")
                }
                val content = runCatching {
                    JSONObject(body).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                }.getOrNull().orEmpty()
                normalize(parseLoose(content))
            }
        }

    companion object {
        /** Contrato JSON fijo: el usuario edita las instrucciones, esto no se toca. */
        private val SYSTEM_PROMPT = listOf(
            "Eres un motor de transcripción de notas manuscritas (HTR).",
            "Responde ÚNICAMENTE con un objeto JSON válido, sin texto alrededor ni vallas de código.",
            "Esquema exacto:",
            "{ \"title\": string, \"text\": string, \"language\": string, \"tags\": string[],",
            "  \"todos\": [{ \"text\": string, \"done\": boolean }],",
            "  \"dates\": [{ \"date\": string|null, \"text\": string }] }",
            "\"text\" en Markdown. \"date\" en formato YYYY-MM-DD si se puede inferir, si no null.",
            "Si un campo no aplica, devuelve [] o cadena vacía. No inventes contenido que no esté escrito.",
        ).joinToString("\n")

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(180, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build()
        }

        /** Extrae el JSON aunque el modelo lo envuelva en texto/vallas. */
        private fun parseLoose(content: String): JSONObject {
            runCatching { return JSONObject(content) }
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start >= 0 && end > start) {
                runCatching { return JSONObject(content.substring(start, end + 1)) }
            }
            return JSONObject()
        }

        private fun normalize(o: JSONObject): OcrResultDto = OcrResultDto(
            title = o.optString("title").ifBlank { "Nota" },
            text = o.optString("text"),
            language = o.optString("language").ifBlank { null },
            tags = o.optJSONArray("tags").strings().filter { it.isNotBlank() },
            todos = o.optJSONArray("todos").objects().map {
                OcrTodoDto(text = it.optString("text"), done = it.optBoolean("done", false))
            }.filter { it.text.isNotBlank() },
            dates = o.optJSONArray("dates").objects().map {
                OcrDateDto(date = it.optString("date").ifBlank { null }, text = it.optString("text"))
            }.filter { !it.text.isNullOrBlank() || !it.date.isNullOrBlank() },
        )

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
