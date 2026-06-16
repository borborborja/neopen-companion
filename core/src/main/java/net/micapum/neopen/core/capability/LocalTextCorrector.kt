package net.micapum.neopen.core.capability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.micapum.neopen.core.data.LanguageToolLocalConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Corrección de texto con LanguageTool en el teléfono (port 1:1 de
 * `apps/server/src/languageTool/provider.ts`). Aplica los reemplazos en orden de
 * offset descendente (igual que el servidor) para no desplazar offsets aún sin
 * aplicar. Vale para api.languagetool.org (público o premium) o una instancia propia.
 */
class LocalTextCorrector(
    private val cfg: LanguageToolLocalConfig,
    private val client: OkHttpClient = defaultClient,
) : TextCorrector {

    override suspend fun correct(text: String, language: String?): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        val form = FormBody.Builder()
            .add("text", text)
            .add("language", normalizeLanguage(language))
        if (cfg.username.isNotBlank()) form.add("username", cfg.username)
        if (cfg.apiKey.isNotBlank()) form.add("apiKey", cfg.apiKey)
        val endpoint = if (cfg.baseUrl.endsWith("/check")) cfg.baseUrl else cfg.baseUrl.trimEnd('/') + "/check"
        val req = Request.Builder().url(endpoint).post(form.build()).build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RuntimeException("LanguageTool ${res.code}: ${body.take(300)}")
            applyMatches(text, parseMatches(body))
        }
    }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}

internal data class LtMatch(val offset: Int, val length: Int, val replacement: String?)

private fun parseMatches(body: String): List<LtMatch> {
    val arr = runCatching { JSONObject(body).optJSONArray("matches") }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val m = arr.optJSONObject(i) ?: return@mapNotNull null
        val r0 = m.optJSONArray("replacements")?.optJSONObject(0)
        val replacement = if (r0 != null && r0.has("value")) r0.optString("value") else null
        LtMatch(offset = m.optInt("offset", -1), length = m.optInt("length", -1), replacement = replacement)
    }
}

/** Reemplazos de derecha a izquierda con la misma guarda que el servidor. */
internal fun applyMatches(text: String, matches: List<LtMatch>): String {
    var corrected = text
    var nextOffset = Int.MAX_VALUE
    for (m in matches.sortedByDescending { it.offset }) {
        val replacement = m.replacement ?: continue
        if (m.offset < 0 || m.length < 0) continue
        if (m.offset + m.length > nextOffset || m.offset + m.length > text.length) continue
        corrected = corrected.substring(0, m.offset) + replacement + corrected.substring(m.offset + m.length)
        nextOffset = m.offset
    }
    return corrected
}

internal fun normalizeLanguage(language: String?): String {
    val value = language?.trim()?.lowercase()
    if (value.isNullOrEmpty()) return "auto"
    val names = mapOf(
        "catalan" to "ca", "català" to "ca",
        "spanish" to "es", "español" to "es", "castellano" to "es",
        "english" to "en",
        "french" to "fr", "français" to "fr",
        "german" to "de", "deutsch" to "de",
        "italian" to "it", "italiano" to "it",
        "portuguese" to "pt", "português" to "pt",
    )
    return names[value] ?: if (Regex("^[a-z]{2,3}(-[a-z]{2})?$").matches(value)) value else "auto"
}
