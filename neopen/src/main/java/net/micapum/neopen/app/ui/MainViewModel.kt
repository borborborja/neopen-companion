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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.micapum.neopen.app.R
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.app.data.Notebook
import net.micapum.neopen.app.data.PageBackground
import net.micapum.neopen.app.db.PageBackgroundEntity
import net.micapum.neopen.app.graph
import net.micapum.neopen.app.render.ExportFormat
import net.micapum.neopen.app.sync.PenService
import net.micapum.neopen.app.render.rasterizePng
import net.micapum.neopen.app.render.renderNotebookPdf
import net.micapum.neopen.app.render.renderPagePdf
import net.micapum.neopen.app.render.renderSvg
import net.micapum.neopen.core.data.AnchorDto
import net.micapum.neopen.core.data.ApiFactory
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.BackgroundUploadInput
import net.micapum.neopen.core.data.FinishModeBody
import net.micapum.neopen.core.data.CreateSobpScanInput
import net.micapum.neopen.core.data.NotebookPaperBody
import net.micapum.neopen.core.data.OverlapConfigDto
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.SplitBody
import net.micapum.neopen.core.data.SobpDto
import net.micapum.neopen.core.data.SobpScanDto
import net.micapum.neopen.core.data.StudioItemDto
import net.micapum.neopen.core.pen.OfflineNote
import net.micapum.neopen.core.pen.PenState
import net.micapum.neopen.core.pen.Sobp
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

    /** Últimas ediciones: todas las capturas por fecha (para la pestaña Bandeja). */
    val recent: StateFlow<List<Capture>> =
        graph.repo.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * SOBP con ≥2 capturas no-clon: sus hojas finalizadas NO se pueden reabrir, porque
     * al reescribir el mismo código ya se creó otra hoja y reabrir haría ambiguo a
     * dónde van los trazos del boli. La UI lo usa para ocultar el botón "Reabrir".
     */
    val supersededSobps: StateFlow<Set<String>> =
        graph.repo.recent()
            .map { caps ->
                caps.filter { it.clonedFrom == null }
                    .groupingBy { it.sobpLabel }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val pending: StateFlow<Int> =
        graph.repo.pendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _serverCheck = MutableStateFlow<ServerCheck>(ServerCheck.Idle)
    val serverCheck: StateFlow<ServerCheck> = _serverCheck.asStateFlow()

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

    /**
     * Prueba la conexión (sin guardar): comprueba que el servidor responde
     * (`/api/health`) y que la contraseña es válida (`/api/capabilities`).
     * El resultado se publica en [serverCheck].
     */
    fun testServer(url: String, password: String) {
        if (url.isBlank()) {
            _serverCheck.value = ServerCheck.Unreachable(getApplication<Application>().getString(R.string.vm_missing_server_url))
            return
        }
        _serverCheck.value = ServerCheck.Checking
        viewModelScope.launch {
            val api = runCatching { ApiFactory.create(url.trim()) }.getOrElse {
                _serverCheck.value = ServerCheck.Unreachable(getApplication<Application>().getString(R.string.vm_invalid_url)); return@launch
            }
            val reachable = runCatching { api.health() }
            if (reachable.isFailure) {
                _serverCheck.value = ServerCheck.Unreachable(reachable.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.vm_no_response))
                return@launch
            }
            _serverCheck.value = runCatching { api.capabilities(password) }.fold(
                onSuccess = { resp ->
                    when (resp.code()) {
                        in 200..299 -> ServerCheck.Ok
                        401, 403 -> ServerCheck.BadAuth
                        else -> ServerCheck.Unreachable(getApplication<Application>().getString(R.string.vm_http_error, resp.code()))
                    }
                },
                onFailure = { ServerCheck.Unreachable(it.message ?: getApplication<Application>().getString(R.string.vm_network_error)) },
            )
        }
    }

    fun resetServerCheck() { _serverCheck.value = ServerCheck.Idle }

    /** Finaliza el wizard: persiste servidor, contraseña y modo, y sincroniza. */
    fun completeSetup(url: String, password: String, mode: AppMode, onDone: () -> Unit) {
        viewModelScope.launch {
            graph.settings.update(
                settings.value.copy(
                    serverUrl = url.trim(),
                    password = password,
                    appMode = mode,
                    setupComplete = true,
                ),
            )
            onDone()
            syncNow()
        }
    }

    /** Cambia el modo de la app (Ajustes). */
    fun setAppMode(mode: AppMode) {
        viewModelScope.launch { graph.settings.update(settings.value.copy(appMode = mode)) }
    }

    /**
     * Vincula un servidor a una base LOCAL: valida, migra todo el contenido al
     * servidor (sin perder datos; el servidor deduplica por bookKey/SOBP) y pasa
     * a modo completo. Si la validación o la migración fallan, no toca lo local.
     */
    fun linkServer(url: String, password: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val clean = url.trim()
            val api = runCatching { ApiFactory.create(clean) }.getOrNull()
            if (api == null) { onResult(false, getApplication<Application>().getString(R.string.vm_invalid_url)); return@launch }
            val code = runCatching { api.capabilities(password).code() }.getOrElse {
                onResult(false, getApplication<Application>().getString(R.string.vm_server_connect_failed)); return@launch
            }
            if (code !in 200..299) {
                onResult(false, if (code == 401 || code == 403) getApplication<Application>().getString(R.string.vm_wrong_password) else getApplication<Application>().getString(R.string.vm_server_error, code))
                return@launch
            }
            val migrated = runCatching { graph.repo.linkAndMerge(api, password) }
            if (migrated.isFailure) {
                onResult(false, getApplication<Application>().getString(R.string.vm_migration_failed, migrated.exceptionOrNull()?.message ?: ""))
                return@launch
            }
            graph.settings.update(settings.value.copy(serverUrl = clean, password = password, appMode = AppMode.FULL))
            graph.syncNow()
            onResult(true, null)
        }
    }

    /** Guarda los ajustes, aplica el calendario de sync y arranca/para el servicio BLE. */
    fun saveSettings(s: AppSettings) {
        viewModelScope.launch {
            graph.settings.update(s)
            graph.applySyncSchedule(s.syncMode, s.syncIntervalMin)
            val ctx = getApplication<Application>()
            if (s.backgroundEnabled) PenService.start(ctx) else PenService.stop(ctx)
        }
    }

    // --- Backup (copia completa del servidor) ---

    /** Descarga la copia completa del servidor (JSON) como bytes. */
    suspend fun exportBackup(): ByteArray? = runCatching {
        val s = settings.value
        withContext(Dispatchers.IO) { ApiFactory.create(s.serverUrl).exportData(s.password).bytes() }
    }.getOrNull()

    /** Restaura una copia (REEMPLAZA el contenido del servidor) y resincroniza. */
    suspend fun restoreBackup(bytes: ByteArray): Boolean {
        val s = settings.value
        val ok = runCatching {
            val body = bytes.toRequestBody("application/json".toMediaType())
            withContext(Dispatchers.IO) { ApiFactory.create(s.serverUrl).importData(s.password, body).isSuccessful }
        }.getOrDefault(false)
        if (ok) syncNow()
        return ok
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

    fun createNotebook(name: String, color: String, tags: List<String> = emptyList(), category: String? = null) =
        mutate { graph.repo.createNotebook(name, color, tags, category) }
    fun editNotebook(id: String, name: String, color: String, tags: List<String>, category: String) =
        mutate { graph.repo.editNotebook(id, name, color, tags, category) }
    fun deleteNotebook(id: String) = mutate { graph.repo.deleteNotebook(id) }
    fun setTags(id: String, tags: List<String>) = mutate { graph.repo.setCaptureTags(id, tags) }

    // --- Formato de página (proporciones) ---

    /** Formato de una hoja individual (override). Vía outbox (offline-capable). */
    fun setCapturePaper(id: String, paper: PaperDto?) = mutate { graph.repo.setCapturePaper(id, paper) }

    /**
     * Formato del cuaderno: PUT directo al servidor (re-sella sus hojas, mismo
     * endpoint que la web), copia optimista local y resync. Requiere conexión.
     */
    fun setNotebookPaper(id: String, paper: PaperDto, source: String?) {
        viewModelScope.launch {
            val s = settings.value
            val ok = runCatching {
                ApiFactory.create(s.serverUrl).setNotebookPaper(s.password, id, NotebookPaperBody(paper, source))
            }.isSuccess
            if (ok) {
                graph.repo.applyNotebookPaperLocal(id, paper)
                graph.syncNow()
            } else {
                _status.value = getApplication<Application>().getString(R.string.vm_set_notebook_paper_failed)
            }
        }
    }

    // --- Boli (BLE) ---

    val penState: StateFlow<PenState> = graph.pen.state
    val online: StateFlow<Boolean> = graph.online
    /** Registro de diagnóstico BLE (panel de debug). */
    val penLogs: StateFlow<List<String>> = graph.pen.logs

    fun penReady(): Boolean = graph.pen.isReady
    fun connectPen() = graph.pen.scanAndConnect()
    fun disconnectPen() = graph.pen.disconnect()
    fun stopConnecting() = graph.pen.disconnect()
    fun clearLogs() = graph.pen.clearLogs()

    // Ajustes del boli
    fun setAutoPowerOn(on: Boolean) = graph.pen.setAutoPowerOn(on)
    fun setBeep(on: Boolean) = graph.pen.setBeep(on)
    fun setOfflineData(on: Boolean) = graph.pen.setOfflineData(on)
    fun setAutoShutdown(min: Int) = graph.pen.setAutoShutdown(min)

    // Memoria offline
    fun listOffline() = graph.pen.listOffline()
    fun importNote(n: OfflineNote) = graph.pen.importOffline(n)
    fun importOffline() = graph.pen.importAllOffline()
    fun clearOffline() = graph.pen.clearOffline(penState.value.notes ?: emptyList())

    // --- Leer SOBP (herramienta del boli) + historial ---

    private val _detectedSobp = MutableStateFlow<Sobp?>(null)
    val detectedSobp: StateFlow<Sobp?> = _detectedSobp.asStateFlow()

    private val _sobpScans = MutableStateFlow<List<SobpScanDto>>(emptyList())
    val sobpScans: StateFlow<List<SobpScanDto>> = _sobpScans.asStateFlow()

    /** Empieza a esperar una marca; el SOBP detectado se publica en [detectedSobp]. */
    fun startReadSobp() {
        _detectedSobp.value = null
        graph.pen.captureNextSobp { _detectedSobp.value = it }
    }

    fun cancelReadSobp() {
        graph.pen.cancelSobpCapture()
        _detectedSobp.value = null
    }

    /** Guarda el SOBP detectado con su identificador en el servidor. */
    fun saveSobpScan(label: String, onResult: (Boolean) -> Unit) {
        val sobp = _detectedSobp.value ?: run { onResult(false); return }
        viewModelScope.launch {
            val s = settings.value
            val pen = penState.value.name ?: penState.value.mac
            val ok = runCatching {
                ApiFactory.create(s.serverUrl).createSobpScan(
                    s.password,
                    CreateSobpScanInput(
                        label = label.ifBlank { "${sobp.section}.${sobp.owner}.${sobp.book}.${sobp.page}" },
                        sobp = SobpDto(sobp.section, sobp.owner, sobp.book, sobp.page),
                        pen = pen,
                    ),
                )
            }.isSuccess
            if (ok) {
                _detectedSobp.value = null
                loadSobpScans()
            } else {
                _status.value = getApplication<Application>().getString(R.string.vm_save_sobp_failed)
            }
            onResult(ok)
        }
    }

    fun loadSobpScans() {
        viewModelScope.launch {
            val s = settings.value
            runCatching { ApiFactory.create(s.serverUrl).listSobpScans(s.password) }
                .onSuccess { _sobpScans.value = it }
        }
    }

    fun deleteSobpScan(id: String) {
        viewModelScope.launch {
            val s = settings.value
            runCatching { ApiFactory.create(s.serverUrl).deleteSobpScan(s.password, id) }
            loadSobpScans()
        }
    }

    // --- OCR (solo online) ---

    fun transcribe(captureId: String) {
        viewModelScope.launch {
            if (!graph.online.value) {
                _status.value = getApplication<Application>().getString(R.string.vm_ocr_needs_connection)
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
            if (dto != null) graph.repo.applyServerCapture(dto) else _status.value = getApplication<Application>().getString(R.string.vm_ocr_failed)
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

    // --- Fondos (plantilla detrás de los trazos) ------------------------------

    private val _studioEnabled = MutableStateFlow(false)
    val studioEnabled: StateFlow<Boolean> = _studioEnabled.asStateFlow()

    private val _recents = MutableStateFlow<List<StudioItemDto>>(emptyList())
    val recents: StateFlow<List<StudioItemDto>> = _recents.asStateFlow()

    private val _presets = MutableStateFlow<List<StudioItemDto>>(emptyList())
    val presets: StateFlow<List<StudioItemDto>> = _presets.asStateFlow()

    /** ¿Hay servidor con el que sincronizar fondos? (no estamos en modo local). */
    fun hasServer(): Boolean =
        settings.value.appMode != AppMode.LOCAL && settings.value.serverUrl.isNotBlank()

    /** Refresca si Studio está activo (→ recientes/presets) al abrir el selector. */
    fun refreshStudio() {
        val s = settings.value
        if (!hasServer()) {
            _studioEnabled.value = false
            _recents.value = emptyList(); _presets.value = emptyList()
            return
        }
        viewModelScope.launch {
            val api = ApiFactory.create(s.serverUrl)
            val enabled = runCatching { api.capabilitiesInfo(s.password).studio?.enabled == true }.getOrDefault(false)
            _studioEnabled.value = enabled
            if (enabled) {
                _recents.value = runCatching { api.listRecents(s.password) }.getOrDefault(emptyList())
                _presets.value = runCatching { api.listPresets(s.password) }.getOrDefault(emptyList())
            } else {
                _recents.value = emptyList(); _presets.value = emptyList()
            }
        }
    }

    fun observeSheetBackground(bookKey: String, page: Int) = graph.repo.observeSheetBackground(bookKey, page)

    fun observeBookBackground(bookKey: String) = graph.repo.observeBookBackground(bookKey)

    private fun newBgId(): String = "bg-" + java.util.UUID.randomUUID()
    private fun nowIso(): String = java.time.Instant.now().toString()

    /** Guarda un PNG de fondo en el almacenamiento de la app (modo local). */
    private fun saveLocalPng(id: String, bytes: ByteArray): String {
        val dir = java.io.File(getApplication<Application>().filesDir, "backgrounds").apply { mkdirs() }
        val f = java.io.File(dir, "$id.png")
        f.writeBytes(bytes)
        return "file://" + f.absolutePath
    }

    private suspend fun uploadPng(bytes: ByteArray): String? {
        val s = settings.value
        val dataUrl = "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return runCatching {
            withContext(Dispatchers.IO) { ApiFactory.create(s.serverUrl).uploadBackground(s.password, BackgroundUploadInput(dataUrl)).url }
        }.getOrNull()
    }

    /**
     * Crea/actualiza el fondo de un cuaderno (`scope="book"`, page=null) o de una
     * hoja (`scope="page"`). En modo completo lo persiste como anchor en el
     * servidor (compartido con la web); en local, solo en Room (+ archivo si es PNG).
     */
    fun setBackground(scope: String, bookKey: String, page: Int?, choice: ResolvedBg) {
        viewModelScope.launch {
            val s = settings.value
            val existing = if (scope == "book") graph.repo.bookBackground(bookKey)
            else page?.let { graph.repo.pageBackground(bookKey, it) }
            val id = existing?.id ?: newBgId()
            val now = nowIso()
            var bgImage: String? = null
            var bgPreset: String? = null
            when (choice) {
                is ResolvedBg.Preset -> bgPreset = choice.id
                is ResolvedBg.ServerUrl -> bgImage = choice.url
                is ResolvedBg.Png -> {
                    bgImage = if (!hasServer()) saveLocalPng(id, choice.bytes)
                    else uploadPng(choice.bytes) ?: run { _status.value = getApplication<Application>().getString(R.string.vm_upload_background_failed); return@launch }
                }
            }
            if (hasServer()) {
                val ok = runCatching {
                    ApiFactory.create(s.serverUrl).putAnchor(
                        s.password, id,
                        AnchorDto(
                            id = id, label = choice.label, bookKey = bookKey, scope = scope,
                            bgImage = bgImage, bgPreset = bgPreset,
                            pageBySobp = if (scope == "page" && page != null) mapOf(page.toString() to 0) else null,
                            source = choice.source, createdAt = now,
                        ),
                    ).isSuccessful
                }.getOrDefault(false)
                if (!ok) { _status.value = getApplication<Application>().getString(R.string.vm_save_background_failed); return@launch }
            }
            graph.repo.upsertBackground(
                PageBackgroundEntity(
                    id = id, bookKey = bookKey, page = page, scope = scope,
                    bgPreset = bgPreset, bgImage = bgImage, label = choice.label,
                    source = choice.source, remote = hasServer(), createdAt = now,
                ),
            )
        }
    }

    fun removeBackground(bg: PageBackground) {
        viewModelScope.launch {
            if (bg.remote) {
                val s = settings.value
                runCatching { ApiFactory.create(s.serverUrl).deleteAnchor(s.password, bg.id) }
            }
            val img = bg.bgImage
            if (img != null && img.startsWith("file://")) runCatching { java.io.File(img.removePrefix("file://")).delete() }
            graph.repo.deleteBackground(bg.id)
        }
    }

    // --- Últimas ediciones + finalizar hoja ------------------------------------

    fun setRecentShowCount(n: Int) {
        viewModelScope.launch { graph.settings.update(settings.value.copy(recentShowCount = n)) }
    }

    /** Vista de la pestaña Cuadernos (Mosaic/Compact/List), persistida. */
    fun setNotebookView(view: String) {
        viewModelScope.launch { graph.settings.update(settings.value.copy(notebookView = view)) }
    }

    /** Tema de la app (system/light/dark), persistido. */
    fun setThemeMode(mode: String) {
        viewModelScope.launch { graph.settings.update(settings.value.copy(themeMode = mode)) }
    }

    /** Oculta lo actual de "Últimas ediciones"; una edición posterior lo trae de vuelta. */
    fun clearRecent() {
        viewModelScope.launch { graph.settings.update(settings.value.copy(recentClearedAt = nowIso())) }
    }

    /** Cambia el modo al finalizar; en modo completo lo sincroniza con el servidor (= web). */
    fun setFinishMode(mode: String) {
        viewModelScope.launch {
            graph.settings.update(settings.value.copy(finishMode = mode))
            if (hasServer()) {
                val s = settings.value
                runCatching { ApiFactory.create(s.serverUrl).setFinishMode(s.password, FinishModeBody(mode)) }
            }
        }
    }

    /** Al abrir Ajustes en modo completo, refleja el modo del servidor (consistencia con web). */
    fun refreshFinishMode() {
        if (!hasServer()) return
        viewModelScope.launch {
            val s = settings.value
            val mode = runCatching { ApiFactory.create(s.serverUrl).getFinishMode(s.password).mode }.getOrNull()
            if (mode != null && mode != settings.value.finishMode) {
                graph.settings.update(settings.value.copy(finishMode = mode))
            }
        }
    }

    /** Finaliza (sella) la hoja o, si ya está sellada, la reabre. Efecto según finishMode. */
    fun finalizeCapture(c: Capture) {
        viewModelScope.launch {
            val s = settings.value
            val local = !hasServer()
            if (c.sealed) {
                // No reabrir si ya hay otra hoja con el mismo SOBP (finalizada permanente).
                if (supersededSobps.value.contains(c.sobpLabel)) return@launch
                if (local) graph.repo.setCaptureSealedLocal(c.id, false)
                else { graph.repo.setCaptureSealed(c.id, false); graph.syncNow() }
                return@launch
            }
            if (s.finishMode == "newNotebook" && c.notebookId != null) {
                if (local) {
                    graph.repo.finalizeNotebookLocal(c.notebookId)
                } else {
                    val ok = runCatching { ApiFactory.create(s.serverUrl).finalizeNotebook(s.password, c.notebookId) }.isSuccess
                    if (ok) graph.syncNow() else _status.value = getApplication<Application>().getString(R.string.vm_finalize_notebook_failed)
                }
            } else {
                if (local) graph.repo.setCaptureSealedLocal(c.id, true)
                else { graph.repo.setCaptureSealed(c.id, true); graph.syncNow() }
            }
        }
    }

    /** Cambia el umbral de superposición; en modo completo lo sincroniza con el servidor. */
    fun setOverlapConfig(enabled: Boolean, minStrokes: Int, minAreaPercent: Int) {
        viewModelScope.launch {
            graph.settings.update(
                settings.value.copy(
                    overlapEnabled = enabled,
                    overlapMinStrokes = minStrokes,
                    overlapMinAreaPercent = minAreaPercent,
                ),
            )
            if (hasServer()) {
                val s = settings.value
                runCatching {
                    ApiFactory.create(s.serverUrl)
                        .setOverlapConfig(s.password, OverlapConfigDto(enabled, minStrokes, minAreaPercent))
                }
            }
        }
    }

    /** Al abrir Ajustes en modo completo, refleja el umbral del servidor (consistencia con web). */
    fun refreshOverlapConfig() {
        if (!hasServer()) return
        viewModelScope.launch {
            val s = settings.value
            val cfg = runCatching { ApiFactory.create(s.serverUrl).getOverlapConfig(s.password) }.getOrNull() ?: return@launch
            graph.settings.update(
                settings.value.copy(
                    overlapEnabled = cfg.enabled,
                    overlapMinStrokes = cfg.minStrokes,
                    overlapMinAreaPercent = cfg.minAreaPercent,
                ),
            )
        }
    }

    /**
     * Resuelve el aviso de trazos superpuestos de una hoja:
     * - "same": descarta el aviso (continuar en la misma página).
     * - "newSheet" / "newNotebook": corta en `cutIndex` (sella la original, crea hoja nueva).
     */
    fun resolveOverlap(c: Capture, cutIndex: Int, action: String) {
        viewModelScope.launch {
            val s = settings.value
            val local = !hasServer()
            if (action == "same") {
                if (local) {
                    graph.repo.dismissOverlapLocal(c.id)
                } else {
                    val dto = runCatching { ApiFactory.create(s.serverUrl).dismissOverlap(s.password, c.id) }.getOrNull()
                    if (dto != null) { graph.repo.applyServerCapture(dto); graph.syncNow() }
                    else _status.value = getApplication<Application>().getString(R.string.vm_dismiss_overlap_failed)
                }
                return@launch
            }
            if (local) {
                graph.repo.splitCaptureLocal(c.id, cutIndex, action)
            } else {
                val res = runCatching {
                    ApiFactory.create(s.serverUrl).splitCapture(s.password, c.id, SplitBody(cutIndex, action))
                }.getOrNull()
                if (res != null) { graph.repo.applySplitResult(res.original, res.created); graph.syncNow() }
                else _status.value = getApplication<Application>().getString(R.string.vm_split_capture_failed)
            }
        }
    }

    private fun fileBaseName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "").replace(' ', '_').take(60)
        return cleaned.ifBlank { "neopen" }
    }
}

/** Fichero exportado listo para compartir o guardar. */
class ExportFile(val filename: String, val mime: String, val bytes: ByteArray)

/** Fondo elegido en el selector, ya resuelto a algo que se puede persistir. */
sealed interface ResolvedBg {
    val label: String
    val source: String

    /** Preset del catálogo (patrón dibujado en el cliente). */
    data class Preset(val id: String, override val label: String) : ResolvedBg {
        override val source get() = "preset"
    }

    /** Imagen ya en el servidor (reciente/preset de Studio). */
    data class ServerUrl(val url: String, override val label: String) : ResolvedBg {
        override val source get() = "custom"
    }

    /** PNG rasterizado en el dispositivo (imagen o PDF subidos por el usuario). */
    data class Png(val bytes: ByteArray, override val label: String) : ResolvedBg {
        override val source get() = "custom"
    }
}

/** Resultado de la prueba de conexión con el servidor neopen. */
sealed interface ServerCheck {
    data object Idle : ServerCheck
    data object Checking : ServerCheck
    data object Ok : ServerCheck
    data object BadAuth : ServerCheck
    data class Unreachable(val detail: String) : ServerCheck
}
