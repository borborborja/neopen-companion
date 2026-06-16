package net.micapum.neopen.live.ink

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import kotlinx.coroutines.tasks.await
import net.micapum.neopen.core.pen.Stroke

/** Idiomas soportados por el modo escritura en vivo. */
enum class InkLanguage(val tag: String, val display: String) {
    ES("es", "Español"),
    CA("ca", "Català"),
    EN("en", "English"),
}

/**
 * Envoltorio sobre ML Kit Digital Ink Recognition (online HWR on-device).
 *
 * Convierte trazos del boli (`List<Stroke>`, coords en celdas Ncode) → texto, sin red y en
 * ~decenas de ms. Un recognizer por idioma; los modelos (~20 MB) se descargan/borran bajo
 * demanda. No mantiene estado de segmentación — eso es responsabilidad de `LiveTranscriber`.
 */
class InkRecognizer(val language: InkLanguage) {

    private val model = DigitalInkRecognitionModel.builder(
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(language.tag)!!
    ).build()
    private val manager = RemoteModelManager.getInstance()
    private val recognizer: DigitalInkRecognizer =
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())

    suspend fun isDownloaded(): Boolean = manager.isModelDownloaded(model).await()

    /** Descarga el modelo del idioma (idempotente; ML Kit no re-descarga si ya está). */
    suspend fun download(requireWifi: Boolean = false) {
        val conditions = DownloadConditions.Builder().apply {
            if (requireWifi) requireWifi()
        }.build()
        manager.download(model, conditions).await()
    }

    suspend fun delete() {
        manager.deleteDownloadedModel(model).await()
    }

    /**
     * Reconoce un lote de trazos. `preContext` = texto ya escrito (mejora la continuación de
     * palabra/frase); `writingArea` = (ancho, alto) de la zona de escritura en las mismas
     * unidades que los puntos (celdas Ncode), opcional pero recomendable.
     *
     * Devuelve los candidatos ordenados de mejor a peor; lista vacía si no hay nada que
     * reconocer (sin puntos).
     */
    suspend fun recognize(
        strokes: List<Stroke>,
        preContext: String? = null,
        writingArea: Pair<Float, Float>? = null,
    ): List<String> {
        val ink = strokes.toInk() ?: return emptyList()
        val ctx = RecognitionContext.builder().apply {
            if (!preContext.isNullOrEmpty()) setPreContext(preContext)
            writingArea?.let { setWritingArea(WritingArea(it.first, it.second)) }
        }.build()
        val result = recognizer.recognize(ink, ctx).await()
        return result.candidates.map { it.text }
    }

    fun close() = recognizer.close()
}

/** Construye un `Ink` de ML Kit desde los trazos del boli. Null si no hay ningún punto. */
internal fun List<Stroke>.toInk(): Ink? {
    val builder = Ink.builder()
    var any = false
    for (s in this) {
        if (s.dots.isEmpty()) continue
        val sb = Ink.Stroke.builder()
        for (d in s.dots) sb.addPoint(Ink.Point.create(d.x, d.y, d.t))
        builder.addStroke(sb.build())
        any = true
    }
    return if (any) builder.build() else null
}
