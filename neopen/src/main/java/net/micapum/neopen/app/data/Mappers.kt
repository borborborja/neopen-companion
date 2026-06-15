package net.micapum.neopen.app.data

import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.NotebookEntity
import net.micapum.neopen.app.db.PageBackgroundEntity
import net.micapum.neopen.core.data.AnchorDto
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
    sealed = sealed,
    overlapJson = Json.overlapJson(overlap),
    clonedFrom = clonedFrom,
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
