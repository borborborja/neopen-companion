package net.micapum.neopen.app.ime

import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.micapum.neopen.app.graph
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.ink.InkLanguage
import net.micapum.neopen.core.ink.InkRecognizer
import net.micapum.neopen.core.ink.LiveTranscriber
import net.micapum.neopen.core.pen.PenManager

/**
 * Teclado (IME) de escritura en vivo. Mientras está activo, suscribe el flujo de trazos del
 * boli (`PenManager.onStroke`), los pasa a `LiveTranscriber` y vuelca el texto reconocido en
 * el campo enfocado de *cualquier* app vía `InputConnection`. No dibuja teclas alfabéticas:
 * el dispositivo de entrada es el papel. Muestra estado del boli, la palabra en curso y unas
 * pocas teclas de control.
 */
class NeopenImeService : InputMethodService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var pen: PenManager

    private var recognizer: InkRecognizer? = null
    private var transcriber: LiveTranscriber? = null
    private var currentLang: InkLanguage = InkLanguage.ES

    @Volatile private var settingsCache: AppSettings = AppSettings()
    private var statusJob: Job? = null

    private var statusView: TextView? = null
    private var previewView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        pen = applicationContext.graph.pen
        scope.launch {
            applicationContext.graph.settings.flow.collectLatest { settingsCache = it }
        }
    }

    // ----------------------------------------------------------------------------------
    // Vista del teclado
    // ----------------------------------------------------------------------------------

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111316"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = ""
        }
        previewView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
            setPadding(0, dp(6), 0, dp(10))
            minHeight = dp(36)
            text = ""
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(key("⌫") { ic()?.deleteSurroundingText(1, 0) }, weight())
        row.addView(key("espacio") { ic()?.commitText(" ", 1) }, weight())
        row.addView(key("↵") { ic()?.commitText("\n", 1) }, weight())
        row.addView(key("⌨") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }, weight())

        root.addView(statusView)
        root.addView(previewView)
        root.addView(row)
        return root
    }

    private fun key(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2D31"))
            setOnClickListener { onClick() }
        }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { marginEnd = dp(6) }

    // ----------------------------------------------------------------------------------
    // Ciclo de vida de la entrada
    // ----------------------------------------------------------------------------------

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val lang = InkLanguage.entries.firstOrNull { it.tag == settingsCache.liveLanguage } ?: InkLanguage.ES
        ensureTranscriber(lang)
        // Arranca el contexto con el texto que ya hay antes del cursor.
        val before = ic()?.getTextBeforeCursor(20, 0)?.toString().orEmpty()
        transcriber?.setPreContext(before)
        pen.enterLiveMode()
        observeStatus()
        wireWhenModelReady()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        pen.onStroke = null
        transcriber?.flush()
        pen.exitLiveMode()
        statusJob?.cancel(); statusJob = null
        previewView?.text = ""
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        pen.onStroke = null
        pen.exitLiveMode()
        transcriber?.close()
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------------------------------

    /** (Re)crea el transcriptor si cambia el idioma elegido en Ajustes. */
    private fun ensureTranscriber(lang: InkLanguage) {
        if (transcriber != null && lang == currentLang) return
        transcriber?.close()
        currentLang = lang
        val rec = InkRecognizer(lang)
        recognizer = rec
        transcriber = LiveTranscriber(
            recognizer = rec,
            scope = scope,
            config = LiveTranscriber.Config(emitNewlines = settingsCache.liveNewlines),
            onComposing = { text ->
                ic()?.setComposingText(text, 1)
                previewView?.text = text
            },
            onCommit = { text ->
                ic()?.commitText(text, 1)
                previewView?.text = ""
            },
        )
    }

    /** Comprueba/descarga el modelo del idioma y, cuando está listo, conecta los trazos. */
    private fun wireWhenModelReady() {
        val rec = recognizer ?: return
        val t = transcriber ?: return
        pen.onStroke = null
        scope.launch {
            val ready = runCatching { rec.isDownloaded() }.getOrDefault(false)
            if (!ready) {
                setStatus("Descargando modelo (${currentLang.display})…")
                val ok = runCatching { rec.download() }.isSuccess
                if (!ok) { setStatus("No se pudo descargar el modelo ${currentLang.display}"); return@launch }
            }
            pen.onStroke = { stroke -> t.feed(stroke) }
            refreshStatus()
        }
    }

    private fun observeStatus() {
        statusJob?.cancel()
        statusJob = scope.launch {
            pen.state.collectLatest { refreshStatus() }
        }
    }

    private fun refreshStatus() {
        val conn = if (pen.state.value.connected) "Boli conectado" else "Boli desconectado — conéctalo en la app"
        setStatus("$conn · ${currentLang.display}")
    }

    private fun setStatus(text: String) { statusView?.text = text }

    private fun ic() = currentInputConnection

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
