package net.micapum.neopen.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.db.NotebookEntity
import net.micapum.neopen.app.db.OutboxEntity
import net.micapum.neopen.app.db.SyncMetaEntity
import net.micapum.neopen.core.data.CapturePatchDto
import net.micapum.neopen.core.data.CaptureDto
import net.micapum.neopen.core.data.ChangesResponseDto
import net.micapum.neopen.core.data.CreateCaptureInput
import net.micapum.neopen.core.data.NotebookCreateDto
import net.micapum.neopen.core.data.DotDto
import net.micapum.neopen.core.data.NotebookDto
import net.micapum.neopen.core.data.NotebookPatchDto
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.ReorderDto
import net.micapum.neopen.core.data.SobpDto
import net.micapum.neopen.core.data.StrokeDto
import net.micapum.neopen.core.pen.PaperLookup
import net.micapum.neopen.core.pen.StrokePage
import java.time.Instant
import java.util.UUID

/** Tipos de operación del outbox (kind de OutboxEntity). */
object OutboxKind {
    const val CREATE_CAPTURE = "createCapture"
    const val PATCH_CAPTURE = "patchCapture"
    const val DELETE_CAPTURE = "deleteCapture"
    const val CREATE_NOTEBOOK = "createNotebook"
    const val PATCH_NOTEBOOK = "patchNotebook"
    const val DELETE_NOTEBOOK = "deleteNotebook"
    const val REORDER_NOTEBOOK = "reorderNotebook"
}

/**
 * Acceso offline-first a los datos locales (Room). La UI lee SIEMPRE de aquí
 * (Flows); el motor de sync escribe vía [applyChanges]. Las mutaciones locales
 * (Fase 3) se encolan en el outbox.
 */
class NeopenRepository(private val db: NeopenDb) {

    // --- Lecturas (Flows reactivos sobre Room) ---

    fun notebooks(): Flow<List<Notebook>> =
        db.notebooks().observeAll().combine(db.notebooks().observeCounts()) { nbs, counts ->
            val byId = counts.associate { it.id to it.count }
            nbs.map { it.toDomain(pageCount = byId[it.id] ?: 0) }
        }

    fun inbox(): Flow<List<Capture>> =
        db.captures().observeInbox().map { rows -> rows.map { it.toDomain() } }

    /** Todas las capturas por última edición (equivalente a "últimas ediciones"). */
    fun recent(): Flow<List<Capture>> =
        db.captures().observeAll().map { rows -> rows.map { it.toDomain() } }

    fun capturesIn(notebookId: String): Flow<List<Capture>> =
        db.captures().observeByNotebook(notebookId).map { rows -> rows.map { it.toDomain() } }

    suspend fun capture(id: String): Capture? = db.captures().getById(id)?.toDomain()

    fun captureFlow(id: String): Flow<Capture?> = db.captures().observeById(id).map { it?.toDomain() }

    suspend fun notebook(id: String): Notebook? = db.notebooks().getById(id)?.toDomain()

    fun pendingCount(): Flow<Int> = db.outbox().observeCount()

    // --- Sincronización de bajada ---

    suspend fun cursor(): Long = db.syncMeta().get()?.cursor ?: 0L

    /** Aplica un lote de cambios del servidor a Room y avanza el cursor. */
    suspend fun applyChanges(resp: ChangesResponseDto, now: Long) {
        if (resp.reset) {
            db.captures().clear()
            db.notebooks().clear()
        }
        if (resp.captures.isNotEmpty()) db.captures().upsert(resp.captures.map { it.toEntity() })
        if (resp.notebooks.isNotEmpty()) db.notebooks().upsert(resp.notebooks.map { it.toEntity() })
        for (d in resp.deletions) when (d.kind) {
            "capture" -> db.captures().deleteById(d.id)
            "notebook" -> db.notebooks().deleteById(d.id)
        }
        val meta = db.syncMeta().get() ?: SyncMetaEntity()
        db.syncMeta().upsert(meta.copy(cursor = resp.cursor, lastPullAt = now))
    }

    /** Borra todo el contenido local (cambio de servidor / cierre de sesión). */
    suspend fun clearLocal() {
        db.captures().clear()
        db.notebooks().clear()
        db.outbox().clear()
        db.syncMeta().upsert(SyncMetaEntity())
    }

    // --- Mutaciones locales optimistas (Room + outbox) -------------------------

    private fun nowIso(): String = Instant.now().toString()

    suspend fun renameCapture(id: String, name: String) {
        val e = db.captures().getById(id) ?: return
        db.captures().upsertOne(e.copy(name = name, updatedAt = nowIso()))
        enqueue(OutboxKind.PATCH_CAPTURE, id, Json.capturePatch.toJson(CapturePatchDto(name = name)))
    }

    suspend fun setCaptureTags(id: String, tags: List<String>) {
        val e = db.captures().getById(id) ?: return
        db.captures().upsertOne(e.copy(tagsJson = Json.tagsJson(tags), updatedAt = nowIso()))
        enqueue(OutboxKind.PATCH_CAPTURE, id, Json.capturePatch.toJson(CapturePatchDto(tags = tags)))
    }

    suspend fun moveCapture(id: String, notebookId: String, position: Int) {
        val e = db.captures().getById(id) ?: return
        db.captures().upsertOne(
            e.copy(destination = "notebook", notebookId = notebookId, position = position, updatedAt = nowIso()),
        )
        enqueue(
            OutboxKind.PATCH_CAPTURE,
            id,
            Json.capturePatch.toJson(
                CapturePatchDto(destination = "notebook", notebookId = notebookId, position = position),
            ),
        )
    }

    /** Formato de una hoja individual (override). Optimista + outbox PATCH. */
    suspend fun setCapturePaper(id: String, paper: PaperDto?) {
        val e = db.captures().getById(id) ?: return
        db.captures().upsertOne(e.copy(paperJson = Json.paperJson(paper), updatedAt = nowIso()))
        enqueue(OutboxKind.PATCH_CAPTURE, id, Json.capturePatch.toJson(CapturePatchDto(paper = paper)))
    }

    /**
     * Aplica localmente el formato del cuaderno a su entidad y re-sella sus hojas
     * (respeta overrides por hoja con source="manual"), igual que el servidor. La
     * subida la hace el ViewModel vía PUT directo; aquí solo es la copia optimista.
     */
    suspend fun applyNotebookPaperLocal(id: String, paper: PaperDto?) {
        val nb = db.notebooks().getById(id) ?: return
        val now = nowIso()
        db.notebooks().upsertOne(nb.copy(paperJson = Json.paperJson(paper), paperSource = paper?.source, updatedAt = now))
        for (c in db.captures().getByNotebook(id)) {
            if (Json.paperOf(c.paperJson)?.source == "manual") continue
            db.captures().upsertOne(c.copy(paperJson = Json.paperJson(paper), updatedAt = now))
        }
    }

    suspend fun deleteCapture(id: String) {
        db.captures().deleteById(id)
        enqueue(OutboxKind.DELETE_CAPTURE, id, "{}")
    }

    suspend fun reorderNotebook(notebookId: String, orderedIds: List<String>) {
        orderedIds.forEachIndexed { i, cid ->
            db.captures().getById(cid)?.let { db.captures().upsertOne(it.copy(position = i, updatedAt = nowIso())) }
        }
        enqueue(OutboxKind.REORDER_NOTEBOOK, notebookId, Json.reorder.toJson(ReorderDto(orderedIds)))
    }

    suspend fun createNotebook(
        name: String,
        color: String,
        tags: List<String> = emptyList(),
        category: String? = null,
    ): String {
        val tempId = "local-" + UUID.randomUUID()
        val now = nowIso()
        db.notebooks().upsertOne(
            NotebookEntity(
                id = tempId,
                rev = 0,
                name = name,
                color = color,
                tagsJson = Json.tagsJson(tags),
                createdAt = now,
                updatedAt = now,
                category = category,
            ),
        )
        enqueue(
            OutboxKind.CREATE_NOTEBOOK,
            tempId,
            Json.notebookCreate.toJson(NotebookCreateDto(name, color, tags, category?.ifBlank { null })),
        )
        return tempId
    }

    /**
     * Edita un cuaderno. `category` null = no tocar la categoría; una cadena
     * (incluida "") = fijarla (el servidor normaliza "" → sin categoría).
     */
    suspend fun editNotebook(
        id: String,
        name: String? = null,
        color: String? = null,
        tags: List<String>? = null,
        category: String? = null,
    ) {
        val e = db.notebooks().getById(id) ?: return
        db.notebooks().upsertOne(
            e.copy(
                name = name ?: e.name,
                color = color ?: e.color,
                tagsJson = if (tags != null) Json.tagsJson(tags) else e.tagsJson,
                category = if (category != null) category.ifBlank { null } else e.category,
                updatedAt = nowIso(),
            ),
        )
        enqueue(
            OutboxKind.PATCH_NOTEBOOK,
            id,
            Json.notebookPatch.toJson(NotebookPatchDto(name = name, color = color, tags = tags, category = category)),
        )
    }

    suspend fun deleteNotebook(id: String) {
        db.notebooks().deleteById(id)
        enqueue(OutboxKind.DELETE_NOTEBOOK, id, "{}")
    }

    /** Inserta la captura optimista en Room y encola su creación (boli — Fase 4). */
    suspend fun enqueueCreateCapture(input: CreateCaptureInput, optimistic: CaptureEntity) {
        db.captures().upsertOne(optimistic)
        enqueue(OutboxKind.CREATE_CAPTURE, optimistic.id, Json.createCapture.toJson(input))
    }

    /**
     * Ingesta una página capturada por el boli: la muestra al instante en la
     * bandeja (Room optimista) y la encola para subir (el servidor la archiva
     * por SOBP). Mismo flujo de subida que el companion, pero offline-first.
     */
    suspend fun ingestPenPage(page: StrokePage) {
        val s = page.sobp
        val strokes = page.strokes.map { st ->
            StrokeDto(st.startTime, st.endTime, st.dots.map { DotDto(it.x, it.y, it.f, it.t) })
        }
        val name = "Nota ${s.section}.${s.owner}.${s.book}.${s.page}"
        // Detección del formato real (NeoLAB) para sembrar el del cuaderno nuevo:
        // así las hojas salen con las proporciones correctas sin intervención.
        val paper = PaperLookup.lookup(s.section, s.owner, s.book, s.page)
        val input = CreateCaptureInput(
            sobp = SobpDto(s.section, s.owner, s.book, s.page),
            source = page.source,
            capturedAt = page.capturedAt,
            name = name,
            strokes = strokes,
            paper = paper,
        )
        val now = nowIso()
        val entity = CaptureEntity(
            id = "local-" + UUID.randomUUID(),
            rev = 0,
            section = s.section,
            owner = s.owner,
            book = s.book,
            page = s.page,
            notebook = null,
            source = page.source,
            capturedAt = page.capturedAt,
            importedAt = now,
            updatedAt = now,
            name = name,
            strokesJson = Json.strokesJson(strokes),
            paperJson = Json.paperJson(paper),
            destination = "inbox",
        )
        enqueueCreateCapture(input, entity)
    }

    private suspend fun enqueue(kind: String, targetId: String?, payloadJson: String) {
        db.outbox().insert(
            OutboxEntity(kind = kind, targetId = targetId, payloadJson = payloadJson, createdAt = System.currentTimeMillis()),
        )
    }

    // --- Drenaje del outbox (lo usa SyncEngine.push) ---------------------------

    suspend fun outboxPeek(limit: Int): List<OutboxEntity> = db.outbox().peek(limit)
    suspend fun outboxDelete(localId: Long) = db.outbox().deleteById(localId)
    suspend fun outboxMarkFailed(localId: Long, error: String?) = db.outbox().markFailed(localId, error)
    suspend fun outboxRepoint(oldId: String, newId: String) = db.outbox().repointTarget(oldId, newId)
    suspend fun markPushed(now: Long) {
        val meta = db.syncMeta().get() ?: SyncMetaEntity()
        db.syncMeta().upsert(meta.copy(lastPushAt = now))
    }

    suspend fun applyServerCapture(dto: CaptureDto) = db.captures().upsertOne(dto.toEntity())
    suspend fun applyServerNotebook(dto: NotebookDto) = db.notebooks().upsertOne(dto.toEntity())

    /** Sustituye un cuaderno temporal por el real del servidor (repunta sus hojas). */
    suspend fun replaceTempNotebook(tempId: String, dto: NotebookDto) {
        if (tempId != dto.id) {
            db.captures().repointNotebook(tempId, dto.id)
            db.notebooks().deleteById(tempId)
        }
        db.notebooks().upsertOne(dto.toEntity())
    }

    /** Sustituye una captura temporal por la real del servidor (mismo SOBP). */
    suspend fun replaceTempCapture(tempId: String, dto: CaptureDto) {
        if (tempId != dto.id) db.captures().deleteById(tempId)
        db.captures().upsertOne(dto.toEntity())
    }
}
