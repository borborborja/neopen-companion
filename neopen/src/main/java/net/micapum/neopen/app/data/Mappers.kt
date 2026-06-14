package net.micapum.neopen.app.data

import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.NotebookEntity
import net.micapum.neopen.core.data.CaptureDto
import net.micapum.neopen.core.data.NotebookDto

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
)

// --- Entity (Room) → dominio (UI) ---

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
)

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
    pageCount = pageCount,
)
