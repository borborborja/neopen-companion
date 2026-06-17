package net.micapum.neopen.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.db.NotebookEntity
import net.micapum.neopen.app.db.OutboxEntity
import net.micapum.neopen.app.db.OfflineImportWarningEntity
import net.micapum.neopen.app.db.PageBackgroundEntity
import net.micapum.neopen.app.db.PenPageUserActionEntity
import net.micapum.neopen.app.db.SyncMetaEntity
import net.micapum.neopen.core.data.AnchorDto
import net.micapum.neopen.core.data.CapturePatchDto
import net.micapum.neopen.core.data.CaptureDto
import net.micapum.neopen.core.data.ChangesResponseDto
import net.micapum.neopen.core.data.CreateCaptureInput
import net.micapum.neopen.core.data.NeopenApi
import net.micapum.neopen.core.data.NotebookCreateDto
import net.micapum.neopen.core.data.DotDto
import net.micapum.neopen.core.data.OfflineReimportDecision
import net.micapum.neopen.core.data.OfflineReimportPolicy
import net.micapum.neopen.core.data.NotebookDto
import net.micapum.neopen.core.data.NotebookPaperBody
import net.micapum.neopen.core.data.OcrResultDto
import net.micapum.neopen.core.data.TranscriptionPushDto
import net.micapum.neopen.core.data.NotebookPatchDto
import net.micapum.neopen.core.data.OverlapConfig
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.detectOverlap
import net.micapum.neopen.core.data.freshReimportStrokes
import net.micapum.neopen.core.data.ReorderDto
import net.micapum.neopen.core.data.resolveOfflineReimport
import net.micapum.neopen.core.data.SobpDto
import net.micapum.neopen.core.data.StrokeDto
import net.micapum.neopen.core.pen.PaperLookup
import net.micapum.neopen.core.pen.Sobp
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

enum class PhotoScanDestination { MERGE_CAPTURE, NOTEBOOK, INBOX }

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

    fun offlineImportWarnings(): Flow<List<OfflineImportWarning>> =
        db.offlineImportWarnings().observePending().map { rows -> rows.map { it.toDomain() } }

    fun pendingOfflineImportWarningCount(): Flow<Int> =
        db.offlineImportWarnings().observePendingCount()

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
        db.captures().clear()
        db.notebooks().clear()
        db.outbox().clear()
        db.backgrounds().clear()
        db.penPageUserActions().clear()
        db.offlineImportWarnings().clear()
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
        val e = db.captures().getById(id)
        db.withTransaction {
            e?.let { recordPageAction(it, PenPageAction.DELETED) }
            db.captures().deleteById(id)
            enqueue(OutboxKind.DELETE_CAPTURE, id, "{}")
        }
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

    suspend fun photoScanTarget(sobp: Sobp): PhotoScanTarget {
        val paper = PaperLookup.lookup(sobp.section, sobp.owner, sobp.book, sobp.page)
        val capture = db.captures().findBySobp(sobp.section, sobp.owner, sobp.book, sobp.page)?.toDomain()
        val notebook = db.notebooks().findByBookKey(sobp.bookKey)?.let { nb ->
            nb.toDomain(pageCount = db.captures().getByNotebook(nb.id).size)
        }
        return PhotoScanTarget(capture, notebook, paper)
    }

    suspend fun savePhotoScan(
        sobp: Sobp,
        strokes: List<StrokeDto>,
        paper: PaperDto?,
        destination: PhotoScanDestination,
        targetCaptureId: String?,
        targetNotebookId: String?,
        syncRemote: Boolean,
    ): Boolean {
        if (strokes.isEmpty()) return false
        val now = nowIso()
        val name = "Escaneo ${sobp.section}.${sobp.owner}.${sobp.book}.${sobp.page}"
        val inputBase = CreateCaptureInput(
            sobp = SobpDto(sobp.section, sobp.owner, sobp.book, sobp.page),
            source = "photo_scan",
            capturedAt = now,
            name = name,
            strokes = strokes,
            paper = paper,
        )
        if (destination == PhotoScanDestination.MERGE_CAPTURE && targetCaptureId != null) {
            val target = db.captures().getById(targetCaptureId)?.takeIf { it.clonedFrom == null && !it.sealed } ?: return false
            val fresh = freshPhotoScanStrokes(Json.strokes(target.strokesJson), strokes)
            if (fresh.isNotEmpty()) {
                db.captures().upsertOne(
                    target.copy(
                        strokesJson = Json.strokesJson(Json.strokes(target.strokesJson) + fresh),
                        transcriptionJson = null,
                        updatedAt = now,
                        importedAt = now,
                    ),
                )
            }
            if (syncRemote) {
                enqueue(
                    OutboxKind.CREATE_CAPTURE,
                    target.id,
                    Json.createCapture.toJson(
                        inputBase.copy(
                            strokes = fresh.ifEmpty { strokes },
                            notebookId = target.notebookId,
                            destination = target.destination,
                        ),
                    ),
                )
            }
            return true
        }

        val notebook = when {
            destination == PhotoScanDestination.NOTEBOOK && targetNotebookId != null -> db.notebooks().getById(targetNotebookId)
            !syncRemote -> db.notebooks().findByBookKey(sobp.bookKey) ?: createLocalNotebook(sobp.bookKey, paper)
            else -> null
        }
        val captureId = "local-" + UUID.randomUUID()
        val position = notebook?.let { db.captures().getByNotebook(it.id).size } ?: 0
        val entity = CaptureEntity(
            id = captureId,
            rev = 0,
            section = sobp.section,
            owner = sobp.owner,
            book = sobp.book,
            page = sobp.page,
            notebook = notebook?.name,
            source = "photo_scan",
            capturedAt = now,
            importedAt = now,
            updatedAt = now,
            name = name,
            strokesJson = Json.strokesJson(strokes),
            destination = if (notebook != null) "notebook" else "inbox",
            notebookId = notebook?.id,
            position = position,
            paperJson = Json.paperJson(paper ?: Json.paperOf(notebook?.paperJson)),
        )
        val input = inputBase.copy(
            notebook = notebook?.name,
            notebookId = notebook?.id,
            destination = if (notebook != null) "notebook" else null,
            paper = paper ?: Json.paperOf(notebook?.paperJson),
        )
        if (syncRemote) enqueueCreateCapture(input, entity) else db.captures().upsertOne(entity)
        return true
    }

    /**
     * Ingesta una página capturada por el boli: la muestra al instante en la
     * bandeja (Room optimista) y la encola para subir (el servidor la archiva
     * por SOBP). Mismo flujo de subida que el companion, pero offline-first.
     */
    suspend fun ingestPenPage(
        page: StrokePage,
        reimportPolicy: OfflineReimportPolicy = OfflineReimportPolicy.ASK,
    ) {
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
        val action = db.penPageUserActions().getBySobpKey(sobpKey(s.section, s.owner, s.book, s.page)) ?: return false
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
    ) {
        val s = page.sobp
        val bookKey = "${s.section}.${s.owner}.${s.book}"
        val detected = PaperLookup.lookup(s.section, s.owner, s.book, s.page)
        val incoming = page.strokes.map { st ->
            StrokeDto(st.startTime, st.endTime, st.dots.map { DotDto(it.x, it.y, it.f, it.t) }, st.color, st.width)
        }
        val now = nowIso()
        val name = "Nota ${s.section}.${s.owner}.${s.book}.${s.page}"
        val input = CreateCaptureInput(
            sobp = SobpDto(s.section, s.owner, s.book, s.page),
            source = page.source,
            capturedAt = page.capturedAt,
            name = name,
            strokes = incoming,
            paper = detected,
        )
        if (page.source == "offline" && handleOfflineReimportIfNeeded(page, incoming, detected, name, input, reimportPolicy, now, syncRemote = false)) {
            return
        }
        val nb = db.notebooks().findByBookKey(bookKey) ?: createLocalNotebook(bookKey, detected)
        val existing = db.captures().findBySobp(s.section, s.owner, s.book, s.page)
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
                sobpKey = sobpKey(capture.section, capture.owner, capture.book, capture.page),
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
            destination = "inbox",
        )
        if (syncRemote) enqueueCreateCapture(input, entity) else db.captures().upsertOne(entity)
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
        if (syncRemote) enqueue(OutboxKind.CREATE_CAPTURE, target.id, Json.createCapture.toJson(input))
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
                append('|').append(st.startTime).append(':').append(st.endTime).append(':').append(st.dots.size)
            }
        }
        return "offline-" + sha1(raw)
    }

    private fun freshPhotoScanStrokes(existing: List<StrokeDto>, incoming: List<StrokeDto>): List<StrokeDto> =
        incoming.filter { n -> existing.none { sameGeometry(it, n) } }

    private fun sameGeometry(a: StrokeDto, b: StrokeDto): Boolean {
        val ad = a.dots
        val bd = b.dots
        if (ad.isEmpty() || bd.isEmpty()) return false
        val af = ad.first()
        val al = ad.last()
        val bf = bd.first()
        val bl = bd.last()
        val start = kotlin.math.hypot((af.x - bf.x).toDouble(), (af.y - bf.y).toDouble())
        val end = kotlin.math.hypot((al.x - bl.x).toDouble(), (al.y - bl.y).toDouble())
        return start < 1.2 && end < 1.2 && kotlin.math.abs(ad.size - bd.size) <= 2
    }

    private fun sha1(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
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
