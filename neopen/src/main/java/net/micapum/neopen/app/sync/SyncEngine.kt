package net.micapum.neopen.app.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.micapum.neopen.app.data.Json
import net.micapum.neopen.app.data.NeopenRepository
import net.micapum.neopen.app.data.OutboxKind
import net.micapum.neopen.app.db.OutboxEntity
import net.micapum.neopen.core.data.ApiFactory
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.core.data.NeopenApi
import net.micapum.neopen.core.data.SettingsStore
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
    data class Result(val ok: Boolean, val pulled: Int = 0, val pushed: Int = 0, val error: String? = null)

    suspend fun sync(): Result = mutex.withLock {
        val p = pushUnlocked()
        // Bajar sobre una escritura pendiente podría borrar la edición optimista.
        if (!p.ok) return@withLock p
        pullUnlocked().copy(pushed = p.pushed)
    }

    suspend fun push(): Result = mutex.withLock { pushUnlocked() }
    suspend fun pull(): Result = mutex.withLock { pullUnlocked() }

    /** Única excepción explícita al modo LOCAL: la vinculación pedida por el usuario. */
    suspend fun linkAndMerge(serverUrl: String, password: String): Result = mutex.withLock {
        repo.prepareLinkMigration(serverUrl)
        val result = drain(apiFactory(serverUrl), password, serverUrl, linking = true)
        if (result.ok) repo.resetCursor()
        result
    }

    private suspend fun pullUnlocked(): Result {
        val s = settings.flow.first()
        if (s.appMode == AppMode.LOCAL) return Result(true)
        if (!s.configured) return Result(false, error = "Sin servidor configurado")
        if (repo.outboxPeek(1).isNotEmpty()) return Result(false, error = "Hay cambios pendientes de subir")
        val api = apiFactory(s.serverUrl)
        var pulled = 0
        return try {
            while (true) {
                if (settings.flow.first().appMode == AppMode.LOCAL) return Result(true, pulled = pulled)
                val cursor = repo.cursor()
                val resp = api.changes(s.password, since = cursor, limit = 200)
                repo.applyChanges(resp, System.currentTimeMillis())
                pulled += resp.captures.size + resp.notebooks.size
                if (!resp.hasMore) break
                check(resp.cursor != cursor || resp.reset) { "El cursor del servidor no avanza" }
            }
            if (settings.flow.first().appMode != AppMode.LOCAL) {
                optional { repo.applyServerNotebook(api.unclassifiedNotebook(s.password)) }
                optional { repo.applyServerAnchors(api.listAnchors(s.password)) }
                optional {
                    val rs = api.renderSettings(s.password)
                    settings.update(settings.flow.first().copy(
                        serverPressureWidthEnabled = rs.pressureWidthEnabled,
                        serverPressureWidthContrast = rs.pressureWidthContrast,
                    ))
                }
            }
            Result(true, pulled = pulled)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Result(false, pulled = pulled, error = e.message ?: e.javaClass.simpleName) }
    }

    private suspend fun optional(action: suspend () -> Unit) {
        try { action() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* siguiente pull */ }
    }

    private suspend fun pushUnlocked(): Result {
        val s = settings.flow.first()
        if (s.appMode == AppMode.LOCAL) return Result(true)
        if (!s.configured) return Result(false, error = "Sin servidor configurado")
        return drain(apiFactory(s.serverUrl), s.password, s.serverUrl)
    }

    private suspend fun drain(api: NeopenApi, pw: String, serverUrl: String, linking: Boolean = false): Result {
        var pushed = 0
        while (true) {
            if (!linking && settings.flow.first().appMode == AppMode.LOCAL) return Result(true, pushed = pushed)
            // Releer tras CADA respuesta: el CREATE anterior puede repuntar ids y payloads.
            val op = repo.outboxPeek(1).firstOrNull() ?: break
            val retryAt = op.lastError?.substringAfter("; retryAt=", "")?.toLongOrNull()
            if (retryAt != null && retryAt > System.currentTimeMillis()) return Result(false, pushed = pushed, error = op.lastError)
            try {
                runOp(api, pw, serverUrl, op)
                pushed++
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val error = if (e is HttpException) {
                    val retry = e.response()?.headers()?.get("Retry-After")?.let { value ->
                        value.toLongOrNull()?.takeIf { it >= 0 }?.let { System.currentTimeMillis() + it.coerceAtMost(86400L * 365) * 1000 }
                            ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    }
                    "HTTP ${e.code()}" + (retry?.let { "; retryAt=$it" } ?: "")
                } else e.message ?: e.javaClass.simpleName
                repo.outboxMarkFailed(op.localId, error)
                return Result(false, pushed = pushed, error = error)
            }
        }
        if (pushed > 0) repo.markPushed(System.currentTimeMillis())
        return Result(true, pushed = pushed)
    }

    private suspend fun runOp(api: NeopenApi, pw: String, serverUrl: String, op: OutboxEntity) {
        when (op.kind) {
            OutboxKind.PATCH_CAPTURE -> repo.acknowledge(op, capture = api.patchCapture(pw, requireNotNull(op.targetId), requireNotNull(Json.capturePatch.fromJson(op.payloadJson))))
            OutboxKind.PUSH_TRANSCRIPTION -> repo.acknowledge(op, capture = api.putTranscription(pw, requireNotNull(op.targetId), requireNotNull(Json.transcriptionPush.fromJson(op.payloadJson))))
            OutboxKind.CREATE_CAPTURE -> repo.acknowledge(op, capture = api.createCapture(pw, requireNotNull(Json.createCapture.fromJson(op.payloadJson))))
            OutboxKind.CREATE_NOTEBOOK -> repo.acknowledge(op, notebook = api.createNotebook(pw, requireNotNull(Json.notebookCreate.fromJson(op.payloadJson))))
            OutboxKind.PATCH_NOTEBOOK -> repo.acknowledge(op, notebook = api.patchNotebook(pw, requireNotNull(op.targetId), requireNotNull(Json.notebookPatch.fromJson(op.payloadJson))))
            OutboxKind.DELETE_CAPTURE -> { checkResponse(api.deleteCapture(pw, requireNotNull(op.targetId)), deleting = true); repo.acknowledge(op) }
            OutboxKind.DELETE_NOTEBOOK -> { checkResponse(api.deleteNotebook(pw, requireNotNull(op.targetId)), deleting = true); repo.acknowledge(op) }
            OutboxKind.REORDER_NOTEBOOK -> { checkResponse(api.reorderNotebook(pw, requireNotNull(op.targetId), requireNotNull(Json.reorder.fromJson(op.payloadJson)))); repo.acknowledge(op) }
            OutboxKind.MIGRATE -> {
                check(op.targetId?.trimEnd('/') == serverUrl.trimEnd('/')) { "La migración pendiente pertenece a otro servidor" }
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
