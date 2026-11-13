package net.micapum.neopen.app.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.micapum.neopen.app.data.Json
import net.micapum.neopen.app.data.LocalChangesPendingException
import net.micapum.neopen.app.data.NeopenRepository
import net.micapum.neopen.app.data.OutboxKind
import net.micapum.neopen.app.data.StudioOutbox
import net.micapum.neopen.app.data.StudioJson
import net.micapum.neopen.app.db.OutboxEntity
import net.micapum.neopen.core.data.ApiFactory
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.NeopenApi
import net.micapum.neopen.core.data.SettingsStore
import net.micapum.neopen.core.data.normalizedServerUrl
import retrofit2.HttpException
import retrofit2.Response
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Outbox FIFO serializado entre workers y UI; nunca descarta una escritura rechazada. */
class SyncEngine(
    private val repo: NeopenRepository,
    private val settings: SettingsStore,
    private val apiFactory: (String) -> NeopenApi = { ApiFactory.create(it) },
) {
    private val mutex = Mutex()
    private data class ServerIdentity(val url: String, val password: String, val mode: AppMode, val binding: String)
    private fun AppSettings.identity() = ServerIdentity(normalizedServerUrl(serverUrl), password, appMode, normalizedServerUrl(syncServerUrl))
    private val capabilitiesRequests = InFlightRequests<ServerIdentity, Unit>()
    private val syncRequests = InFlightRequests<ServerIdentity, Result>()
    val studioAccess = StudioServerAccess()
    val studioEnabled = studioAccess.state.combine(settings.flow) { _, s -> studioAccess.allowedFor(s) }

    /** Revalidado al volver al primer plano y antes de cada sincronización. */
    suspend fun refreshCapabilities() {
        val s = settings.flow.first()
        if (s.appMode == AppMode.LOCAL || !s.configured || (s.syncServerUrl.isNotBlank() && normalizedServerUrl(s.syncServerUrl) != normalizedServerUrl(s.serverUrl))) { studioAccess.invalidate(); return }
        capabilitiesRequests.run(s.identity()) {
            // Un waiter puede retomar una consulta cuyo propietario se canceló.
            // No inicia otra petición con credenciales que ya hayan cambiado.
            if (settings.flow.first().identity() != s.identity()) {
                studioAccess.invalidate()
                return@run
            }
            try {
                val enabled = apiFactory(s.serverUrl).capabilitiesInfo(s.password).studio?.enabled == true
                val current = settings.flow.first()
                if (current.identity() != s.identity()) studioAccess.invalidate()
                else studioAccess.confirmed(s, enabled)
            } catch (e: CancellationException) { studioAccess.invalidate(); throw e
            } catch (_: Exception) { studioAccess.invalidate() }
        }
    }
    data class Result(
        val ok: Boolean,
        val pulled: Int = 0,
        val pushed: Int = 0,
        val error: String? = null,
        internal val deferredByLocalWrite: Boolean = false,
    )

    companion object {
        const val SERVER_CHANGE_BLOCKED = "server-change-blocked"
        const val STUDIO_SUSPENDED = "studio-server-not-enabled"
    }

    /** Con datos existentes, cambiar de servidor requiere cerrar la biblioteca explícitamente. */
    suspend fun updateSettings(next: AppSettings): Result = mutex.withLock {
        repo.withPenIngestionPaused {
            val old = settings.flow.first()
            val previous = normalizedServerUrl(old.syncServerUrl.ifBlank { old.serverUrl })
            val target = normalizedServerUrl(next.serverUrl)
            val changing = next.appMode != AppMode.LOCAL && previous.isNotBlank() && previous != target
            if (changing) {
                if (repo.hasLibraryOrPendingChanges()) return@withPenIngestionPaused Result(false, error = SERVER_CHANGE_BLOCKED)
                repo.clearLocal()
            }
            // Vincular primero: una interrupción nunca permite usar el cursor antiguo en otro servidor.
            if (next.appMode != AppMode.LOCAL && target.isNotBlank()) settings.bindSyncServer(target)
            if (normalizedServerUrl(old.serverUrl) != target || old.password != next.password || next.appMode == AppMode.LOCAL) studioAccess.invalidate()
            settings.update(if (changing) next.copy(serverPressureWidthEnabled = null, serverPressureWidthContrast = null) else next)
            Result(true)
        }
    }

    suspend fun logout() = mutex.withLock {
        studioAccess.invalidate()
        repo.withPenIngestionPaused {
            repo.clearLocal()
            settings.update(AppSettings())
            settings.bindSyncServer("")
        }
    }

    suspend fun sync(): Result = syncRequests.run(settings.flow.first().identity()) {
        mutex.withLock {
            var result = syncUnlocked()
            // La UI y el worker comparten la pasada. Una edición que llega después
            // del push inicial exige otra vuelta, no otro worker duplicado al abrir.
            while ((result.ok || result.deferredByLocalWrite) && settings.flow.first().let { it.appMode != AppMode.LOCAL && it.configured } && repo.outboxPeek(1).isNotEmpty()) {
                val next = syncUnlocked()
                result = next.copy(pulled = result.pulled + next.pulled, pushed = result.pushed + next.pushed)
            }
            result
        }
    }

    private suspend fun syncUnlocked(): Result {
        refreshCapabilities()
        val p = pushUnlocked()
        // Bajar sobre una escritura pendiente podría borrar la edición optimista.
        if (!p.ok) {
            val waiting = repo.outboxPeek(1).firstOrNull()
            if (waiting?.kind?.startsWith("studio") == true || waiting?.lastError?.contains("conflictId") == true) refreshStudioUnlocked()
            return p
        }
        val pulled = pullUnlocked().copy(pushed = p.pushed)
        return if (pulled.ok && p.error != null) pulled.copy(ok = false, error = p.error) else pulled
    }

    suspend fun push(): Result = mutex.withLock { refreshCapabilities(); val result = pushUnlocked(); if (result.error != null) result.copy(ok = false) else result }
    suspend fun pull(): Result = mutex.withLock { refreshCapabilities(); pullUnlocked() }

    /** Única excepción explícita al modo LOCAL: la vinculación pedida por el usuario. */
    suspend fun linkAndMerge(serverUrl: String, password: String): Result = mutex.withLock {
        repo.withPenIngestionPaused {
            repo.prepareLinkMigration(serverUrl)
            val result = drain(apiFactory(serverUrl), password, serverUrl, linking = true)
            if (result.ok) {
                repo.resetCursor()
                settings.bindSyncServer(serverUrl)
                // La ingesta pendiente relee este modo al liberar el gate y usa el outbox.
                settings.update(settings.flow.first().copy(serverUrl = serverUrl, password = password, appMode = AppMode.FULL))
            }
            result
        }
    }

    /** También protege contra cambios de URL hechos fuera del formulario de Ajustes. */
    private suspend fun ensureServer(s: AppSettings): Boolean {
        val target = normalizedServerUrl(s.serverUrl)
        val bound = normalizedServerUrl(s.syncServerUrl)
        if (bound.isNotBlank() && bound != target) {
            if (repo.hasLibraryOrPendingChanges()) return false
            return repo.withPenIngestionPaused {
                if (repo.hasLibraryOrPendingChanges()) return@withPenIngestionPaused false
                repo.clearLocal()
                settings.bindSyncServer(target)
                settings.update(settings.flow.first().copy(serverPressureWidthEnabled = null, serverPressureWidthContrast = null))
                true
            }
        } else if (bound.isBlank()) settings.bindSyncServer(target)
        return true
    }

    private suspend fun pullUnlocked(): Result {
        val s = settings.flow.first()
        if (s.appMode == AppMode.LOCAL) return Result(true)
        if (!s.configured) return Result(false, error = "Sin servidor configurado")
        if (!ensureServer(s)) return Result(false, error = SERVER_CHANGE_BLOCKED)
        if (repo.outboxPeek(Int.MAX_VALUE).any { !StudioOutbox.requiresStudio(it.kind) }) return Result(false, error = "Hay cambios pendientes de subir", deferredByLocalWrite = true)
        val api = apiFactory(s.serverUrl)
        var pulled = 0
        return try {
            while (true) {
                if (settings.flow.first().appMode == AppMode.LOCAL) return Result(true, pulled = pulled)
                val cursor = repo.cursor()
                val resp = api.changes(s.password, since = cursor, limit = 200)
                if (normalizedServerUrl(settings.flow.first().serverUrl) != normalizedServerUrl(s.serverUrl)) {
                    return Result(false, pulled = pulled, error = SERVER_CHANGE_BLOCKED)
                }
                repo.applyChanges(resp, System.currentTimeMillis())
                pulled += resp.captures.size + resp.notebooks.size
                if (!resp.hasMore) break
                check(resp.cursor != cursor || resp.reset) { "El cursor del servidor no avanza" }
            }
            if (settings.flow.first().appMode != AppMode.LOCAL) {
                optional { repo.applyServerNotebook(api.unclassifiedNotebook(s.password)) }
                optional { repo.applyServerAnchors(api.listAnchors(s.password)) }
                if (studioAccess.allowedFor(settings.flow.first())) refreshStudioUnlocked()
                else optional { repo.studio.replacePens(api.pens(s.password).pens) }
                optional {
                    val rs = api.renderSettings(s.password)
                    settings.update(settings.flow.first().copy(
                        serverPressureWidthEnabled = rs.pressureWidthEnabled,
                        serverPressureWidthContrast = rs.pressureWidthContrast,
                    ))
                }
            }
            Result(true, pulled = pulled)
        } catch (e: LocalChangesPendingException) { Result(false, pulled = pulled, error = e.message, deferredByLocalWrite = true)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Result(false, pulled = pulled, error = e.message ?: e.javaClass.simpleName) }
    }

    /** Incluso con conflicto del outbox, actualiza los destinos confirmados para resolverlo. */
    private suspend fun refreshStudioUnlocked() {
        val s = settings.flow.first()
        if (!studioAccess.allowedFor(s)) return
        optional {
            val api = apiFactory(s.serverUrl)
            repo.studio.apply(api.studioBooks(s.password), api.pens(s.password).pens, api.studioProfiles(s.password))
            optional { repo.studio.applyConflicts(api.studioCaptureConflicts(s.password)) }
        }
    }

    private suspend fun optional(action: suspend () -> Unit) {
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (e is HttpException && e.code() == 403 && e.response()?.errorBody()?.string()?.contains("STUDIO_DISABLED") == true) studioAccess.invalidate()
        }
    }

    private suspend fun pushUnlocked(): Result {
        val s = settings.flow.first()
        if (s.appMode == AppMode.LOCAL) return Result(true)
        if (!s.configured) return Result(false, error = "Sin servidor configurado")
        if (!ensureServer(s)) return Result(false, error = SERVER_CHANGE_BLOCKED)
        return drain(apiFactory(s.serverUrl), s.password, s.serverUrl)
    }

    private suspend fun drain(api: NeopenApi, pw: String, serverUrl: String, linking: Boolean = false): Result {
        var pushed = 0
        var suspended = false
        while (true) {
            if (!linking && settings.flow.first().appMode == AppMode.LOCAL) return Result(true, pushed = pushed)
            if (!linking && normalizedServerUrl(settings.flow.first().serverUrl) != normalizedServerUrl(serverUrl)) {
                return Result(false, pushed = pushed, error = SERVER_CHANGE_BLOCKED)
            }
            // Releer tras CADA respuesta: el CREATE anterior puede repuntar ids y payloads.
            val all = repo.outboxPeek(Int.MAX_VALUE)
            val allowed = studioAccess.allowedFor(settings.flow.first())
            val paused = if (allowed) emptyList() else all.filter { StudioOutbox.requiresStudio(it.kind) }
            suspended = suspended || paused.isNotEmpty()
            paused.filter { it.lastError != STUDIO_SUSPENDED }.forEach { repo.outboxMarkFailed(it.localId, STUDIO_SUSPENDED) }
            val queued = if (allowed) all else all.filterNot { StudioOutbox.requiresStudio(it.kind) }
            // Una resolución puede desbloquear un CREATE retenido por identidad/asignación.
            val first = queued.firstOrNull() ?: break
            val op = if (first.kind == OutboxKind.CREATE_CAPTURE && first.lastError?.contains("conflictId") == true)
                queued.firstOrNull { it.kind == StudioOutbox.RESOLVE || it.kind == StudioOutbox.ALIAS || it.kind == StudioOutbox.REGISTER } ?: first else first
            val retryAt = op.lastError?.substringAfter("; retryAt=", "")?.toLongOrNull()
            if (retryAt != null && retryAt > System.currentTimeMillis()) return Result(false, pushed = pushed, error = op.lastError)
            try {
                runOp(api, pw, serverUrl, op)
                pushed++
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val body = if (e is HttpException) e.response()?.errorBody()?.string() else null
                if (StudioOutbox.requiresStudio(op.kind) && e is HttpException && e.code() == 403 && body?.contains("STUDIO_DISABLED") == true) {
                    studioAccess.invalidate()
                    repo.outboxMarkFailed(op.localId, STUDIO_SUSPENDED)
                    suspended = true
                    continue
                }
                val error = if (e is HttpException) {
                    val retry = e.response()?.headers()?.get("Retry-After")?.let { value ->
                        value.toLongOrNull()?.takeIf { it >= 0 }?.let { System.currentTimeMillis() + it.coerceAtMost(86400L * 365) * 1000 }
                            ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    }
                    "HTTP ${e.code()}" + (body?.take(1500)?.let { ": $it" } ?: "") + (retry?.let { "; retryAt=$it" } ?: "")
                } else e.message ?: e.javaClass.simpleName
                repo.outboxMarkFailed(op.localId, error)
                return Result(false, pushed = pushed, error = error)
            }
        }
        if (pushed > 0) repo.markPushed(System.currentTimeMillis())
        return Result(true, pushed = pushed, error = if (suspended) STUDIO_SUSPENDED else null)
    }

    private suspend fun runOp(api: NeopenApi, pw: String, serverUrl: String, op: OutboxEntity) {
        when (op.kind) {
            StudioOutbox.REGISTER -> { repo.studio.confirmedPen(api.registerPen(pw, requireNotNull(StudioJson.register.fromJson(op.payloadJson)))); repo.acknowledge(op) }
            StudioOutbox.RESOLVE -> {
                val result = api.resolveStudioConflict(pw, requireNotNull(op.targetId), requireNotNull(StudioJson.resolve.fromJson(op.payloadJson)))
                repo.confirmStudioConflict(result)
                repo.acknowledge(op)
                refreshStudioUnlocked()
            }
            StudioOutbox.PATCH -> repo.studio.confirmed(api.patchStudioBook(pw, requireNotNull(op.targetId), requireNotNull(StudioJson.patch.fromJson(op.payloadJson))), op)
            StudioOutbox.TRANSFER -> {
                repo.studio.confirmed(api.transferStudioBook(pw, requireNotNull(op.targetId), requireNotNull(StudioJson.transfer.fromJson(op.payloadJson))), op)
                refreshStudioUnlocked()
            }
            StudioOutbox.DELETE -> { checkResponse(api.deleteStudioBook(pw, requireNotNull(op.targetId), op.payloadJson.toLong()), deleting = true); repo.studio.confirmedDeleted(requireNotNull(op.targetId), op); refreshStudioUnlocked() }
            StudioOutbox.ALIAS -> { api.linkPenAlias(pw, requireNotNull(op.targetId), requireNotNull(StudioJson.alias.fromJson(op.payloadJson))); repo.acknowledge(op); refreshStudioUnlocked() }

            OutboxKind.PATCH_CAPTURE -> repo.acknowledge(op, capture = api.patchCapture(pw, requireNotNull(op.targetId), requireNotNull(Json.capturePatch.fromJson(op.payloadJson))))
            OutboxKind.PUSH_TRANSCRIPTION -> repo.acknowledge(op, capture = api.putTranscription(pw, requireNotNull(op.targetId), requireNotNull(Json.transcriptionPush.fromJson(op.payloadJson))))
            OutboxKind.CREATE_CAPTURE -> repo.acknowledge(op, capture = api.createCapture(pw, requireNotNull(Json.createCapture.fromJson(op.payloadJson))))
            OutboxKind.CREATE_NOTEBOOK -> repo.acknowledge(op, notebook = api.createNotebook(pw, requireNotNull(Json.notebookCreate.fromJson(op.payloadJson))))
            OutboxKind.PATCH_NOTEBOOK -> repo.acknowledge(op, notebook = api.patchNotebook(pw, requireNotNull(op.targetId), requireNotNull(Json.notebookPatch.fromJson(op.payloadJson))))
            OutboxKind.DELETE_CAPTURE -> { checkResponse(api.deleteCapture(pw, requireNotNull(op.targetId)), deleting = true); repo.acknowledge(op) }
            OutboxKind.DELETE_NOTEBOOK -> { checkResponse(api.deleteNotebook(pw, requireNotNull(op.targetId)), deleting = true); repo.acknowledge(op) }
            OutboxKind.REORDER_NOTEBOOK -> { checkResponse(api.reorderNotebook(pw, requireNotNull(op.targetId), requireNotNull(Json.reorder.fromJson(op.payloadJson)))); repo.acknowledge(op) }
            OutboxKind.MIGRATE -> {
                check(op.targetId?.let(::normalizedServerUrl) == normalizedServerUrl(serverUrl)) { "La migración pendiente pertenece a otro servidor" }
                checkResponse(api.migrate(pw, requireNotNull(Json.migration.fromJson(op.payloadJson))))
                repo.acknowledge(op)
            }
            else -> error("Operación pendiente desconocida: ${op.kind}")
        }
    }

    private fun checkResponse(resp: Response<Unit>, deleting: Boolean = false) {
        if (!resp.isSuccessful && !(deleting && resp.code() == 404)) throw HttpException(resp)
    }
}
