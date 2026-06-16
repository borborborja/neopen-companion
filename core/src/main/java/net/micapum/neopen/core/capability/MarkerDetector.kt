package net.micapum.neopen.core.capability

/**
 * Detección de marcadores manuscritos en la transcripción (port 1:1 de
 * `apps/server/src/ocr/detect.ts`):
 *   - #etiquetas → tags de la captura
 *   - *título*   → nombre de la captura
 *   - [TIPO]     → kind ([TODO], [AGENDA], [NOTES], [NOTA], [CORREU], [CORREO])
 * Solo se miran las primeras `maxLines` líneas (la "zona de cabecera").
 *
 * Nota: en Java/Kotlin `\p{L}` y `\p{N}` ya son Unicode por defecto (acentos,
 * ñ, ç…), así que no hace falta el flag `u` del original JS.
 */
data class Detected(
    val tags: List<String>,
    val title: String?,
    val kind: String?,
)

object MarkerDetector {
    private val TAG = Regex("(^|\\s)#([\\p{L}\\p{N}_-]{2,30})")
    private val TITLE = Regex("\\*\\s*([^*\\n]{2,80}?)\\s*\\*")
    private val KIND = Regex("\\[(TODO|AGENDA|NOTES|NOTA|CORREU|CORREO)\\]", RegexOption.IGNORE_CASE)

    fun detect(text: String, maxLines: Int = 5): Detected {
        val lines = text.split("\n").take(maxLines).joinToString("\n")
        val tags = TAG.findAll(lines).map { it.groupValues[2].lowercase() }.toList().distinct()
        val title = TITLE.find(lines)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val kind = KIND.find(lines)?.groupValues?.get(1)?.uppercase()
        return Detected(tags = tags, title = title, kind = kind)
    }
}
