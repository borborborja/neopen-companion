package net.micapum.neopen.app.sync

import kotlinx.coroutines.flow.first
import net.micapum.neopen.app.data.Json
import net.micapum.neopen.app.data.NeopenRepository
import net.micapum.neopen.app.data.OutboxKind
import net.micapum.neopen.app.db.OutboxEntity
import net.micapum.neopen.core.data.ApiFactory
import net.micapum.neopen.core.data.NeopenApi
import net.micapum.neopen.core.data.SettingsStore
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Motor de sincronización offline-first.
 *
 * - [pull] baja cambios incrementales (`/api/changes?since=cursor`) y los aplica
 *   a Room, avanzando el cursor; pagina con `hasMore`.
 * - [push] drena el outbox FIFO hacia el servidor (last-write-wins: gana el
 *   último que llega), remapeando ids temporales y reconciliando con la respuesta.
 */
class SyncEngine(
    private val repo: NeopenRepository,
    private val settings: SettingsStore,
    private val apiFactory: (String) -> NeopenApi = { ApiFactory.create(it) },
) {
    data class Result(val ok: Boolean, val pulled: Int = 0, val pushed: Int = 0, val error: String? = null)

    /** Sincronización completa: primero sube lo pendiente, luego baja cambios. */
    suspend fun sync(): Result {
        val p = push()
        val q = pull()
        return when {
            !p.ok -> p.copy(pulled = q.pulled)
            !q.ok -> q.copy(pushed = p.pushed)
            else -> Result(ok = true, pulled = q.pulled, pushed = p.pushed)
        }
    }

    suspend fun pull(): Result {
        val s = settings.flow.first()
        if (!s.configured) return Result(false, error = "Sin servidor configurado")
        val api = apiFactory(s.serverUrl)
        var pulled = 0
        return try {
            while (true) {
                val cursor = repo.cursor()
                val resp = api.changes(s.password, since = cursor, limit = 200)
                repo.applyChanges(resp, System.currentTimeMillis())
                pulled += resp.captures.size + resp.notebooks.size
                if (!resp.hasMore) break
                if (resp.cursor == cursor && !resp.reset) break // guardia anti-bucle
            }
            // Garantiza que "Sin clasificar" exista en local (se muestra aunque esté vacío).
            runCatching { repo.applyServerNotebook(api.unclassifiedNotebook(s.password)) }
            // Fondos (anclajes) del servidor → caché local (la web gestiona la BBDD).
            runCatching { repo.applyServerAnchors(api.listAnchors(s.password)) }
            // Ajustes de render del servidor (presión→grosor): fuente de verdad en modo servidor.
            runCatching {
                val rs = api.renderSettings(s.password)
                settings.update(
                    settings.flow.first().copy(
                        serverPressureWidthEnabled = rs.pressureWidthEnabled,
                        serverPressureWidthContrast = rs.pressureWidthContrast,
                    ),
                )
            }
            Result(ok = true, pulled = pulled)
        } catch (e: Exception) {
            Result(ok = false, pulled = pulled, error = e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun push(): Result {
        val s = settings.flow.first()
        if (!s.configured) return Result(false, error = "Sin servidor configurado")
        val api = apiFactory(s.serverUrl)
        var pushed = 0
        try {
            while (true) {
                val batch = repo.outboxPeek(20)
                if (batch.isEmpty()) break
                for (op in batch) {
                    when (val outcome = runOp(api, s.password, op)) {
                        Outcome.Done -> {
                            repo.outboxDelete(op.localId); pushed++
                        }
                        Outcome.Drop -> repo.outboxDelete(op.localId)
                        Outcome.AuthStop -> {
                            repo.outboxMarkFailed(op.localId, "401")
                            return Result(false, pushed = pushed, error = "Contraseña o servidor incorrectos")
                        }
                        is Outcome.Stop -> {
                            repo.outboxMarkFailed(op.localId, outcome.error)
                            return Result(false, pushed = pushed, error = outcome.error)
                        }
                    }
                }
            }
            if (pushed > 0) repo.markPushed(System.currentTimeMillis())
            return Result(ok = true, pushed = pushed)
        } catch (e: Exception) {
            return Result(false, pushed = pushed, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private sealed interface Outcome {
        data object Done : Outcome // subido y reconciliado
        data object Drop : Outcome // 4xx terminal o payload corrupto: descartar
        data object AuthStop : Outcome // 401: parar y avisar
        data class Stop(val error: String) : Outcome // red/5xx: reintentar luego
    }

    private suspend fun runOp(api: NeopenApi, pw: String, op: OutboxEntity): Outcome {
        return try {
            when (op.kind) {
                OutboxKind.PATCH_CAPTURE -> {
                    val dto = Json.capturePatch.fromJson(op.payloadJson) ?: return Outcome.Drop
                    repo.applyServerCapture(api.patchCapture(pw, op.targetId!!, dto))
                    Outcome.Done
                }
                OutboxKind.PUSH_TRANSCRIPTION -> {
                    val dto = Json.transcriptionPush.fromJson(op.payloadJson) ?: return Outcome.Drop
                    repo.applyServerCapture(api.putTranscription(pw, op.targetId!!, dto))
                    Outcome.Done
                }
                OutboxKind.DELETE_CAPTURE -> interpret(api.deleteCapture(pw, op.targetId!!))
                OutboxKind.CREATE_NOTEBOOK -> {
                    val dto = Json.notebookCreate.fromJson(op.payloadJson) ?: return Outcome.Drop
                    repo.replaceTempNotebook(op.targetId!!, api.createNotebook(pw, dto))
                    Outcome.Done
                }
                OutboxKind.PATCH_NOTEBOOK -> {
                    val dto = Json.notebookPatch.fromJson(op.payloadJson) ?: return Outcome.Drop
                    repo.applyServerNotebook(api.patchNotebook(pw, op.targetId!!, dto))
                    Outcome.Done
                }
                OutboxKind.DELETE_NOTEBOOK -> interpret(api.deleteNotebook(pw, op.targetId!!))
                OutboxKind.REORDER_NOTEBOOK -> {
                    val dto = Json.reorder.fromJson(op.payloadJson) ?: return Outcome.Drop
                    interpret(api.reorderNotebook(pw, op.targetId!!, dto))
                }
                OutboxKind.CREATE_CAPTURE -> {
                    val input = Json.createCapture.fromJson(op.payloadJson) ?: return Outcome.Drop
                    val cap = api.createCapture(pw, input)
                    op.targetId?.let { temp ->
                        repo.outboxRepoint(temp, cap.id) // ops posteriores apuntan al id real
                        repo.replaceTempCapture(temp, cap)
                    }
                    Outcome.Done
                }
                else -> Outcome.Drop
            }
        } catch (e: HttpException) {
            when {
                e.code() == 401 -> Outcome.AuthStop
                e.code() in 400..499 -> Outcome.Drop // el servidor rechaza; reintentar no ayuda
                else -> Outcome.Stop("HTTP ${e.code()}")
            }
        } catch (e: IOException) {
            Outcome.Stop(e.message ?: "Sin conexión")
        }
    }

    private fun interpret(resp: Response<Unit>): Outcome = when {
        resp.isSuccessful || resp.code() == 404 -> Outcome.Done // 404 = ya borrado (idempotente)
        resp.code() == 401 -> Outcome.AuthStop
        resp.code() in 400..499 -> Outcome.Drop
        else -> Outcome.Stop("HTTP ${resp.code()}")
    }
}
