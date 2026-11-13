package net.micapum.neopen.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import java.io.File
import android.graphics.Bitmap
import net.micapum.neopen.core.data.StudioBookDto
import net.micapum.neopen.core.data.StudioPenScopeDto
import net.micapum.neopen.core.data.normalizedServerUrl
import okhttp3.RequestBody.Companion.asRequestBody
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.micapum.neopen.app.R
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.app.data.Notebook
import net.micapum.neopen.app.data.OfflineImportWarning
import net.micapum.neopen.app.data.PageBackground
import net.micapum.neopen.app.db.PageBackgroundEntity
import net.micapum.neopen.app.graph
import net.micapum.neopen.app.render.ExportFormat
import net.micapum.neopen.app.render.PreviewAssetStore
import net.micapum.neopen.app.render.PagePreviewCache
import net.micapum.neopen.app.sync.PenService
import net.micapum.neopen.app.sync.SyncEngine
import net.micapum.neopen.app.capability.OcrOutcome
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
import net.micapum.neopen.core.data.OfflineReimportPolicy
import net.micapum.neopen.core.data.OverlapConfigDto
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.SplitBody
import net.micapum.neopen.core.data.SyncStatus
import net.micapum.neopen.core.data.SobpDto
import net.micapum.neopen.core.data.SobpScanDto
import net.micapum.neopen.core.data.StudioItemDto
import net.micapum.neopen.core.pen.KnownPen
import net.micapum.neopen.core.pen.OfflineNote
import net.micapum.neopen.core.pen.PenState
import net.micapum.neopen.core.pen.StrokeStyle
import net.micapum.neopen.core.pen.Sobp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    val studioEnabled = graph.sync.studioEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private fun canUseStudio() = graph.sync.studioAccess.allowedFor(settings.value)
    private fun invalidateStudioOnRejection(error: Throwable) {
        if (error is retrofit2.HttpException && error.code() == 403 && error.response()?.errorBody()?.string()?.contains("STUDIO_DISABLED") == true) graph.sync.studioAccess.invalidate()
    }
    val studioAvailability = graph.repo.studio.availability().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val studioBooks = graph.repo.studio.books().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val studioConflicts = graph.repo.studio.conflicts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val studioPendingConflicts = graph.repo.studio.pendingConflictIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    fun resolveStudioConflict(id: String, penId: String?, bookInstanceId: String? = null) { if (!canUseStudio()) return; viewModelScope.launch { runCatching { graph.repo.studio.resolveConflict(id, penId, bookInstanceId) }.onFailure { _status.value = it.message }; syncNow() } }
    val closedStudioInstances = graph.repo.studio.books().map { rows -> rows.flatMap { it.book.instances }.filter { it.closedAt != null }.map { it.id }.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val studioPens = graph.repo.studio.pens().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun patchStudioBook(book: StudioBookDto, name: String? = null, archived: Boolean? = null, penScope: StudioPenScopeDto? = null) {
        if (!canUseStudio()) return
        viewModelScope.launch { runCatching { graph.repo.studio.patch(book, name, archived, penScope) }.onFailure { _status.value = it.message }; syncNow() }
    }
    fun deleteStudioBook(book: StudioBookDto) { if (!canUseStudio()) return; viewModelScope.launch { graph.repo.studio.delete(book); syncNow() } }
    fun transferStudioPens(to: StudioBookDto, from: StudioBookDto, ids: List<String>) { if (!canUseStudio()) return; viewModelScope.launch { graph.repo.studio.transfer(to, from, ids); syncNow() } }
    fun cancelStudioChange(id: String) { viewModelScope.launch { graph.repo.studio.cancelPending(id); syncNow() } }
    fun linkStudioPen(id: String, identifier: String) { if (!canUseStudio()) return; viewModelScope.launch { graph.repo.studio.linkAlias(id, identifier); syncNow() } }

    private val previewAssets by lazy { PreviewAssetStore(getApplication<Application>()) }
    private var previewIdentity: List<Any?>? = null
    private var previewEpoch = java.util.UUID.randomUUID().toString()

    /** La identidad de caché no contiene contraseñas ni se publica en URLs. */
    @Synchronized
    fun thumbnailNamespace(): String {
        val s = settings.value
        val identity = listOf(normalizedServerUrl(s.serverUrl), s.syncServerUrl, s.password, s.appMode, graph.previewLibraryGeneration)
        if (identity != previewIdentity) {
            previewIdentity = identity
            previewEpoch = java.util.UUID.randomUUID().toString()
            PagePreviewCache.clear()
        }
        return previewEpoch
    }
    private fun privateAssetNamespace(): String {
        val s = settings.value
        // Persiste entre procesos. clearLocal elimina estos archivos; el contador
        // de biblioteca solo pertenece a las imágenes en memoria.
        return normalizedServerUrl(s.syncServerUrl.ifBlank { s.serverUrl })
    }
    private fun legacyAsset(key: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(getApplication<Application>().filesDir, "studio-assets/$hash")
    }
    /** Assets privados compartidos entre las páginas del mismo PDF. */
    private suspend fun studioAssetFile(id: String): File = withContext(Dispatchers.IO) {
        check(canUseStudio())
        val s = settings.value
        val scope = thumbnailNamespace()
        previewAssets.file(privateAssetNamespace(), "asset:$id", listOf(legacyAsset(id))) { temporary ->
            ApiFactory.create(s.serverUrl).studioAsset(s.password, id).use { body ->
                body.byteStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            }
            if (scope != thumbnailNamespace() || !canUseStudio()) throw CancellationException("Preview context changed")
        }
    }
    suspend fun studioPreview(book: StudioBookDto, page: Int = 0): Bitmap? =
        if (!canUseStudio()) null else book.designAssetId?.let { renderStudioAsset(it, page) }

    /** El histórico sigue disponible aun con Studio apagado; la red no bloquea las listas. */
    suspend fun capturePreview(capture: Capture, widthPx: Int = 1000): Bitmap? = withContext(Dispatchers.IO) {
        val instance = capture.bookInstanceId ?: return@withContext null
        val scope = thumbnailNamespace()
        try {
            val s = settings.value
            val key = "capture-design:$instance"
            val legacy = buildList {
                graph.repo.studio.designAsset(instance)?.let { add(legacyAsset(it)) }
                add(legacyAsset(key))
            }
            val file = previewAssets.file(privateAssetNamespace(), key, legacy) { temporary ->
                if (!hasServer() || (s.syncServerUrl.isNotBlank() && normalizedServerUrl(s.syncServerUrl) != normalizedServerUrl(s.serverUrl)))
                    throw IllegalStateException("No bound server")
                ApiFactory.create(s.serverUrl).captureDesign(s.password, capture.id).use { body ->
                    body.byteStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                }
                if (scope != thumbnailNamespace()) throw CancellationException("Preview context changed")
            }
            val bitmap = previewAssets.render(file, capture.page, widthPx)
            if (scope != thumbnailNamespace()) throw CancellationException("Preview context changed")
            bitmap
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { null }
    }
    private suspend fun renderStudioAsset(id: String, index: Int): Bitmap? = withContext(Dispatchers.IO) {
        try { previewAssets.render(studioAssetFile(id), index, 1000) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { invalidateStudioOnRejection(error); null }
    }
    suspend fun downloadStudioBook(book: StudioBookDto, recipe: Boolean = false): File? = withContext(Dispatchers.IO) {
        if (!canUseStudio()) return@withContext null
        runCatching {
            val s = settings.value
            val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "book-${book.id.filter { it.isLetterOrDigit() || it == '-' }}.${if (recipe) "json" else "pdf"}")
            val body = if (recipe) ApiFactory.create(s.serverUrl).studioBookRecipe(s.password, book.id) else ApiFactory.create(s.serverUrl).studioBookPdf(s.password, book.id)
            body.use { it.byteStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } } }
            file
        }.onFailure { invalidateStudioOnRejection(it); _status.value = it.message }.getOrNull()
    }


    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    private val _initialSettings = MutableStateFlow<AppSettings?>(null)
    /** null hasta leer los ajustes reales; nunca decide el wizard con valores provisionales. */
    val initialSettings: StateFlow<AppSettings?> = _initialSettings.asStateFlow()

    private val _notebooksLoaded = MutableStateFlow(false)
    val notebooksLoaded: StateFlow<Boolean> = _notebooksLoaded.asStateFlow()
    val notebooks: StateFlow<List<Notebook>> =
        graph.repo.notebooks().onEach { _notebooksLoaded.value = true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        graph.repo.supersededWritingPages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun recentSummaries(limit: Int, clearedAt: String) = graph.repo.recentSummaries(limit, clearedAt)
    fun searchCaptureSummaries(query: String) = graph.repo.searchCaptureSummaries(query)
    fun captureSummariesIn(notebookId: String) = graph.repo.captureSummariesIn(notebookId)
    fun expandedSummaries(ids: Set<String>): Flow<Map<String, List<net.micapum.neopen.app.data.CaptureSummary>>> =
        if (ids.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyMap()) else kotlinx.coroutines.flow.combine(
            ids.sorted().map { id -> graph.repo.captureSummariesIn(id).map { id to it } },
        ) { it.toMap() }

    val pending: StateFlow<Int> =
        graph.repo.pendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val offlineImportWarnings: StateFlow<List<OfflineImportWarning>> =
        graph.repo.offlineImportWarnings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingOfflineImportWarnings: StateFlow<Int> =
        graph.repo.pendingOfflineImportWarningCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Marcas de última sincronización (boli/servidor) para mostrar en Ajustes → Servidor. */
    val syncStatus: StateFlow<SyncStatus> =
        graph.syncStatus.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncStatus())

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
        viewModelScope.launch(Dispatchers.IO) {
            _syncing.value = true
            val r = graph.sync.sync()
            _syncing.value = false
            _status.value = if (r.ok) null else syncError(r.error)
        }
    }

    private fun syncError(error: String?): String? = when (error) {
        SyncEngine.SERVER_CHANGE_BLOCKED -> getApplication<Application>().getString(R.string.vm_server_change_blocked)
        SyncEngine.STUDIO_SUSPENDED -> getApplication<Application>().getString(R.string.studio_server_suspended)
        else -> error
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
     * servidor (ids estables, sin fusionar revisiones por SOBP) y pasa
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
            val migrated = runCatching {
                val result = graph.sync.linkAndMerge(clean, password)
                check(result.ok) { result.error ?: "Error de sincronización" }
            }
            if (migrated.isFailure) {
                onResult(false, getApplication<Application>().getString(R.string.vm_migration_failed, migrated.exceptionOrNull()?.message ?: ""))
                return@launch
            }
            graph.syncNow()
            onResult(true, null)
        }
    }

    /**
     * Asegura el servicio en primer plano que hospeda la notificación de control (y la conexión
     * en segundo plano). Antes solo se arrancaba al *Guardar* ajustes, así que la notificación
     * "permanente" no reaparecía al reabrir la app tras matar el proceso o reiniciar. Lo llama
     * la UI al abrir la app si la opción está activada. Idempotente (re-entrega onStartCommand).
     */
    fun ensurePenService() {
        PenService.start(getApplication())
    }

    /** Guarda los ajustes, aplica el calendario de sync y arranca/para el servicio BLE. */
    fun saveSettings(s: AppSettings) {
        viewModelScope.launch {
            val result = graph.sync.updateSettings(s)
            if (!result.ok) {
                _status.value = syncError(result.error)
                return@launch
            }
            graph.applySyncSchedule(s.syncMode, s.syncIntervalMin)
            val ctx = getApplication<Application>()
            // El servicio en primer plano se necesita para el segundo plano O para mostrar la
            // notificación de control persistente.
            if (s.backgroundEnabled || s.controlNotification) PenService.start(ctx) else PenService.stop(ctx)
        }
    }

    /**
     * Importa a la config LOCAL (teléfono) la configuración del servidor (GET /api/settings).
     * Solo lo no-secreto: URLs, modelo, usuarios y carpetas base. Las API keys y contraseñas
     * NO se exponen por el servidor, así que se preservan las locales. `onResult(true)` si fue ok.
     */
    fun importServerConfig(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val s = settings.value
            val dto = runCatching { ApiFactory.create(s.serverUrl).serverSettings(s.password) }.getOrNull()
            if (dto == null) { onResult(false); return@launch }
            val newS = s.copy(
                ocrLocal = s.ocrLocal.copy(
                    baseUrl = dto.ocr?.baseUrl?.value?.ifBlank { null } ?: s.ocrLocal.baseUrl,
                    model = dto.ocr?.model?.value?.ifBlank { null } ?: s.ocrLocal.model,
                ),
                ltLocal = s.ltLocal.copy(
                    baseUrl = dto.languageTool?.baseUrl?.value?.ifBlank { null } ?: s.ltLocal.baseUrl,
                    username = dto.languageTool?.username?.value?.ifBlank { null } ?: s.ltLocal.username,
                ),
                webdavLocal = s.webdavLocal.copy(
                    url = dto.webdav?.url?.value?.ifBlank { null } ?: s.webdavLocal.url,
                    username = dto.webdav?.username?.value?.ifBlank { null } ?: s.webdavLocal.username,
                    basePath = dto.webdav?.basePath?.value?.ifBlank { null } ?: s.webdavLocal.basePath,
                ),
                ftpLocal = s.ftpLocal.copy(
                    url = dto.ftp?.url?.value?.ifBlank { null } ?: s.ftpLocal.url,
                    username = dto.ftp?.username?.value?.ifBlank { null } ?: s.ftpLocal.username,
                    basePath = dto.ftp?.basePath?.value?.ifBlank { null } ?: s.ftpLocal.basePath,
                ),
            )
            graph.settings.update(newS)
            onResult(true)
        }
    }

    // --- Backup (copia completa del servidor) ---

    /** Copia ZIP transmitida directamente a DocumentProvider sin cargarla en RAM. */
    suspend fun exportBackup(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.value
            ApiFactory.create(s.serverUrl).exportData(s.password).use { body ->
                body.byteStream().use { input -> getApplication<Application>().contentResolver.openOutputStream(uri)?.use { input.copyTo(it) } ?: error("Sin destino") }
            }
        }.isSuccess
    }
    /** Mantiene la importación JSON antigua y detecta ZIP por su firma, no por extensión. */
    suspend fun restoreBackup(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val dir = getApplication<Application>().cacheDir
        val file = File.createTempFile("restore", ".backup", dir)
        try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return@withContext false
            val zip = file.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
            val s = settings.value
            val ok = ApiFactory.create(s.serverUrl).importData(s.password, file.asRequestBody((if (zip) "application/zip" else "application/json").toMediaType())).isSuccessful
            if (ok) syncNow()
            ok
        } catch (_: Exception) { false } finally { file.delete() }
    }

    fun logout() {
        viewModelScope.launch {
            graph.sync.logout()
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
    val penConnectionStatus = graph.pen.state.map { it.status }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), graph.pen.state.value.status)
    val pressureStyle = settings.map { it.effectivePressureWidthEnabled to it.effectivePressureWidthContrast }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false to 2f)
    val online: StateFlow<Boolean> = graph.online
    /** Registro de diagnóstico BLE (panel de debug). */
    val penLogs: StateFlow<List<String>> = graph.pen.logs

    fun penReady(): Boolean = graph.pen.isReady
    fun connectPen() = graph.pen.scanAndConnect()
    fun disconnectPen() = graph.pen.disconnect()
    fun stopConnecting() {
        graph.pen.setForegroundReconnect(false)
        graph.pen.disconnect()
    }
    fun clearLogs() = graph.pen.clearLogs()

    fun startForegroundPenReconnect() {
        val cur = graph.currentSettings
        val hasTarget = cur.lockedPenMac.isNotBlank() || cur.knownPens.any { it.mac !in cur.forgottenPens }
        if (hasTarget && hasBlePermission()) graph.pen.setForegroundReconnect(true)
    }

    fun stopForegroundPenReconnect() {
        graph.pen.setForegroundReconnect(false)
    }

    private fun hasBlePermission(): Boolean {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Ajustes del boli
    fun setAutoPowerOn(on: Boolean) = graph.pen.setAutoPowerOn(on)
    fun setBeep(on: Boolean) = graph.pen.setBeep(on)
    fun setOfflineData(on: Boolean) = graph.pen.setOfflineData(on)
    fun setAutoShutdown(min: Int) = graph.pen.setAutoShutdown(min)
    fun setSensitivity(level: Int) = graph.pen.setSensitivity(level)

    // Contraseña / PIN del boli
    fun unlockPen(pin: String, remember: Boolean) = graph.pen.unlockPen(pin, remember)
    fun setOrChangePenPassword(current: String, new: String) = graph.pen.setOrChangePenPassword(current, new)
    fun removePenPassword(current: String) = graph.pen.removePenPassword(current)
    /** ¿Hay PIN recordado para el boli (por MAC)? Para mostrar "Olvidar PIN". */
    fun hasSavedPin(mac: String?): Boolean =
        mac != null && graph.currentSettings.knownPens.any { it.mac == mac && it.pin.isNotEmpty() }
    /** Olvida el PIN recordado del boli. */
    fun forgetPin(mac: String?) { mac?.let { graph.pen.onPinRemembered(it, null) } }

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

    // --- OCR (teléfono / servidor según el conmutador de capacidades) ---

    fun transcribe(captureId: String) {
        viewModelScope.launch {
            when (graph.dispatcher.runOcr(captureId)) {
                is OcrOutcome.Failed -> _status.value = getApplication<Application>().getString(R.string.vm_ocr_failed)
                OcrOutcome.Skipped -> _status.value = getApplication<Application>().getString(R.string.vm_ocr_off)
                OcrOutcome.Applied -> Unit // el Flow de Room refresca la UI
            }
        }
    }

    // --- Exportar (genera los bytes; la UI los comparte o guarda) ---

    suspend fun exportPage(captureId: String, format: ExportFormat): ExportFile? {
        val cap = graph.repo.capture(captureId) ?: return null
        val bytes = withContext(Dispatchers.IO) {
            val s = settings.value
            when (format) {
                ExportFormat.PDF -> renderPagePdf(cap.strokes, cap.paper, s.effectivePressureWidthEnabled, s.effectivePressureWidthContrast)
                ExportFormat.PNG -> rasterizePng(cap.strokes, cap.paper, pressureWidthEnabled = s.effectivePressureWidthEnabled, pressureWidthContrast = s.effectivePressureWidthContrast)
                ExportFormat.SVG -> renderSvg(cap.strokes, cap.paper, s.effectivePressureWidthEnabled, s.effectivePressureWidthContrast).toByteArray()
            }
        }
        return ExportFile("${fileBaseName(cap.name)}.${format.ext}", format.mime, bytes)
    }

    suspend fun exportNotebookPdf(notebookId: String): ExportFile? {
        val nb = graph.repo.notebook(notebookId) ?: return null
        val pages = graph.repo.capturesIn(notebookId).first()
        val bytes = withContext(Dispatchers.IO) {
            val s = settings.value
            renderNotebookPdf(pages, s.effectivePressureWidthEnabled, s.effectivePressureWidthContrast)
        }
        return ExportFile("${fileBaseName(nb.name)}.pdf", ExportFormat.PDF.mime, bytes)
    }

    // --- Fondos (plantilla detrás de los trazos) ------------------------------


    private val _recents = MutableStateFlow<List<StudioItemDto>>(emptyList())
    val recents: StateFlow<List<StudioItemDto>> = _recents.asStateFlow()

    private val _presets = MutableStateFlow<List<StudioItemDto>>(emptyList())
    val presets: StateFlow<List<StudioItemDto>> = _presets.asStateFlow()

    /** ¿Hay servidor con el que sincronizar fondos? (no estamos en modo local). */
    fun hasServer(): Boolean =
        settings.value.appMode != AppMode.LOCAL && settings.value.serverUrl.isNotBlank()

    private var studioChecks: Job? = null
    fun startStudioChecks() {
        if (studioChecks?.isActive == true) return
        graph.sync.studioAccess.invalidate()
        studioChecks = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) { graph.sync.refreshCapabilities(); delay(30_000) }
        }
    }
    fun stopStudioChecks() {
        studioChecks?.cancel(); studioChecks = null
        graph.sync.studioAccess.invalidate()
    }
    init {
        viewModelScope.launch {
            graph.settings.flow.collect { loaded ->
                _settings.value = loaded
                _initialSettings.value = loaded
            }
        }
        viewModelScope.launch { studioEnabled.collect { enabled ->
            if (!enabled) { _recents.value = emptyList(); _presets.value = emptyList() }
        } }
    }
    /** La respuesta del servidor actual es la única fuente de habilitación. */
    fun refreshStudio() {
        viewModelScope.launch {
            graph.sync.refreshCapabilities()
            if (!canUseStudio()) return@launch
            val s = settings.value
            val api = ApiFactory.create(s.serverUrl)
            try {
                val recents = api.listRecents(s.password)
                if (!canUseStudio()) return@launch
                val presets = api.listPresets(s.password)
                if (canUseStudio() && s.serverUrl == settings.value.serverUrl) {
                    _recents.value = recents; _presets.value = presets
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { graph.sync.studioAccess.invalidate() }
        }
    }

    fun observeSheetBackground(bookKey: String, page: Int, bookInstanceId: String? = null) = graph.repo.observeSheetBackground(bookKey, page, bookInstanceId)

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
        if (choice is ResolvedBg.ServerUrl && !canUseStudio()) return
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

    // --- Estilo de trazo por boli ---

    /** Fija el estilo (color/grueso) de un boli; lo registra si aún no estaba. */
    fun setPenStyle(mac: String, style: StrokeStyle) {
        viewModelScope.launch {
            val cur = settings.value
            val pens = if (cur.knownPens.any { it.mac == mac })
                cur.knownPens.map { if (it.mac == mac) it.copy(style = style) else it }
            else cur.knownPens + KnownPen(mac, mac, style)
            graph.settings.update(cur.copy(knownPens = pens))
        }
    }

    fun setDefaultStyle(style: StrokeStyle) {
        viewModelScope.launch { graph.settings.update(settings.value.copy(defaultStyle = style)) }
    }

    fun setPressureWidthEnabled(enabled: Boolean) {
        viewModelScope.launch { graph.settings.update(settings.value.copy(pressureWidthEnabled = enabled)) }
    }

    fun setPressureWidthContrast(contrast: Float) {
        viewModelScope.launch { graph.settings.update(settings.value.copy(pressureWidthContrast = contrast.coerceIn(0.5f, 3f))) }
    }

    fun setOfflineReimportPolicy(policy: OfflineReimportPolicy) {
        viewModelScope.launch { graph.settings.update(settings.value.copy(offlineReimportPolicy = policy)) }
    }

    fun setLockedPen(mac: String?) {
        viewModelScope.launch {
            val cur = settings.value
            graph.settings.update(cur.copy(lockedPenMac = mac.orEmpty()))
        }
    }

    fun renamePen(mac: String, alias: String) {
        viewModelScope.launch {
            val cur = settings.value
            graph.settings.update(cur.copy(knownPens = cur.knownPens.map { if (it.mac == mac) it.copy(alias = alias) else it }))
        }
    }

    fun forgetPen(mac: String) {
        viewModelScope.launch {
            val cur = settings.value
            val locked = if (cur.lockedPenMac == mac) "" else cur.lockedPenMac
            graph.settings.update(
                cur.copy(
                    knownPens = cur.knownPens.filterNot { it.mac == mac },
                    forgottenPens = (cur.forgottenPens + mac).distinct(),
                    lockedPenMac = locked,
                ),
            )
            if (penState.value.mac == mac) graph.pen.disconnect()
        }
    }

    /** Oculta lo actual de "Últimas ediciones"; una edición posterior lo trae de vuelta. */
    fun clearRecent() {
        viewModelScope.launch { graph.settings.update(settings.value.copy(recentClearedAt = nowIso())) }
    }

    fun discardOfflineImportWarning(id: String) {
        viewModelScope.launch { graph.repo.discardOfflineImportWarning(id) }
    }

    fun importOfflineWarningAsNewCapture(id: String) {
        viewModelScope.launch {
            graph.repo.importOfflineWarningAsNewCapture(id)
            if (hasServer()) graph.syncNow()
        }
    }

    fun mergeOfflineImportWarning(id: String) {
        viewModelScope.launch {
            val merged = graph.repo.mergeOfflineImportWarning(id)
            if (merged && hasServer()) graph.syncNow()
            if (!merged) _status.value = getApplication<Application>().getString(R.string.vm_offline_reimport_merge_failed)
        }
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
                if (supersededSobps.value.contains(c.writingPageKey) || c.bookInstanceId in closedStudioInstances.value) return@launch
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
