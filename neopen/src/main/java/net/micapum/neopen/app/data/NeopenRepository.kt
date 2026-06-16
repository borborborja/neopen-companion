package net.micapum.neopen.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.db.NotebookEntity
import net.micapum.neopen.app.db.OutboxEntity
import net.micapum.neopen.app.db.PageBackgroundEntity
import net.micapum.neopen.app.db.SyncMetaEntity
import net.micapum.neopen.core.data.AnchorDto
import net.micapum.neopen.core.data.CapturePatchDto
import net.micapum.neopen.core.data.CaptureDto
import net.micapum.neopen.core.data.ChangesResponseDto
import net.micapum.neopen.core.data.CreateCaptureInput
import net.micapum.neopen.core.data.NeopenApi
import net.micapum.neopen.core.data.NotebookCreateDto
import net.micapum.neopen.core.data.DotDto
import net.micapum.neopen.core.data.NotebookDto
import net.micapum.neopen.core.data.NotebookPaperBody
import net.micapum.neopen.core.data.OcrResultDto
import net.micapum.neopen.core.data.TranscriptionPushDto
import net.micapum.neopen.core.data.NotebookPatchDto
import net.micapum.neopen.core.data.OverlapConfig
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.detectOverlap
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
    /** Sube al servidor una transcripción generada en el teléfono (failover del OCR). */
    const val PUSH_TRANSCRIPTION = "pushTranscription"
}

/**
 * Acceso offline-first a los datos locales (Room). La UI lee SIEMPRE de aquí
 * (Flows); el motor de sync escribe vía [applyChanges]. Las mutaciones locales
 * (Fase 3) se encolan en el outbox.
 */
class NeopenRepository(private val db: NeopenDb) {

    /** Umbral de detección de superposición (lo refresca el Graph desde los ajustes). */
    @Volatile
    var overlapConfig: OverlapConfig = OverlapConfig()

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
        db.backgrounds().clear()
        db.syncMeta().upsert(SyncMetaEntity())
    }

    // --- Fondos (plantilla detrás de los trazos) -------------------------------

    /** Fondo que aplica a una hoja: gana el de página sobre el de cuaderno. */
    suspend fun backgroundForSheet(bookKey: String, page: Int): PageBackground? {
        val rows = db.backgrounds().forBook(bookKey)
        val pageHit = rows.firstOrNull { it.scope == "page" && it.page == page }
        val bookHit = rows.firstOrNull { it.scope == "book" }
        return (pageHit ?: bookHit)?.toDomain()
    }

    fun observeBookBackground(bookKey: String): Flow<PageBackground?> =
        db.backgrounds().observeForBook(bookKey).map { rows -> rows.firstOrNull { it.scope == "book" }?.toDomain() }

    /** Fondo que aplica a una hoja (página > cuaderno), reactivo a cambios en Room. */
    fun observeSheetBackground(bookKey: String, page: Int): Flow<PageBackground?> =
        db.backgrounds().observeForBook(bookKey).map { rows ->
            (rows.firstOrNull { it.scope == "page" && it.page == page } ?: rows.firstOrNull { it.scope == "book" })?.toDomain()
        }

    suspend fun pageBackground(bookKey: String, page: Int): PageBackground? =
        db.backgrounds().forBook(bookKey).firstOrNull { it.scope == "page" && it.page == page }?.toDomain()

    suspend fun bookBackground(bookKey: String): PageBackground? =
        db.backgrounds().forBook(bookKey).firstOrNull { it.scope == "book" }?.toDomain()

    suspend fun upsertBackground(row: PageBackgroundEntity) = db.backgrounds().upsert(row)
    suspend fun deleteBackground(id: String) = db.backgrounds().deleteById(id)
    suspend fun backgroundById(id: String): PageBackground? = db.backgrounds().getById(id)?.toDomain()

    /** Reemplaza los fondos venidos del servidor (modo completo) con los actuales. */
    suspend fun applyServerAnchors(list: List<AnchorDto>) {
        db.backgrounds().clearRemote()
        for (a in list) {
            if (a.bgImage == null && a.bgPreset == null) continue // anclajes solo-vectoriales: no aplican en Android
            db.backgrounds().upsert(a.toBackgroundEntity())
        }
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

    // --- Finalizar hoja / cuaderno (sellado) -----------------------------------

    /** Sella/reabre una hoja (modo completo): optimista + outbox PATCH. */
    suspend fun setCaptureSealed(id: String, sealed: Boolean) {
        db.captures().setSealed(id, sealed, nowIso())
        enqueue(OutboxKind.PATCH_CAPTURE, id, Json.capturePatch.toJson(CapturePatchDto(sealed = sealed)))
    }

    /** Sella/reabre una hoja (modo local, sin servidor). */
    suspend fun setCaptureSealedLocal(id: String, sealed: Boolean) {
        db.captures().setSealed(id, sealed, nowIso())
    }

    /**
     * Finaliza un cuaderno en LOCAL (modo "nuevo cuaderno"): sella sus hojas,
     * desliga el bookKey del cuaderno viejo (renombrándolo) y crea una copia nueva
     * con el mismo bookKey, de modo que el siguiente trazo cree un cuaderno nuevo.
     */
    suspend fun finalizeNotebookLocal(notebookId: String) {
        val nb = db.notebooks().getById(notebookId) ?: return
        val bookKey = nb.bookKey ?: return
        val now = nowIso()
        // Atómico: sellar + desligar + crear la copia no debe quedar a medias.
        db.withTransaction {
            db.captures().sealByNotebook(notebookId, now)
            db.notebooks().upsertOne(nb.copy(bookKey = null, name = "${nb.name} (finalizado)", updatedAt = now))
            db.notebooks().upsertOne(
                NotebookEntity(
                    id = "local-" + UUID.randomUUID(),
                    rev = 0,
                    name = nb.name,
                    color = nb.color,
                    createdAt = now,
                    updatedAt = now,
                    bookKey = bookKey,
                    paperJson = nb.paperJson,
                    paperSource = nb.paperSource,
                    category = nb.category,
                ),
            )
        }
    }

    /**
     * Corta una hoja con trazos superpuestos en LOCAL (sin servidor). Los trazos
     * `[0, cut)` se quedan en la hoja original (que se sella y limpia el aviso) y
     * los `[cut, …)` pasan a una hoja nueva sin sellar.
     * - `newSheet`: la hoja nueva queda en el mismo cuaderno.
     * - `newNotebook`: arranca un cuaderno nuevo (hereda el bookKey del original si
     *   lo tenía, desligándolo del viejo).
     */
    suspend fun splitCaptureLocal(id: String, cutIndex: Int, mode: String) {
        val e = db.captures().getById(id) ?: return
        val sorted = Json.strokes(e.strokesJson).sortedBy { it.startTime }
        // Un corte en 0 o al final (o una hoja vacía) no parte nada: no-op.
        if (sorted.isEmpty() || cutIndex <= 0 || cutIndex >= sorted.size) return
        val before = sorted.take(cutIndex)
        val after = sorted.drop(cutIndex)
        val now = nowIso()
        // Atómico: sellar la original + (re)crear cuaderno + insertar la hoja nueva.
        db.withTransaction {
            // 1. La hoja original se queda con lo de antes del corte y se sella.
            db.captures().upsertOne(
                e.copy(strokesJson = Json.strokesJson(before), sealed = true, overlapJson = null, updatedAt = now),
            )
            // 2. Destino de la hoja nueva.
            var notebookId = e.notebookId
            var destination = e.destination
            if (mode == "newNotebook") {
                val srcNb = e.notebookId?.let { db.notebooks().getById(it) }
                if (srcNb?.bookKey != null) {
                    db.notebooks().upsertOne(srcNb.copy(bookKey = null, name = "${srcNb.name} (finalizado)", updatedAt = now))
                    val freshNb = NotebookEntity(
                        id = "local-" + UUID.randomUUID(),
                        rev = 0,
                        name = srcNb.name,
                        color = srcNb.color,
                        createdAt = now,
                        updatedAt = now,
                        bookKey = srcNb.bookKey,
                        paperJson = srcNb.paperJson,
                        paperSource = srcNb.paperSource,
                        category = srcNb.category,
                    )
                    db.notebooks().upsertOne(freshNb)
                    notebookId = freshNb.id
                    destination = "notebook"
                } else {
                    val freshNb = NotebookEntity(
                        id = "local-" + UUID.randomUUID(),
                        rev = 0,
                        name = e.name,
                        color = "#64748b",
                        createdAt = now,
                        updatedAt = now,
                        paperJson = e.paperJson,
                        custom = true,
                    )
                    db.notebooks().upsertOne(freshNb)
                    notebookId = freshNb.id
                    destination = "notebook"
                }
            }
            // 3. Inserta la hoja nueva con los trazos posteriores al corte.
            db.captures().upsertOne(
                CaptureEntity(
                    id = "local-" + UUID.randomUUID(),
                    rev = 0,
                    section = e.section, owner = e.owner, book = e.book, page = e.page,
                    notebook = e.notebook,
                    source = e.source,
                    capturedAt = e.capturedAt,
                    importedAt = now,
                    updatedAt = now,
                    name = e.name,
                    tagsJson = e.tagsJson,
                    strokesJson = Json.strokesJson(after),
                    destination = destination,
                    notebookId = notebookId,
                    position = 0,
                    paperJson = e.paperJson,
                    kind = e.kind,
                    sealed = false,
                ),
            )
        }
    }

    /** Descarta el aviso de superposición de una hoja en LOCAL (sin cortar). */
    suspend fun dismissOverlapLocal(id: String) {
        val e = db.captures().getById(id) ?: return
        db.captures().upsertOne(e.copy(overlapJson = null, updatedAt = nowIso()))
    }

    /** Aplica al instante el resultado de un split del servidor (modo completo). */
    suspend fun applySplitResult(original: CaptureDto, created: CaptureDto) {
        db.captures().upsertOne(original.toEntity())
        db.captures().upsertOne(created.toEntity())
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
            StrokeDto(st.startTime, st.endTime, st.dots.map { DotDto(it.x, it.y, it.f, it.t) }, st.color, st.width)
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

    // --- Modo LOCAL: archivado en el propio dispositivo (sin servidor) ---------

    /**
     * Ingesta una página en modo local: resuelve/crea el cuaderno por bookKey,
     * deduplica por SOBP (append-only por startTime + nº de dots) y archiva la
     * hoja en su cuaderno. NO usa outbox ni servidor.
     */
    suspend fun ingestPenPageLocal(page: StrokePage) {
        val s = page.sobp
        val bookKey = "${s.section}.${s.owner}.${s.book}"
        val detected = PaperLookup.lookup(s.section, s.owner, s.book, s.page)
        val nb = db.notebooks().findByBookKey(bookKey) ?: createLocalNotebook(bookKey, detected)
        val incoming = page.strokes.map { st ->
            StrokeDto(st.startTime, st.endTime, st.dots.map { DotDto(it.x, it.y, it.f, it.t) }, st.color, st.width)
        }
        val now = nowIso()
        val existing = db.captures().findBySobp(s.section, s.owner, s.book, s.page)
        if (existing != null) {
            val cur = Json.strokes(existing.strokesJson)
            val fresh = incoming.filter { n -> cur.none { it.startTime == n.startTime && it.dots.size == n.dots.size } }
            if (fresh.isEmpty()) return
            // Detección de superposición: ¿los trazos nuevos pisan a los anteriores? Si
            // dispara, marca la hoja; si no, conserva el aviso previo (sin resolver).
            val overlapHit = detectOverlap(cur, fresh, overlapConfig)
            val overlapJson = if (overlapHit != null) Json.overlapJson(overlapHit) else existing.overlapJson
            db.captures().upsertOne(
                existing.copy(strokesJson = Json.strokesJson(cur + fresh), overlapJson = overlapJson, updatedAt = now),
            )
        } else {
            db.captures().upsertOne(
                CaptureEntity(
                    id = "local-" + UUID.randomUUID(),
                    rev = 0,
                    section = s.section, owner = s.owner, book = s.book, page = s.page,
                    notebook = nb.name,
                    source = page.source,
                    capturedAt = page.capturedAt,
                    importedAt = now,
                    updatedAt = now,
                    name = "Nota ${s.section}.${s.owner}.${s.book}.${s.page}",
                    strokesJson = Json.strokesJson(incoming),
                    destination = "notebook",
                    notebookId = nb.id,
                    paperJson = nb.paperJson ?: Json.paperJson(detected),
                ),
            )
        }
    }

    private suspend fun createLocalNotebook(bookKey: String, paper: PaperDto?): NotebookEntity {
        val now = nowIso()
        val nb = NotebookEntity(
            id = "local-" + UUID.randomUUID(),
            rev = 0,
            name = "Ncode $bookKey",
            color = "#5b8cff",
            createdAt = now,
            updatedAt = now,
            bookKey = bookKey,
            paperJson = Json.paperJson(paper),
            paperSource = paper?.source,
        )
        db.notebooks().upsertOne(nb)
        return nb
    }

    /**
     * Vincula un servidor a una base local: sube TODO al servidor (cuadernos →
     * mapa de ids; capturas con su notebookId mapeado; el servidor deduplica por
     * bookKey/SOBP) y, SOLO si todo subió bien, limpia el estado local + outbox y
     * resetea el cursor para que el caller baje el estado unificado. Si algún POST
     * falla, propaga la excepción ANTES de limpiar (local intacto, reintentable).
     */
    suspend fun linkAndMerge(api: NeopenApi, password: String) {
        val idMap = HashMap<String, String>()
        for (nb in db.notebooks().getAll()) {
            if (nb.unclassified) continue // el servidor resuelve su propia pila
            val dto = api.createNotebook(
                password,
                NotebookCreateDto(
                    name = nb.name, color = nb.color, tags = Json.tags(nb.tagsJson), category = nb.category,
                    bookKey = nb.bookKey, paper = Json.paperOf(nb.paperJson), custom = nb.custom,
                ),
            )
            idMap[nb.id] = dto.id
            // createNotebook puede devolver uno existente sin formato: asegúralo.
            Json.paperOf(nb.paperJson)?.let { p ->
                api.setNotebookPaper(password, dto.id, NotebookPaperBody(p, p.source))
            }
        }
        for (c in db.captures().getAll()) {
            val nbId = c.notebookId?.let { idMap[it] }
            api.createCapture(
                password,
                CreateCaptureInput(
                    sobp = SobpDto(c.section, c.owner, c.book, c.page),
                    source = c.source, capturedAt = c.capturedAt, name = c.name,
                    tags = Json.tags(c.tagsJson), strokes = Json.strokes(c.strokesJson),
                    paper = Json.paperOf(c.paperJson), notebook = c.notebook,
                    notebookId = nbId, destination = if (nbId != null) "notebook" else null,
                ),
            )
        }
        // Todo subido sin error → limpia local para repoblar desde el servidor.
        db.outbox().clear()
        db.captures().clear()
        db.notebooks().clear()
        db.syncMeta().upsert(SyncMetaEntity())
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

    /**
     * Aplica localmente el resultado de un OCR hecho en el teléfono: escribe la
     * transcripción y, si la detección de marcadores aportó algo, sobrescribe
     * tags/título/kind (igual que el servidor en `setTranscription` + `applyDetection`).
     * No encola nada; el dispatcher decide si además hay que subirlo (Fase 3).
     */
    suspend fun applyLocalTranscription(
        id: String,
        transcription: OcrResultDto,
        tags: List<String>? = null,
        name: String? = null,
        kind: String? = null,
    ) {
        val e = db.captures().getById(id) ?: return
        db.captures().upsertOne(
            e.copy(
                transcriptionJson = Json.ocrJson(transcription),
                tagsJson = if (tags != null) Json.tagsJson(tags) else e.tagsJson,
                name = name ?: e.name,
                kind = kind ?: e.kind,
                updatedAt = nowIso(),
            ),
        )
    }

    /**
     * Encola la subida al servidor de la transcripción local actual de [id]
     * (failover del OCR: se hizo en el teléfono y hay que sincronizarla). Toma el
     * estado ya aplicado por [applyLocalTranscription]. No-op si no hay transcripción.
     */
    suspend fun enqueueTranscription(id: String) {
        val cap = db.captures().getById(id)?.toDomain() ?: return
        val transcription = cap.transcription ?: return
        val push = TranscriptionPushDto(
            transcription = transcription,
            tags = cap.tags,
            name = cap.name,
            kind = cap.kind,
        )
        enqueue(OutboxKind.PUSH_TRANSCRIPTION, id, Json.transcriptionPush.toJson(push))
    }

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
