package net.micapum.neopen.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.app.data.Notebook
import net.micapum.neopen.app.graph
import net.micapum.neopen.app.render.ExportFormat
import net.micapum.neopen.app.render.rasterizePng
import net.micapum.neopen.app.render.renderNotebookPdf
import net.micapum.neopen.app.render.renderPagePdf
import net.micapum.neopen.app.render.renderSvg
import net.micapum.neopen.core.data.ApiFactory
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.pen.PenState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    val settings: StateFlow<AppSettings> =
        graph.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val notebooks: StateFlow<List<Notebook>> =
        graph.repo.notebooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inbox: StateFlow<List<Capture>> =
        graph.repo.inbox().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pending: StateFlow<Int> =
        graph.repo.pendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun capturesIn(notebookId: String) = graph.repo.capturesIn(notebookId)

    fun captureFlow(id: String) = graph.repo.captureFlow(id)

    fun serverUrl(): String = settings.value.serverUrl

    fun syncNow() {
        viewModelScope.launch {
            _syncing.value = true
            val r = graph.sync.sync()
            _syncing.value = false
            _status.value = if (r.ok) null else r.error
        }
    }

    /** Valida la conexión, guarda los ajustes y lanza una sincronización inicial. */
    fun connect(url: String, password: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val clean = url.trim()
            val ok = runCatching {
                ApiFactory.create(clean).capabilities(password).isSuccessful
            }.getOrDefault(false)
            if (!ok) {
                onResult(false, "No se pudo conectar o la contraseña es incorrecta")
                return@launch
            }
            graph.settings.update(
                settings.value.copy(serverUrl = clean, password = password),
            )
            onResult(true, null)
            syncNow()
        }
    }

    fun logout() {
        viewModelScope.launch {
            graph.repo.clearLocal()
            graph.settings.update(AppSettings())
        }
    }

    // --- Ediciones (Room optimista + outbox; se suben en segundo plano) ---

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            graph.syncNow()
        }
    }

    fun renameCapture(id: String, name: String) = mutate { graph.repo.renameCapture(id, name) }
    fun deleteCapture(id: String) = mutate { graph.repo.deleteCapture(id) }
    fun moveCapture(id: String, notebookId: String, position: Int) =
        mutate { graph.repo.moveCapture(id, notebookId, position) }

    fun createNotebook(name: String, color: String, tags: List<String> = emptyList()) =
        mutate { graph.repo.createNotebook(name, color, tags) }
    fun editNotebook(id: String, name: String, color: String, tags: List<String>) =
        mutate { graph.repo.editNotebook(id, name, color, tags) }
    fun deleteNotebook(id: String) = mutate { graph.repo.deleteNotebook(id) }
    fun setTags(id: String, tags: List<String>) = mutate { graph.repo.setCaptureTags(id, tags) }

    // --- Boli (BLE) ---

    val penState: StateFlow<PenState> = graph.pen.state
    val online: StateFlow<Boolean> = graph.online

    fun penReady(): Boolean = graph.pen.isReady
    fun connectPen() = graph.pen.scanAndConnect()
    fun disconnectPen() = graph.pen.disconnect()
    fun importOffline() = graph.pen.importAllOffline()

    // --- OCR (solo online) ---

    fun transcribe(captureId: String) {
        viewModelScope.launch {
            if (!graph.online.value) {
                _status.value = "El OCR necesita conexión"
                return@launch
            }
            val cap = graph.repo.capture(captureId) ?: return@launch
            val s = settings.value
            val dto = runCatching {
                val png = rasterizePng(cap.strokes, cap.paper)
                val body = png.toRequestBody("image/png".toMediaType())
                val part = MultipartBody.Part.createFormData("image", "page.png", body)
                ApiFactory.create(s.serverUrl).transcribe(s.password, captureId, part, null)
            }.getOrNull()
            if (dto != null) graph.repo.applyServerCapture(dto) else _status.value = "El OCR falló"
        }
    }

    // --- Exportar (genera los bytes; la UI los comparte o guarda) ---

    suspend fun exportPage(captureId: String, format: ExportFormat): ExportFile? {
        val cap = graph.repo.capture(captureId) ?: return null
        val bytes = withContext(Dispatchers.IO) {
            when (format) {
                ExportFormat.PDF -> renderPagePdf(cap.strokes, cap.paper)
                ExportFormat.PNG -> rasterizePng(cap.strokes, cap.paper)
                ExportFormat.SVG -> renderSvg(cap.strokes, cap.paper).toByteArray()
            }
        }
        return ExportFile("${fileBaseName(cap.name)}.${format.ext}", format.mime, bytes)
    }

    suspend fun exportNotebookPdf(notebookId: String): ExportFile? {
        val nb = graph.repo.notebook(notebookId) ?: return null
        val pages = graph.repo.capturesIn(notebookId).first()
        val bytes = withContext(Dispatchers.IO) { renderNotebookPdf(pages) }
        return ExportFile("${fileBaseName(nb.name)}.pdf", ExportFormat.PDF.mime, bytes)
    }

    private fun fileBaseName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "").replace(' ', '_').take(60)
        return cleaned.ifBlank { "neopen" }
    }
}

/** Fichero exportado listo para compartir o guardar. */
class ExportFile(val filename: String, val mime: String, val bytes: ByteArray)
