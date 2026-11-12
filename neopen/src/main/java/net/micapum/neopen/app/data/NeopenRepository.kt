package net.micapum.neopen.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.db.NotebookEntity
import net.micapum.neopen.app.db.OutboxEntity
import net.micapum.neopen.app.db.OfflineImportWarningEntity
import net.micapum.neopen.app.db.PageBackgroundEntity
import net.micapum.neopen.app.db.PenPageUserActionEntity
import net.micapum.neopen.app.db.SyncMetaEntity
import net.micapum.neopen.core.data.AnchorDto
import net.micapum.neopen.core.data.paper
import net.micapum.neopen.core.data.StudioBookDto
import net.micapum.neopen.core.data.canonicalPenId
import net.micapum.neopen.core.data.normalizePenIdentifier
import net.micapum.neopen.core.data.CapturePatchDto
import net.micapum.neopen.core.data.CaptureDto
import net.micapum.neopen.core.data.ChangesResponseDto
import net.micapum.neopen.core.data.CreateCaptureInput
import net.micapum.neopen.core.data.NotebookCreateDto
import net.micapum.neopen.core.data.DotDto
import net.micapum.neopen.core.data.OfflineReimportDecision
import net.micapum.neopen.core.data.OfflineReimportPolicy
import net.micapum.neopen.core.data.NotebookDto
import net.micapum.neopen.core.data.OcrResultDto
import net.micapum.neopen.core.data.TranscriptionPushDto
import net.micapum.neopen.core.data.NotebookPatchDto
import net.micapum.neopen.core.data.OverlapConfig
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.detectOverlap
import net.micapum.neopen.core.data.freshReimportStrokes
import net.micapum.neopen.core.data.ReorderDto
import net.micapum.neopen.core.data.resolveOfflineReimport
import net.micapum.neopen.core.data.normalizedServerUrl
import net.micapum.neopen.core.data.SobpDto
import net.micapum.neopen.core.data.StrokeDto
import net.micapum.neopen.core.pen.PaperLookup
import net.micapum.neopen.core.pen.StrokePage
import java.security.MessageDigest
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
    const val MIGRATE = "migrate"
}

object OfflineImportStatus {
    const val PENDING = "pending"
    const val DISCARDED = "discarded"
    const val NEW_CAPTURE = "newCapture"
    const val MERGED = "merged"
}

object PenPageAction {
    const val DELETED = "deleted"
    const val MOVED = "moved"
}

/**
 * Acceso offline-first a los datos locales (Room). La UI lee SIEMPRE de aquí
 * (Flows); el motor de sync escribe vía [applyChanges]. Las mutaciones locales
 * (Fase 3) se encolan en el outbox.
 */
class NeopenRepository(private val db: NeopenDb) {
    val studio = StudioBooksRepository(db)
    private val penIngestion = Mutex()
    var onLocalCleared: () -> Unit = {}

    /** La vinculación y el cambio de biblioteca no pueden adelantar una escritura BLE. */
    suspend fun <T> withPenIngestionPaused(action: suspend () -> T): T = penIngestion.withLock { action() }

    /** Evalúa el modo después de esperar cualquier transición, nunca antes. */
    suspend fun persistPenPage(
        page: StrokePage,
        policy: OfflineReimportPolicy,
        binding: StudioBookDto? = null,
        bindingFrozen: Boolean = false,
        isLocal: suspend () -> Boolean,
    ): Boolean = penIngestion.withLock {
        val local = isLocal()
        if (local) ingestPenPageLocalUnlocked(page, policy, binding, bindingFrozen) else ingestPenPageUnlocked(page, policy, binding, bindingFrozen)
        local
    }

    /** Umbral de detección de superposición (lo refresca el Graph desde los ajustes). */
    @Volatile
    var overlapConfig: OverlapConfig = OverlapConfig()

    // --- Lecturas (Flows reactivos sobre Room) ---

    fun notebooks(): Flow<List<Notebook>> =
        db.notebooks().observeAll().combine(db.notebooks().observeCounts()) { nbs, counts ->
            val byId = counts.associate { it.id to it.count }
            nbs.map { it.toDomain(pageCount = byId[it.id] ?: 0) }
        }

    // El parseo de strokesJson (Moshi) de toda la lista se hace en Dispatchers.Default para
    // no bloquear el hilo principal en el arranque en frío (muchas capturas → mucho parseo).
    fun inbox(): Flow<List<Capture>> =
        db.captures().observeInbox().map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    /** Todas las capturas por última edición (equivalente a "últimas ediciones"). */
    fun recent(): Flow<List<Capture>> =
        db.captures().observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    fun offlineImportWarnings(): Flow<List<OfflineImportWarning>> =
        db.offlineImportWarnings().observePending().map { rows -> rows.map { it.toDomain() } }

    fun pendingOfflineImportWarningCount(): Flow<Int> =
        db.offlineImportWarnings().observePendingCount()

    fun capturesIn(notebookId: String): Flow<List<Capture>> =
        db.captures().observeByNotebook(notebookId).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    suspend fun capture(id: String): Capture? = db.captures().getById(id)?.toDomain()

    fun captureFlow(id: String): Flow<Capture?> = db.captures().observeById(id).map { it?.toDomain() }

    suspend fun notebook(id: String): Notebook? = db.notebooks().getById(id)?.toDomain()

    fun pendingCount(): Flow<Int> = db.outbox().observeCount()

    // --- Sincronización de bajada ---

    suspend fun cursor(): Long = db.syncMeta().get()?.cursor ?: 0L

    /** Aplica un lote de cambios del servidor a Room y avanza el cursor. */
    suspend fun applyChanges(resp: ChangesResponseDto, now: Long) = db.withTransaction {
        check(db.outbox().peek(Int.MAX_VALUE).all { StudioOutbox.requiresStudio(it.kind) }) { "Hay cambios locales pendientes" }
        if (resp.reset) {
            db.captures().clear()
            db.notebooks().clear()
        }
        if (resp.captures.isNotEmpty()) db.captures().upsert(resp.captures.map { it.toEntity() })
        if (resp.notebooks.isNotEmpty()) db.notebooks().upsert(resp.notebooks.map { it.toEntity() })
        for (d in resp.deletions) when (d.kind) {
            "capture" -> {
                db.captures().getById(d.id)?.let { recordPageAction(it, PenPageAction.DELETED) }
                db.captures().deleteById(d.id)
            }
            "notebook" -> db.notebooks().deleteById(d.id)
        }
        val meta = db.syncMeta().get() ?: SyncMetaEntity()
        db.syncMeta().upsert(meta.copy(cursor = resp.cursor, lastPullAt = now))
    }

    /** Borra todo el contenido local (cambio de servidor / cierre de sesión). */
    suspend fun clearLocal() {
        db.withTransaction {
            db.captures().clear()
            db.notebooks().clear()
            db.outbox().clear()
            db.backgrounds().clear()
            db.studio().clearBooks()
            db.studio().clearMetadata()
            db.penPageUserActions().clear()
            db.offlineImportWarnings().clear()
            db.offlineNoteState().clear()
            db.connectorSyncState().clear()
            db.syncMeta().upsert(SyncMetaEntity())
        }
        onLocalCleared()
    }

    suspend fun hasLibraryOrPendingChanges(): Boolean =
        db.captures().getAll().isNotEmpty() || db.notebooks().getAll().isNotEmpty() || db.studio().books().isNotEmpty() || db.outbox().peek(1).isNotEmpty()

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
    fun observeSheetBackground(bookKey: String, page: Int, bookInstanceId: String? = null): Flow<PageBackground?> =
        db.backgrounds().observeForBook(bookKey).map { allRows ->
            val rows = allRows.filter { it.bookInstanceId == bookInstanceId }
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

    suspend fun renameCapture(id: String, name: String) = db.withTransaction {
        val e = db.captures().getById(id) ?: return@withTransaction
        db.captures().upsertOne(e.copy(name = name, updatedAt = nowIso()))
        enqueue(OutboxKind.PATCH_CAPTURE, id, Json.capturePatch.toJson(CapturePatchDto(name = name)))
    }

    suspend fun setCaptureTags(id: String, tags: List<String>) = db.withTransaction {
        val e = db.captures().getById(id) ?: return@withTransaction
        db.captures().upsertOne(e.copy(tagsJson = Json.tagsJson(tags), updatedAt = nowIso()))
        enqueue(OutboxKind.PATCH_CAPTURE, id, Json.capturePatch.toJson(CapturePatchDto(tags = tags)))
    }

    suspend fun moveCapture(id: String, notebookId: String, position: Int) = db.withTransaction {
        val e = db.captures().getById(id) ?: return@withTransaction
        val nb = db.notebooks().getById(notebookId)
        recordPageAction(e, PenPageAction.MOVED, notebookId, nb?.name)
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
    suspend fun setCapturePaper(id: String, paper: PaperDto?) = db.withTransaction {
        val e = db.captures().getById(id) ?: return@withTransaction
        db.captures().upsertOne(e.copy(paperJson = Json.paperJson(paper), updatedAt = nowIso()))
        enqueue(OutboxKind.PATCH_CAPTURE, id, Json.capturePatch.toJson(CapturePatchDto(paper = paper, paperSpecified = true)))
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
        val e = db.captures().getById(id)
        db.withTransaction {
            e?.let { recordPageAction(it, PenPageAction.DELETED) }
            db.captures().deleteById(id)
            enqueue(OutboxKind.DELETE_CAPTURE, id, "{}")
        }
    }

    // --- Finalizar hoja / cuaderno (sellado) -----------------------------------

    /** Sella/reabre una hoja (modo completo): optimista + outbox PATCH. */
    suspend fun setCaptureSealed(id: String, sealed: Boolean) = db.withTransaction {
        if (!sealed && db.captures().getById(id)?.bookInstanceId?.let { instance -> studio.cachedBooks().flatMap { it.instances }.any { it.id == instance && it.closedAt != null } } == true) return@withTransaction
        db.captures().setSealed(id, sealed, nowIso())
        enqueue(OutboxKind.PATCH_CAPTURE, id, Json.capturePatch.toJson(CapturePatchDto(sealed = sealed)))
    }

    /** Sella/reabre una hoja (modo local, sin servidor). */
    suspend fun setCaptureSealedLocal(id: String, sealed: Boolean) {
        if (!sealed && db.captures().getById(id)?.bookInstanceId?.let { instance -> studio.cachedBooks().flatMap { it.instances }.any { it.id == instance && it.closedAt != null } } == true) return
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
                    sealed = e.sealed,
                    penId = e.penId, bookInstanceId = e.bookInstanceId, assignmentId = e.assignmentId, assignmentRevision = e.assignmentRevision,
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

    suspend fun reorderNotebook(notebookId: String, orderedIds: List<String>) = db.withTransaction {
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
    ): String = db.withTransaction {
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
        tempId
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
    ) = db.withTransaction {
        val e = db.notebooks().getById(id) ?: return@withTransaction
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

    suspend fun deleteNotebook(id: String) = db.withTransaction {
        db.notebooks().deleteById(id)
        enqueue(OutboxKind.DELETE_NOTEBOOK, id, "{}")
    }

    /** Inserta la captura optimista en Room y encola su creación (boli — Fase 4). */
    suspend fun enqueueCreateCapture(input: CreateCaptureInput, optimistic: CaptureEntity) = db.withTransaction {
        db.captures().upsertOne(optimistic)
        enqueue(OutboxKind.CREATE_CAPTURE, optimistic.id, Json.createCapture.toJson(input))
    }

    /**
     * Ingesta una página capturada por el boli: la muestra al instante en la
     * bandeja (Room optimista) y la encola para subir (el servidor la archiva
     * por SOBP). Mismo flujo de subida que el companion, pero offline-first.
     */
    suspend fun ingestPenPage(
        page: StrokePage,
        reimportPolicy: OfflineReimportPolicy = OfflineReimportPolicy.ASK,
    ) = penIngestion.withLock { ingestPenPageUnlocked(page, reimportPolicy) }

    private suspend fun ingestPenPageUnlocked(page: StrokePage, reimportPolicy: OfflineReimportPolicy, binding: StudioBookDto? = null, bindingFrozen: Boolean = false) {
        val s = page.sobp
        page.penId?.let { studio.registerPen(it) }
        val knownPens = studio.cachedPens()
        val origin = canonicalPenId(page.penId, knownPens) ?: page.penId?.let(::normalizePenIdentifier)
        val assigned = if (bindingFrozen) binding else studio.resolve(s.section, s.owner, s.book, page.penId)
        val strokes = page.strokes.map { st ->
            StrokeDto(st.startTime, st.endTime, st.dots.map { DotDto(it.x, it.y, it.f, it.t) }, st.color, st.width, canonicalPenId(st.penId, knownPens) ?: st.penId?.let(::normalizePenIdentifier) ?: origin)
        }
        val name = "Nota ${s.section}.${s.owner}.${s.book}.${s.page}"
        // Detección del formato real (NeoLAB) para sembrar el del cuaderno nuevo:
        // así las hojas salen con las proporciones correctas sin intervención.
        val paper = assigned?.paper() ?: if (studio.hasCode(s.section, s.owner, s.book)) null else PaperLookup.lookup(s.section, s.owner, s.book, s.page)
        val input = CreateCaptureInput(
            sobp = SobpDto(s.section, s.owner, s.book, s.page),
            source = page.source,
            penId = origin,
            bookInstanceId = assigned?.instanceId,
            assignmentId = assigned?.assignmentId, assignmentRevision = assigned?.assignmentRevision,
            capturedAt = page.capturedAt,
            name = name,
            strokes = strokes,
            paper = paper,
        )
        val now = nowIso()
        if (page.source == "offline" && handleOfflineReimportIfNeeded(page, strokes, paper, name, input, reimportPolicy, now, syncRemote = true)) {
            return
        }
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
            penId = origin, bookInstanceId = assigned?.instanceId, assignmentId = assigned?.assignmentId, assignmentRevision = assigned?.assignmentRevision,
        )
        enqueueCreateCapture(input, entity)
    }

    private suspend fun handleOfflineReimportIfNeeded(
        page: StrokePage,
        strokes: List<StrokeDto>,
        paper: PaperDto?,
        name: String,
        input: CreateCaptureInput,
        policy: OfflineReimportPolicy,
        now: String,
        syncRemote: Boolean,
    ): Boolean {
        val s = page.sobp
        if (input.bookInstanceId == null && studio.hasCode(s.section, s.owner, s.book)) return false
        val actionKey = input.bookInstanceId?.let { "$it:${s.page}" } ?: sobpKey(s.section, s.owner, s.book, s.page)
        val action = db.penPageUserActions().getBySobpKey(actionKey) ?: return false
        if (action.action != PenPageAction.DELETED && action.action != PenPageAction.MOVED) return false
        val warningId = offlineWarningId(s.section, s.owner, s.book, s.page, strokes)
        if (db.offlineImportWarnings().getResolved(warningId) != null) return true
        val target = action.captureId
            ?.let { db.captures().getById(it) }
            ?.takeIf { it.clonedFrom == null && !it.sealed }
        val warning = warningEntity(
            id = warningId,
            page = page,
            strokes = strokes,
            paper = paper,
            name = name,
            action = action,
            target = target,
            now = now,
        )
        when (resolveOfflineReimport(policy, target != null)) {
            OfflineReimportDecision.ASK -> db.offlineImportWarnings().upsert(warning)
            OfflineReimportDecision.DISCARD -> db.offlineImportWarnings().upsert(warning.copy(status = OfflineImportStatus.DISCARDED, resolvedAt = now))
            OfflineReimportDecision.NEW_CAPTURE -> {
                createCaptureFromReimport(input, strokes, paper, page, name, now, syncRemote)
                db.offlineImportWarnings().upsert(warning.copy(status = OfflineImportStatus.NEW_CAPTURE, resolvedAt = now))
            }
            OfflineReimportDecision.MERGE -> {
                mergeReimportIntoCapture(target ?: return false, input, strokes, now, syncRemote)
                db.offlineImportWarnings().upsert(warning.copy(status = OfflineImportStatus.MERGED, resolvedAt = now))
            }
        }
        return true
    }

    suspend fun discardOfflineImportWarning(id: String) {
        db.offlineImportWarnings().setStatus(id, OfflineImportStatus.DISCARDED, nowIso())
    }

    suspend fun importOfflineWarningAsNewCapture(id: String) {
        val warning = db.offlineImportWarnings().getById(id) ?: return
        if (warning.status != OfflineImportStatus.PENDING) return
        val page = warning.toStrokePage()
        val strokes = Json.strokes(warning.strokesJson)
        val paper = Json.paperOf(warning.paperJson)
        val input = CreateCaptureInput(
            sobp = SobpDto(warning.section, warning.owner, warning.book, warning.page),
            source = page.source,
            capturedAt = warning.capturedAt,
            penId = strokes.mapNotNull { it.penId }.distinct().singleOrNull(),
            name = warning.name,
            strokes = strokes,
            paper = paper,
        )
        val now = nowIso()
        createCaptureFromReimport(input, strokes, paper, page, warning.name, now, syncRemote = true)
        db.offlineImportWarnings().setStatus(id, OfflineImportStatus.NEW_CAPTURE, now)
    }

    suspend fun mergeOfflineImportWarning(id: String): Boolean {
        val warning = db.offlineImportWarnings().getById(id) ?: return false
        if (warning.status != OfflineImportStatus.PENDING) return false
        val targetId = warning.targetCaptureId ?: return false
        val target = db.captures().getById(targetId)?.takeIf { it.clonedFrom == null && !it.sealed } ?: return false
        val strokes = Json.strokes(warning.strokesJson)
        val input = CreateCaptureInput(
            sobp = SobpDto(warning.section, warning.owner, warning.book, warning.page),
            source = warning.source,
            capturedAt = warning.capturedAt,
            penId = strokes.mapNotNull { it.penId }.distinct().singleOrNull(),
            name = warning.name,
            strokes = strokes,
            paper = Json.paperOf(warning.paperJson),
            notebookId = target.notebookId,
            destination = target.destination,
        )
        val now = nowIso()
        mergeReimportIntoCapture(target, input, strokes, now, syncRemote = true)
        db.offlineImportWarnings().setStatus(id, OfflineImportStatus.MERGED, now)
        return true
    }

    // --- Modo LOCAL: archivado en el propio dispositivo (sin servidor) ---------

    /**
     * Ingesta una página en modo local: resuelve/crea el cuaderno por bookKey,
     * deduplica por SOBP (append-only por startTime + nº de dots) y archiva la
     * hoja en su cuaderno. NO usa outbox ni servidor.
     */
    suspend fun ingestPenPageLocal(
        page: StrokePage,
        reimportPolicy: OfflineReimportPolicy = OfflineReimportPolicy.ASK,
    ) = penIngestion.withLock { ingestPenPageLocalUnlocked(page, reimportPolicy) }

    private suspend fun ingestPenPageLocalUnlocked(page: StrokePage, reimportPolicy: OfflineReimportPolicy, binding: StudioBookDto? = null, bindingFrozen: Boolean = false) {
        val s = page.sobp
        val knownPens = studio.cachedPens()
        val origin = canonicalPenId(page.penId, knownPens) ?: page.penId?.let(::normalizePenIdentifier)
        val assigned = if (bindingFrozen) binding else studio.resolve(s.section, s.owner, s.book, page.penId)
        val staleBinding = assigned != null && bindingFrozen && studio.cachedBooks().none { it.instanceId == assigned.instanceId && it.assignmentId == assigned.assignmentId && it.status == "ready" }
        val studioCode = studio.hasCode(s.section, s.owner, s.book)
        val bookKey = assigned?.instanceId?.let { "instance:$it" } ?: "${s.section}.${s.owner}.${s.book}"
        val detected = assigned?.paper() ?: if (studioCode) null else PaperLookup.lookup(s.section, s.owner, s.book, s.page)
        val incoming = page.strokes.map { st ->
            StrokeDto(st.startTime, st.endTime, st.dots.map { DotDto(it.x, it.y, it.f, it.t) }, st.color, st.width, canonicalPenId(st.penId, knownPens) ?: st.penId?.let(::normalizePenIdentifier) ?: origin)
        }
        val now = nowIso()
        val name = "Nota ${s.section}.${s.owner}.${s.book}.${s.page}"
        val input = CreateCaptureInput(
            sobp = SobpDto(s.section, s.owner, s.book, s.page),
            source = page.source,
            penId = origin, bookInstanceId = assigned?.instanceId,
            assignmentId = assigned?.assignmentId, assignmentRevision = assigned?.assignmentRevision,
            capturedAt = page.capturedAt,
            name = name,
            strokes = incoming,
            paper = detected,
        )
        if (page.source == "offline" && handleOfflineReimportIfNeeded(page, incoming, detected, name, input, reimportPolicy, now, syncRemote = false)) {
            return
        }
        val nb = db.notebooks().findByBookKey(bookKey) ?: createLocalNotebook(bookKey, detected)
        val existing = if (staleBinding) null else assigned?.instanceId?.let { db.captures().findByInstance(it, s.page) }
            ?: if (!studioCode) db.captures().findBySobp(s.section, s.owner, s.book, s.page) else null
        if (existing != null) {
            val cur = Json.strokes(existing.strokesJson)
            val fresh = freshReimportStrokes(cur, incoming)
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
                    name = name,
                    strokesJson = Json.strokesJson(incoming),
                    penId = origin, bookInstanceId = assigned?.instanceId, assignmentId = assigned?.assignmentId, assignmentRevision = assigned?.assignmentRevision,
                    destination = if (staleBinding || (studioCode && assigned == null)) "inbox" else "notebook",
                    sealed = staleBinding,
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
     * Snapshot duradero de la biblioteca local. Cada registro conserva su id;
     * los reintentos no duplican hojas ni fusionan revisiones por SOBP.
     * Las filas de Room nunca se borran para realizar la vinculación.
     */
    suspend fun prepareLinkMigration(serverUrl: String) = db.withTransaction {
        val pending = db.outbox().peek(Int.MAX_VALUE)
        val migration = pending.filter { it.kind == OutboxKind.MIGRATE }
        if (migration.isNotEmpty()) {
            check(migration.all { it.targetId?.let(::normalizedServerUrl) == normalizedServerUrl(serverUrl) }) {
                "Hay una migración pendiente hacia otro servidor"
            }
        }
        val notebooks = db.notebooks().getAll().map { it.toDto() }
        val captures = db.captures().getAll().map { it.toDto() }
        // Rehacer el snapshot también recoge ediciones/ingestas LOCAL hechas
        // después de un intento fallido. Reenviar ids confirmados es idempotente.
        // El snapshot ya incluye las altas/ediciones/ordenaciones optimistas.
        db.outbox().clear()
        for (nb in notebooks) enqueue(OutboxKind.MIGRATE, serverUrl,
            Json.migration.toJson(net.micapum.neopen.core.data.MigrationDto(notebooks = listOf(nb))))
        for (cap in captures) enqueue(OutboxKind.MIGRATE, serverUrl,
            Json.migration.toJson(net.micapum.neopen.core.data.MigrationDto(captures = listOf(cap))))
        // Un borrado no tiene fila en el snapshot, pero sí intención pendiente.
        for (op in pending.filter { it.kind == OutboxKind.DELETE_CAPTURE || it.kind == OutboxKind.DELETE_NOTEBOOK }) {
            enqueue(op.kind, op.targetId, op.payloadJson)
        }
    }

    suspend fun resetCursor() {
        val meta = db.syncMeta().get() ?: SyncMetaEntity()
        db.syncMeta().upsert(meta.copy(cursor = 0))
    }

    private suspend fun enqueue(kind: String, targetId: String?, payloadJson: String) {
        db.outbox().insert(
            OutboxEntity(kind = kind, targetId = targetId, payloadJson = payloadJson, createdAt = System.currentTimeMillis()),
        )
    }

    private suspend fun recordPageAction(
        capture: CaptureEntity,
        action: String,
        overrideNotebookId: String? = null,
        overrideNotebookName: String? = null,
    ) {
        if (capture.clonedFrom != null) return
        val notebookName = overrideNotebookName
            ?: overrideNotebookId?.let { db.notebooks().getById(it)?.name }
            ?: capture.notebookId?.let { db.notebooks().getById(it)?.name }
            ?: capture.notebook
        db.penPageUserActions().upsert(
            PenPageUserActionEntity(
                sobpKey = capture.bookInstanceId?.let { "$it:${capture.page}" } ?: sobpKey(capture.section, capture.owner, capture.book, capture.page),
                section = capture.section,
                owner = capture.owner,
                book = capture.book,
                page = capture.page,
                action = action,
                captureId = capture.id,
                captureName = capture.name,
                destination = if (overrideNotebookId != null) "notebook" else capture.destination,
                notebookId = overrideNotebookId ?: capture.notebookId,
                notebookName = notebookName,
                paperJson = capture.paperJson,
                createdAt = nowIso(),
            ),
        )
    }

    private suspend fun createCaptureFromReimport(
        input: CreateCaptureInput,
        strokes: List<StrokeDto>,
        paper: PaperDto?,
        page: StrokePage,
        name: String,
        now: String,
        syncRemote: Boolean,
    ) {
        val entity = CaptureEntity(
            id = "local-" + UUID.randomUUID(),
            rev = 0,
            section = page.sobp.section,
            owner = page.sobp.owner,
            book = page.sobp.book,
            page = page.sobp.page,
            notebook = null,
            source = page.source,
            capturedAt = page.capturedAt,
            importedAt = now,
            updatedAt = now,
            name = name,
            strokesJson = Json.strokesJson(strokes),
            paperJson = Json.paperJson(paper),
            penId = input.penId, bookInstanceId = input.bookInstanceId, assignmentId = input.assignmentId, assignmentRevision = input.assignmentRevision,
            destination = "inbox",
        )
        if (syncRemote) enqueueCreateCapture(input.copy(mergeMode = "forceNew"), entity) else db.captures().upsertOne(entity)
    }

    private suspend fun mergeReimportIntoCapture(
        target: CaptureEntity,
        input: CreateCaptureInput,
        incoming: List<StrokeDto>,
        now: String,
        syncRemote: Boolean,
    ) {
        val cur = Json.strokes(target.strokesJson)
        val fresh = freshReimportStrokes(cur, incoming)
        if (fresh.isNotEmpty()) {
            val overlapHit = detectOverlap(cur, fresh, overlapConfig)
            val overlapJson = if (overlapHit != null) Json.overlapJson(overlapHit) else target.overlapJson
            db.captures().upsertOne(
                target.copy(
                    strokesJson = Json.strokesJson(cur + fresh),
                    overlapJson = overlapJson,
                    transcriptionJson = null,
                    updatedAt = now,
                    importedAt = now,
                ),
            )
        }
        if (syncRemote) enqueue(OutboxKind.CREATE_CAPTURE, target.id,
            Json.createCapture.toJson(input.copy(mergeMode = "mergeTarget", targetCaptureId = target.id)))
    }

    private fun warningEntity(
        id: String,
        page: StrokePage,
        strokes: List<StrokeDto>,
        paper: PaperDto?,
        name: String,
        action: PenPageUserActionEntity,
        target: CaptureEntity?,
        now: String,
    ): OfflineImportWarningEntity =
        OfflineImportWarningEntity(
            id = id,
            section = page.sobp.section,
            owner = page.sobp.owner,
            book = page.sobp.book,
            page = page.sobp.page,
            source = page.source,
            capturedAt = page.capturedAt,
            importedAt = now,
            name = name,
            strokesJson = Json.strokesJson(strokes),
            paperJson = Json.paperJson(paper ?: Json.paperOf(action.paperJson)),
            reasonAction = action.action,
            actionCaptureId = action.captureId,
            actionCaptureName = action.captureName,
            actionDestination = action.destination,
            actionNotebookId = action.notebookId,
            actionNotebookName = action.notebookName,
            actionAt = action.createdAt,
            targetCaptureId = target?.id,
        )

    private fun OfflineImportWarningEntity.toStrokePage(): StrokePage =
        StrokePage(
            sobp = net.micapum.neopen.core.pen.Sobp(section, owner, book, page),
            source = source,
            capturedAt = capturedAt,
            strokes = emptyList(),
        )

    private fun sobpKey(section: Int, owner: Int, book: Int, page: Int): String =
        "$section.$owner.$book.$page"

    private fun offlineWarningId(section: Int, owner: Int, book: Int, page: Int, strokes: List<StrokeDto>): String {
        val raw = buildString {
            append(sobpKey(section, owner, book, page))
            strokes.sortedBy { it.startTime }.forEach { st ->
                append('|').append(st.penId).append(':').append(st.startTime).append(':').append(st.endTime).append(':').append(st.dots.size)
            }
        }
        return "offline-" + sha1(raw)
    }

    private fun sha1(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // --- Drenaje del outbox (lo usa SyncEngine.push) ---------------------------

    /** La resolución explícita confirma primero el destino antes de retirar la captura pendiente. */
    suspend fun confirmStudioConflict(result: net.micapum.neopen.core.data.StudioConflictResolutionDto) = db.withTransaction {
        val pending = db.outbox().peek(Int.MAX_VALUE).filter { it.kind == OutboxKind.CREATE_CAPTURE && it.lastError?.contains(result.conflict.id) == true }
        for (op in pending) {
            if (result.capture != null) acknowledge(op, capture = result.capture)
            else {
                op.targetId?.let { db.captures().setSealed(it, true, nowIso()) }
                db.outbox().deleteById(op.localId)
            }
        }
        result.capture?.let { applyServerCapture(it) }
    }

    suspend fun outboxPeek(limit: Int): List<OutboxEntity> = db.outbox().peek(limit)
    suspend fun outboxDelete(localId: Long) = db.outbox().deleteById(localId)
    suspend fun outboxMarkFailed(localId: Long, error: String?) = db.outbox().markFailed(localId, error)
    suspend fun outboxRepoint(oldId: String, newId: String) {
        if (oldId == newId) return
        for (op in db.outbox().peek(Int.MAX_VALUE)) {
            val payload = when (op.kind) {
                OutboxKind.CREATE_CAPTURE -> Json.createCapture.fromJson(op.payloadJson)?.let {
                    Json.createCapture.toJson(it.copy(
                        notebookId = if (it.notebookId == oldId) newId else it.notebookId,
                        targetCaptureId = if (it.targetCaptureId == oldId) newId else it.targetCaptureId,
                    ))
                }
                OutboxKind.PATCH_CAPTURE -> Json.capturePatch.fromJson(op.payloadJson)?.let {
                    Json.capturePatch.toJson(it.copy(notebookId = if (it.notebookId == oldId) newId else it.notebookId))
                }
                OutboxKind.REORDER_NOTEBOOK -> Json.reorder.fromJson(op.payloadJson)?.let {
                    Json.reorder.toJson(it.copy(order = it.order.map { id -> if (id == oldId) newId else id }))
                }
                else -> null
            }
            if (payload != null && payload != op.payloadJson) db.outbox().updatePayload(op.localId, payload)
        }
        db.outbox().repointTarget(oldId, newId)
    }

    /** La confirmación y el repunte se confirman juntos; nunca queda un CREATE a medias. */
    suspend fun acknowledge(op: OutboxEntity, capture: CaptureDto? = null, notebook: NotebookDto? = null) = db.withTransaction {
        if (capture != null) {
            if (op.kind == OutboxKind.CREATE_CAPTURE && op.targetId != null) replaceTempCapture(op.targetId, capture)
            else applyServerCapture(capture)
        }
        if (notebook != null) {
            if (op.kind == OutboxKind.CREATE_NOTEBOOK && op.targetId != null) replaceTempNotebook(op.targetId, notebook)
            else applyServerNotebook(notebook)
        }
        db.outbox().deleteById(op.localId)
        // La respuesta confirma esta operación, no las ediciones hechas después.
        if (capture != null) replayPendingEdits(capture.id)
        if (notebook != null) replayPendingEdits(notebook.id)
    }

    private suspend fun replayPendingEdits(targetId: String) {
        for (pending in db.outbox().forTarget(targetId)) {
            val id = pending.targetId ?: continue
            when (pending.kind) {
                OutboxKind.PATCH_CAPTURE -> {
                    val c = db.captures().getById(id) ?: continue
                    val p = Json.capturePatch.fromJson(pending.payloadJson) ?: continue
                    db.captures().upsertOne(c.copy(
                        name = p.name ?: c.name, tagsJson = p.tags?.let(Json::tagsJson) ?: c.tagsJson,
                        destination = p.destination ?: c.destination, notebookId = p.notebookId ?: c.notebookId,
                        position = p.position ?: c.position, kind = p.kind ?: c.kind, sealed = p.sealed ?: c.sealed,
                        paperJson = if (p.paperSpecified || p.paper != null) Json.paperJson(p.paper) else c.paperJson,
                    ))
                }
                OutboxKind.PUSH_TRANSCRIPTION -> {
                    val c = db.captures().getById(id) ?: continue
                    val p = Json.transcriptionPush.fromJson(pending.payloadJson) ?: continue
                    db.captures().upsertOne(c.copy(transcriptionJson = Json.ocrJson(p.transcription),
                        name = p.name ?: c.name, tagsJson = p.tags?.let(Json::tagsJson) ?: c.tagsJson, kind = p.kind ?: c.kind))
                }
                OutboxKind.PATCH_NOTEBOOK -> {
                    val n = db.notebooks().getById(id) ?: continue
                    val p = Json.notebookPatch.fromJson(pending.payloadJson) ?: continue
                    db.notebooks().upsertOne(n.copy(name = p.name ?: n.name, color = p.color ?: n.color,
                        tagsJson = p.tags?.let(Json::tagsJson) ?: n.tagsJson, category = p.category ?: n.category))
                }
                OutboxKind.DELETE_CAPTURE -> db.captures().deleteById(id)
                OutboxKind.DELETE_NOTEBOOK -> db.notebooks().deleteById(id)
            }
        }
    }
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
            outboxRepoint(tempId, dto.id)
            db.captures().repointNotebook(tempId, dto.id)
            db.notebooks().deleteById(tempId)
        }
        db.notebooks().upsertOne(dto.toEntity())
    }

    /** Sustituye una captura temporal por la real del servidor (mismo SOBP). */
    suspend fun replaceTempCapture(tempId: String, dto: CaptureDto) {
        if (tempId != dto.id) {
            outboxRepoint(tempId, dto.id)
            db.captures().deleteById(tempId)
        }
        db.captures().upsertOne(dto.toEntity())
    }
}
