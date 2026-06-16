package net.micapum.neopen.core.ink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.micapum.neopen.core.pen.Stroke
import kotlin.math.max
import kotlin.math.min

/**
 * Convierte el flujo de trazos del boli en eventos de texto para el IME.
 *
 * ML Kit reconoce un *lote* de trazos, no decide dónde acaba una palabra; eso es lo que
 * resuelve esta clase. Acumula los trazos de la palabra en curso, muestra una vista previa
 * en vivo (`onComposing`) con debounce, y finaliza la palabra (`onCommit`) cuando detecta un
 * límite: o bien el siguiente trazo empieza claramente a la derecha (hueco horizontal), o
 * bien pasa un tiempo de inactividad. El texto ya confirmado se arrastra como `preContext`
 * para mejorar la continuación.
 *
 * Diacríticos (í, ñ, ç, é, à, ò…): son *delayed strokes* (el acento llega después). ML Kit
 * los maneja dentro de un mismo lote; por eso el límite de palabra exige hueco horizontal a
 * la derecha, no una simple pausa, y el idle es generoso. Es la zona a tunear.
 *
 * Todas las llamadas a `feed`/`flush`/`reset` deben hacerse en el mismo hilo (el principal);
 * el reconocimiento se lanza en `scope` y se serializa con un mutex.
 */
class LiveTranscriber(
    private val recognizer: InkRecognizer,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
    private val onComposing: (String) -> Unit,
    private val onCommit: (String) -> Unit,
) {
    data class Config(
        /** Hueco horizontal (celdas Ncode) para considerar que empieza otra palabra. */
        val wordGapCells: Float = 8f,
        /** Caída vertical (celdas) + retorno a la izquierda → nueva línea. */
        val newLineDropCells: Float = 10f,
        /** Inactividad tras la que se finaliza la palabra en curso. */
        val idleFinalizeMs: Long = 1200,
        /** Debounce de la vista previa: re-reconoce la palabra como mucho cada N ms. */
        val previewDebounceMs: Long = 280,
        /** Emitir saltos de línea reales ("\n") al detectar nueva línea (si no, un espacio). */
        val emitNewlines: Boolean = false,
        /** Tamaño (ancho, alto) de la zona de escritura en celdas; mejora la precisión. */
        val writingAreaCells: Pair<Float, Float>? = null,
    )

    private val wordStrokes = ArrayList<Stroke>()
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f
    private var hasBox = false
    private var preContext = ""

    private var previewJob: Job? = null
    private var idleJob: Job? = null
    private val recMutex = Mutex()

    /** Inicializa el contexto con el texto que ya hay antes del cursor (mejora la 1ª palabra). */
    fun setPreContext(text: String) {
        preContext = text.takeLast(PRECONTEXT_LEN)
    }

    /** Procesa un trazo completo recién terminado. */
    fun feed(stroke: Stroke) {
        if (stroke.dots.isEmpty()) return
        if (hasBox && isWordBoundary(stroke)) {
            finalizeWord(appendNewline = config.emitNewlines && isNewLine(stroke))
        }
        wordStrokes.add(stroke)
        expandBox(stroke)
        scheduleIdle()
        schedulePreview()
    }

    /** Fuerza el cierre de la palabra en curso (p. ej. al ocultar el teclado). */
    fun flush() = finalizeWord(appendNewline = false)

    /** Limpia todo el estado (palabra y contexto). Para cuando se reinicia la entrada. */
    fun reset() {
        previewJob?.cancel(); previewJob = null
        idleJob?.cancel(); idleJob = null
        resetWord()
        preContext = ""
    }

    // --- segmentación ---

    private fun isWordBoundary(stroke: Stroke): Boolean {
        val fx = stroke.dots.first().x
        if (fx - maxX > config.wordGapCells) return true
        return isNewLine(stroke)
    }

    private fun isNewLine(stroke: Stroke): Boolean {
        val f = stroke.dots.first()
        return f.y - maxY > config.newLineDropCells && f.x < minX
    }

    private fun expandBox(stroke: Stroke) {
        for (d in stroke.dots) {
            if (!hasBox) {
                minX = d.x; maxX = d.x; minY = d.y; maxY = d.y; hasBox = true
            } else {
                minX = min(minX, d.x); maxX = max(maxX, d.x)
                minY = min(minY, d.y); maxY = max(maxY, d.y)
            }
        }
    }

    private fun resetWord() {
        wordStrokes.clear()
        hasBox = false
    }

    // --- reconocimiento ---

    private fun schedulePreview() {
        previewJob?.cancel()
        val snapshot = wordStrokes.toList()
        val pre = preContext.ifEmpty { null }
        previewJob = scope.launch {
            delay(config.previewDebounceMs)
            val text = recMutex.withLock {
                recognizer.recognize(snapshot, pre, config.writingAreaCells)
            }.firstOrNull().orEmpty()
            if (text.isNotEmpty()) onComposing(text)
        }
    }

    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(config.idleFinalizeMs)
            finalizeWord(appendNewline = false)
        }
    }

    private fun finalizeWord(appendNewline: Boolean) {
        previewJob?.cancel(); previewJob = null
        idleJob?.cancel(); idleJob = null
        if (wordStrokes.isEmpty()) { resetWord(); return }
        val snapshot = wordStrokes.toList()
        val pre = preContext.ifEmpty { null }
        resetWord()
        scope.launch {
            val text = recMutex.withLock {
                recognizer.recognize(snapshot, pre, config.writingAreaCells)
            }.firstOrNull().orEmpty()
            if (text.isNotEmpty()) {
                val out = if (appendNewline) "$text\n" else "$text "
                onCommit(out)
                preContext = (preContext + out).takeLast(PRECONTEXT_LEN)
            }
        }
    }

    fun close() {
        previewJob?.cancel()
        idleJob?.cancel()
        recognizer.close()
    }

    private companion object {
        const val PRECONTEXT_LEN = 20
    }
}
