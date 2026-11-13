package net.micapum.neopen.app.data

import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.CaptureSummaryRow
import net.micapum.neopen.app.db.NotebookEntity
import net.micapum.neopen.app.db.OfflineImportWarningEntity
import net.micapum.neopen.app.db.PageBackgroundEntity
import net.micapum.neopen.core.data.AnchorDto
import net.micapum.neopen.core.data.CaptureDto
import net.micapum.neopen.core.data.NotebookDto
import java.security.MessageDigest

// --- DTO (servidor) → Entity (Room) ---

fun CaptureDto.toEntity(): CaptureEntity = CaptureEntity(
    id = id,
    rev = rev,
    section = sobp.section,
    owner = sobp.owner,
    book = sobp.book,
    page = sobp.page,
    notebook = notebook,
    source = source,
    capturedAt = capturedAt,
    importedAt = importedAt,
    updatedAt = updatedAt ?: importedAt,
    name = name,
    tagsJson = Json.tagsJson(tags),
    strokesJson = Json.strokesJson(strokes),
    transcriptionJson = Json.ocrJson(transcription),
    destination = destination,
    notebookId = notebookId,
    position = position,
    paperJson = Json.paperJson(paper),
    kind = kind,
    sealed = sealed,
    overlapJson = Json.overlapJson(overlap),
    clonedFrom = clonedFrom,
    penId = penId, bookInstanceId = bookInstanceId, assignmentId = assignmentId, assignmentRevision = assignmentRevision,
)

/** Se calcula en las lecturas de detalle, fuera de Main, sin usar equals de miles de puntos. */
internal fun captureVisualFingerprint(strokesJson: String, paperJson: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(strokesJson.toByteArray(Charsets.UTF_8))
    digest.update(0.toByte())
    digest.update((paperJson ?: "null").toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(64) {
        for (byte in digest.digest()) {
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4]); append(hex[value and 15])
        }
    }
}

fun CaptureSummaryRow.toDomain() = CaptureSummary(
    id, rev, section, owner, book, page, source, capturedAt, importedAt, updatedAt,
    name, Json.tags(tagsJson), destination, notebookId, position, Json.paperOf(paperJson), kind,
    sealed, Json.overlapOf(overlapJson), clonedFrom, penId, bookInstanceId, assignmentId, assignmentRevision,
)

fun NotebookDto.toEntity(): NotebookEntity = NotebookEntity(
    id = id,
    rev = rev,
    name = name,
    color = color,
    tagsJson = Json.tagsJson(tags),
    createdAt = createdAt,
    updatedAt = updatedAt,
    paperJson = Json.paperJson(paper),
    paperSource = paperSource,
    bookKey = bookKey,
    coverUrl = coverUrl,
    unclassified = unclassified,
    custom = custom,
    category = category,
)

// --- Entity (Room) → dominio (UI) ---

/** Snapshot completo para migrar sin perder OCR, revisiones ni propiedades de archivo. */
fun CaptureEntity.toDto() = CaptureDto(
    id = id, rev = rev, sobp = net.micapum.neopen.core.data.SobpDto(section, owner, book, page),
    notebook = notebook, source = source, capturedAt = capturedAt, importedAt = importedAt,
    updatedAt = updatedAt, name = name, tags = Json.tags(tagsJson), strokes = Json.strokes(strokesJson),
    transcription = Json.ocrOf(transcriptionJson), destination = destination, notebookId = notebookId,
    position = position, paper = Json.paperOf(paperJson), kind = kind, sealed = sealed,
    overlap = Json.overlapOf(overlapJson), clonedFrom = clonedFrom,
    penId = penId, bookInstanceId = bookInstanceId, assignmentId = assignmentId, assignmentRevision = assignmentRevision,
)

fun NotebookEntity.toDto() = NotebookDto(
    id = id, rev = rev, name = name, color = color, tags = Json.tags(tagsJson),
    createdAt = createdAt, updatedAt = updatedAt, paper = Json.paperOf(paperJson), paperSource = paperSource,
    bookKey = bookKey, coverUrl = coverUrl, unclassified = unclassified, custom = custom, category = category,
)

fun CaptureEntity.toDomain(): Capture = Capture(
    id = id,
    rev = rev,
    section = section,
    owner = owner,
    book = book,
    page = page,
    source = source,
    capturedAt = capturedAt,
    importedAt = importedAt,
    updatedAt = updatedAt,
    name = name,
    tags = Json.tags(tagsJson),
    strokes = Json.strokes(strokesJson),
    transcription = Json.ocrOf(transcriptionJson),
    destination = destination,
    notebookId = notebookId,
    position = position,
    paper = Json.paperOf(paperJson),
    kind = kind,
    sealed = sealed,
    overlap = Json.overlapOf(overlapJson),
    clonedFrom = clonedFrom,
    penId = penId, bookInstanceId = bookInstanceId, assignmentId = assignmentId, assignmentRevision = assignmentRevision,
    visualFingerprint = captureVisualFingerprint(strokesJson, paperJson),
)

fun OfflineImportWarningEntity.toDomain(): OfflineImportWarning = OfflineImportWarning(
    id = id,
    section = section,
    owner = owner,
    book = book,
    page = page,
    capturedAt = capturedAt,
    importedAt = importedAt,
    name = name,
    strokes = Json.strokes(strokesJson),
    paper = Json.paperOf(paperJson),
    reasonAction = reasonAction,
    actionCaptureName = actionCaptureName,
    actionDestination = actionDestination,
    actionNotebookName = actionNotebookName,
    targetCaptureId = targetCaptureId,
)

fun PageBackgroundEntity.toDomain(): PageBackground = PageBackground(
    id = id,
    bookKey = bookKey,
    page = page,
    scope = scope,
    bgPreset = bgPreset,
    bgImage = bgImage,
    label = label,
    source = source,
    remote = remote,
    bookInstanceId = bookInstanceId, assignmentId = assignmentId,
    createdAt = createdAt,
)

/** DesignAnchor del servidor → fila local (remote=true). El ámbito "page" deduce
 *  su página de la primera clave de pageBySobp. */
fun AnchorDto.toBackgroundEntity(): PageBackgroundEntity {
    val sc = scope ?: "page"
    val pg = if (sc == "page") pageBySobp?.keys?.firstOrNull()?.toIntOrNull() else null
    return PageBackgroundEntity(
        id = id,
        bookKey = bookKey,
        page = pg,
        scope = sc,
        bgPreset = bgPreset,
        bgImage = bgImage,
        label = label,
        source = source,
        remote = true,
        createdAt = createdAt,
        bookInstanceId = bookInstanceId, assignmentId = assignmentId,
    )
}

fun NotebookEntity.toDomain(pageCount: Int = 0): Notebook = Notebook(
    id = id,
    rev = rev,
    name = name,
    color = color,
    tags = Json.tags(tagsJson),
    createdAt = createdAt,
    updatedAt = updatedAt,
    paper = Json.paperOf(paperJson),
    paperSource = paperSource,
    bookKey = bookKey,
    coverUrl = coverUrl,
    unclassified = unclassified,
    custom = custom,
    category = category,
    pageCount = pageCount,
)
