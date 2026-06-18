package net.micapum.neopen.app.capability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.micapum.neopen.app.Graph
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.app.render.rasterizePng
import net.micapum.neopen.core.capability.LocalOcrProvider
import net.micapum.neopen.core.capability.LocalTextCorrector
import net.micapum.neopen.core.capability.MarkerDetector
import net.micapum.neopen.core.data.ApiFactory
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.CapabilitySource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Resultado de un intento de OCR despachado. */
sealed interface OcrOutcome {
    /** Se aplicó (en el servidor o en el teléfono). */
    data object Applied : OcrOutcome

    /** Capacidad desactivada o captura inexistente: nada que hacer. */
    data object Skipped : OcrOutcome

    /** No se pudo (sin credenciales, o falló servidor y teléfono). */
    data class Failed(val error: String?) : OcrOutcome
}

/**
 * Decide quién ejecuta cada capacidad según el conmutador de 3 estados y gestiona
 * el repliegue: en modo SERVIDOR, si el servidor falla (o no hay conexión) y hay
 * credenciales locales, el teléfono hace el trabajo y (Fase 3) lo sincroniza.
 */
class CapabilityDispatcher(private val graph: Graph) {

    suspend fun runOcr(captureId: String): OcrOutcome = withContext(Dispatchers.IO) {
        val s = graph.settings.flow.first()
        val cap = graph.repo.capture(captureId) ?: return@withContext OcrOutcome.Skipped
        when (s.ocrSource) {
            CapabilitySource.OFF -> OcrOutcome.Skipped

            CapabilitySource.PHONE ->
                if (!s.ocrLocal.isUsable) OcrOutcome.Failed("phone-no-credentials")
                else runCatching { runLocal(s, cap, syncBack = false); OcrOutcome.Applied }
                    .getOrElse { OcrOutcome.Failed(it.message) }

            CapabilitySource.SERVER -> {
                val serverConfigured = s.appMode != AppMode.LOCAL && s.configured
                val serverOk = serverConfigured && graph.online.value &&
                    runCatching { runServer(s, cap) }.getOrDefault(false)
                when {
                    serverOk -> OcrOutcome.Applied
                    // Repliegue al teléfono si hay credenciales locales; marca syncBack
                    // para subir el resultado al servidor cuando vuelva (Fase 3).
                    s.ocrLocal.isUsable ->
                        runCatching { runLocal(s, cap, syncBack = serverConfigured); OcrOutcome.Applied }
                            .getOrElse { OcrOutcome.Failed(it.message) }
                    else -> OcrOutcome.Failed("server-unavailable")
                }
            }
        }
    }

    /** Sube el PNG al servidor para que transcriba y aplica la captura devuelta. */
    private suspend fun runServer(s: AppSettings, cap: Capture): Boolean {
        val png = rasterizePng(
            cap.strokes,
            cap.paper,
            pressureWidthEnabled = s.effectivePressureWidthEnabled,
            pressureWidthContrast = s.effectivePressureWidthContrast,
        )
        val part = MultipartBody.Part.createFormData(
            "image", "page.png", png.toRequestBody("image/png".toMediaType()),
        )
        val dto = ApiFactory.create(s.serverUrl).transcribe(s.password, cap.id, part, null)
        graph.repo.applyServerCapture(dto)
        return true
    }

    /** Pipeline local: OCR → (LanguageTool) → (marcadores) → aplicar a Room. */
    private suspend fun runLocal(s: AppSettings, cap: Capture, syncBack: Boolean) {
        val png = rasterizePng(
            cap.strokes,
            cap.paper,
            pressureWidthEnabled = s.effectivePressureWidthEnabled,
            pressureWidthContrast = s.effectivePressureWidthContrast,
        )
        var result = LocalOcrProvider(s.ocrLocal).transcribe(png, s.ocrLocal.effectiveInstructions)

        // LanguageTool: corrige el texto si la capacidad no está apagada y hay credenciales.
        // Se tragan los errores (se conserva la transcripción), igual que el servidor.
        if (s.languageToolSource != CapabilitySource.OFF && s.ltLocal.isUsable) {
            runCatching { LocalTextCorrector(s.ltLocal).correct(result.text.orEmpty(), result.language) }
                .getOrNull()?.let { result = result.copy(text = it) }
        }

        // Marcadores: solo tocan tags/título/kind de la captura (los tags del OCR
        // viven en la transcripción), igual que `setTranscription` + `applyDetection`.
        var tags: List<String>? = null
        var name: String? = null
        var kind: String? = null
        if (s.markerDetectionSource != CapabilitySource.OFF) {
            val det = MarkerDetector.detect(result.text.orEmpty())
            if (det.tags.isNotEmpty()) tags = (cap.tags + det.tags).distinct()
            name = det.title
            kind = det.kind
        }
        graph.repo.applyLocalTranscription(cap.id, result, tags, name, kind)

        if (syncBack) graph.enqueueTranscriptionSync(cap.id)
    }
}
