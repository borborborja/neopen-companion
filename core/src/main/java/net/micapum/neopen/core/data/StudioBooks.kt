package net.micapum.neopen.core.data

/** Contrato de libros: receta y perfiles se conservan íntegros para vistas históricas. */
data class StudioPenScopeDto(val mode: String = "all", val excludedPenIds: List<String> = emptyList(), val penIds: List<String> = emptyList()) {
    fun contains(id: String) = if (mode == "all") id !in excludedPenIds else id in penIds
    fun overlaps(other: StudioPenScopeDto): Boolean = when {
        mode == "all" && other.mode == "all" -> true
        mode == "selected" -> penIds.any(other::contains)
        else -> other.penIds.any(::contains)
    }
}
data class StudioProfileDto(val id: String, val version: Int = 1, val validation: String = "corpus", val label: String = "", val section: Int = 0, val owner: Int = 0, val bookMin: Int = 0, val bookMax: Int = 0, val pageMin: Int = 0, val pageMax: Int = 511, val coordinateMax: Int = 127, val dpi: Int = 600, val patternValidated: Boolean = false, val penValidated: Boolean = false, val sources: List<String> = emptyList())
data class StudioSectionDto(val id: String, val label: String = "", val kind: String, val config: Map<String, Any?>? = null, val assetId: String? = null, val fromPage: Int? = null, val toPage: Int? = null, val pageCount: Int? = null)
data class StudioRecipeDto(val version: Int = 1, val format: String = "A4", val sections: List<StudioSectionDto> = emptyList(), val cover: Map<String, Any?>? = null, val numbering: Map<String, Any?>? = null, val print: Map<String, Any?>? = null, val eventsByDay: Map<String, Any?>? = null, val borderSeed: Long = 0)
data class StudioInstanceDto(val id: String, val bookId: String, val notebookId: String, val createdAt: String, val closedAt: String? = null, val recipe: StudioRecipeDto, val profileId: String, val name: String, val section: Int, val owner: Int, val book: Int, val format: String, val designAssetId: String? = null, val pdfAssetId: String? = null, val profileSnapshot: StudioProfileDto? = null)
data class StudioBookDto(val id: String, val rev: Long, val name: String, val profileId: String, val section: Int, val owner: Int, val book: Int, val format: String, val recipe: StudioRecipeDto, val pageCount: Int, val status: String, val archived: Boolean = false, val penScope: StudioPenScopeDto = StudioPenScopeDto(), val instanceId: String? = null, val notebookId: String? = null, val pdfAssetId: String? = null, val designAssetId: String? = null, val previewAssetId: String? = null, val createdAt: String, val updatedAt: String, val deletedAt: String? = null, val assignmentId: String? = null, val assignmentRevision: Long? = null, val instances: List<StudioInstanceDto> = emptyList(), val profileSnapshot: StudioProfileDto? = null) {
    val code get() = "$section.$owner.$book"
}
data class StudioBookPatchDto(val rev: Long, val name: String? = null, val archived: Boolean? = null, val penScope: StudioPenScopeDto? = null)
data class StudioTransferDto(val expectedRev: Long, val fromBookId: String, val fromRev: Long, val penIds: List<String>)

/** Solo se normalizan MAC completas; otros identificadores requieren coincidencia exacta. */
fun normalizePenIdentifier(value: String): String {
    val trimmed = value.trim()
    val compact = trimmed.replace(":", "").replace("-", "")
    return if (Regex("[0-9a-fA-F]{12}").matches(compact)) compact.uppercase().chunked(2).joinToString(":") else trimmed
}
fun canonicalPenId(identifier: String?, pens: List<PenInfoDto>): String? {
    val normalized = identifier?.let(::normalizePenIdentifier) ?: return null
    return pens.singleOrNull { p -> p.id == normalized || (p.aliases + p.mac).any { normalizePenIdentifier(it) == normalized } }?.id
}

data class StudioCaptureConflictDto(val id: String, val code: String, val createdAt: String, val input: CreateCaptureInput, val status: String, val captureId: String? = null)
data class StudioConflictResolveDto(val action: String, val penId: String? = null, val bookInstanceId: String? = null)
data class StudioConflictResolutionDto(val conflict: StudioCaptureConflictDto, val capture: CaptureDto? = null)

/** ISO físico generado localmente; no consulta catálogos NeoLAB. */
fun StudioBookDto.paper(): PaperDto {
    val width = if (format.equals("a5", true)) 148f else 210f
    val height = if (format.equals("a5", true)) 210f else 297f
    return PaperDto(0f, 0f, width / 2.371f, height / 2.371f, "studio")
}

/** Encoder disponible en esta versión. Los límites se obtienen del perfil sincronizado. */
const val STUDIO_ENABLED_PROFILE_ID = "s3-o27-book-low6-v1"

data class StudioCodeAvailability(
    val total: Int,
    val used: Int,
    val available: Int,
)

/** Los ámbitos Todos reservan también los bolígrafos futuros, aunque hoy estén excluidos todos. */
fun studioCodeAvailability(books: List<StudioBookDto>, profile: StudioProfileDto): StudioCodeAvailability {
    val total = (profile.bookMax - profile.bookMin + 1).coerceAtLeast(0)
    val used = books.asSequence().filter { book ->
        book.status != "deleted" &&
            (book.penScope.mode == "all" || book.penScope.penIds.isNotEmpty()) &&
            book.section == profile.section && book.owner == profile.owner &&
            book.book in profile.bookMin..profile.bookMax
    }.map { it.book }.distinct().count()
    return StudioCodeAvailability(total, used, total - used)
}
