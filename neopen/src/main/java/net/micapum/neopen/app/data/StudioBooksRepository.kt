package net.micapum.neopen.app.data

import androidx.room.withTransaction
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import net.micapum.neopen.app.db.*
import net.micapum.neopen.core.data.*

/** Caché confirmada independiente de las peticiones pendientes del usuario. */
data class CachedStudioBook(val book: StudioBookDto, val pending: Boolean = false, val error: String? = null)
object StudioJson {
    val book = Json.moshi.adapter(StudioBookDto::class.java)
    val patch = Json.moshi.adapter(StudioBookPatchDto::class.java)
    val transfer = Json.moshi.adapter(StudioTransferDto::class.java)
    val alias = Json.moshi.adapter(PenAliasInput::class.java)
    val register = Json.moshi.adapter(RegisterPenInput::class.java)
    val resolve = Json.moshi.adapter(StudioConflictResolveDto::class.java)
    val conflicts = Json.moshi.adapter<List<StudioCaptureConflictDto>>(Types.newParameterizedType(List::class.java, StudioCaptureConflictDto::class.java))
    val profiles = Json.moshi.adapter<List<StudioProfileDto>>(Types.newParameterizedType(List::class.java, StudioProfileDto::class.java))
    val pens = Json.moshi.adapter<List<PenInfoDto>>(Types.newParameterizedType(List::class.java, PenInfoDto::class.java))
}
object StudioOutbox {
    const val PATCH = "studioPatchBook"
    const val DELETE = "studioDeleteBook"
    const val TRANSFER = "studioTransferBook"
    const val ALIAS = "studioLinkPen"
    const val REGISTER = "studioRegisterPen"
    const val RESOLVE = "studioResolveCapture"
    fun requiresStudio(kind: String) = kind == PATCH || kind == DELETE || kind == TRANSFER || kind == RESOLVE
}
class StudioBooksRepository(private val db: NeopenDb) {
    fun books() = db.studio().observeBooks().combine(db.outbox().observeAll()) { rows, ops ->
        rows.mapNotNull { row -> StudioJson.book.fromJson(row.json)?.let { book ->
            val pending = ops.filter { it.targetId == book.id && it.kind.startsWith("studio") }
            CachedStudioBook(book, pending.isNotEmpty(), pending.firstNotNullOfOrNull { it.lastError })
        } }.sortedByDescending { it.book.updatedAt }
    }
    fun pendingConflictIds() = db.outbox().observeAll().map { rows -> rows.filter { it.kind == StudioOutbox.RESOLVE }.mapNotNull { it.targetId }.toSet() }
    fun conflicts() = db.studio().observeMetadata("conflicts").map { it?.json?.let(StudioJson.conflicts::fromJson).orEmpty().filter { it.status == "pending" } }
    suspend fun applyConflicts(rows: List<StudioCaptureConflictDto>) = db.studio().metadata(StudioMetadataEntity("conflicts", StudioJson.conflicts.toJson(rows)))
    suspend fun resolveConflict(id: String, penId: String?, bookInstanceId: String? = null) = enqueue(id, StudioOutbox.RESOLVE, StudioJson.resolve.toJson(StudioConflictResolveDto(if (penId == null && bookInstanceId == null) "discard" else "assign", penId, bookInstanceId)))
    fun profiles() = db.studio().observeMetadata("profiles").map { it?.json?.let(StudioJson.profiles::fromJson).orEmpty() }
    fun availability() = books().combine(profiles()) { rows, profiles ->
        profiles.singleOrNull { it.id == STUDIO_ENABLED_PROFILE_ID && it.patternValidated }
            ?.let { studioCodeAvailability(rows.map { it.book }, it) }
    }
    fun pens() = db.studio().observeMetadata("pens").map { it?.json?.let(StudioJson.pens::fromJson).orEmpty() }
    suspend fun cachedBooks() = db.studio().books().mapNotNull { StudioJson.book.fromJson(it.json) }
    suspend fun cachedPens() = db.studio().metadata("pens")?.json?.let(StudioJson.pens::fromJson).orEmpty()
    suspend fun apply(books: List<StudioBookDto>, pens: List<PenInfoDto>, profiles: List<StudioProfileDto>) = db.withTransaction {
        db.studio().upsert(books.map { StudioBookEntity(it.id, it.rev, StudioJson.book.toJson(it)) })
        db.studio().metadata(StudioMetadataEntity("pens", StudioJson.pens.toJson(pens)))
        db.studio().metadata(StudioMetadataEntity("profiles", StudioJson.profiles.toJson(profiles)))
        books.flatMap { it.instances }.filter { it.closedAt != null }.forEach { db.captures().sealInstance(it.id) }
    }
    suspend fun confirmed(book: StudioBookDto, op: OutboxEntity) = db.withTransaction {
        db.studio().upsert(listOf(StudioBookEntity(book.id, book.rev, StudioJson.book.toJson(book))))
        book.instances.filter { it.closedAt != null }.forEach { db.captures().sealInstance(it.id) }
        db.outbox().deleteById(op.localId)
    }
    suspend fun registerPen(identifier: String, name: String? = null, firmware: String? = null) = db.withTransaction {
        val mac = normalizePenIdentifier(identifier)
        if (canonicalPenId(mac, cachedPens()) != null || db.outbox().forTarget(mac).any { it.kind == StudioOutbox.REGISTER }) return@withTransaction
        db.outbox().insert(OutboxEntity(kind = StudioOutbox.REGISTER, targetId = mac, payloadJson = StudioJson.register.toJson(RegisterPenInput(mac, name, firmware)), createdAt = System.currentTimeMillis()))
    }
    suspend fun replacePens(pens: List<PenInfoDto>) = db.studio().metadata(StudioMetadataEntity("pens", StudioJson.pens.toJson(pens)))
    suspend fun confirmedPen(pen: PenInfoDto) {
        val rows = cachedPens().filterNot { it.id == pen.id || normalizePenIdentifier(it.mac) == normalizePenIdentifier(pen.mac) } + pen
        db.studio().metadata(StudioMetadataEntity("pens", StudioJson.pens.toJson(rows)))
    }
    suspend fun confirmedDeleted(id: String, op: OutboxEntity) = db.withTransaction {
        db.studio().book(id)?.let { row -> StudioJson.book.fromJson(row.json)?.let { book ->
            book.instanceId?.let { db.captures().sealInstance(it) }
            val closed = book.instances.map { if (it.closedAt == null) it.copy(closedAt = java.time.Instant.now().toString()) else it }
            val deleted = book.copy(status = "deleted", instanceId = null, penScope = StudioPenScopeDto("selected"), instances = closed)
            db.studio().upsert(listOf(StudioBookEntity(id, deleted.rev, StudioJson.book.toJson(deleted))))
        } }
        db.outbox().deleteById(op.localId)
    }
    suspend fun patch(book: StudioBookDto, name: String? = null, archived: Boolean? = null, scope: StudioPenScopeDto? = null) = enqueue(book.id, StudioOutbox.PATCH, StudioJson.patch.toJson(StudioBookPatchDto(book.rev, name, archived, scope)))
    suspend fun delete(book: StudioBookDto) = enqueue(book.id, StudioOutbox.DELETE, book.rev.toString())
    suspend fun transfer(to: StudioBookDto, from: StudioBookDto, pens: List<String>) = enqueue(to.id, StudioOutbox.TRANSFER, StudioJson.transfer.toJson(StudioTransferDto(to.rev, from.id, from.rev, pens)))
    suspend fun linkAlias(id: String, identifier: String) = enqueue(id, StudioOutbox.ALIAS, StudioJson.alias.toJson(PenAliasInput(normalizePenIdentifier(identifier))))
    suspend fun cancelPending(id: String) = db.withTransaction {
        db.outbox().forTarget(id).filter { it.kind.startsWith("studio") }.forEach { db.outbox().deleteById(it.localId) }
    }
    private suspend fun enqueue(id: String, kind: String, json: String) = db.withTransaction {
        check(db.outbox().forTarget(id).none { it.kind.startsWith("studio") }) { "Ya hay un cambio de libro pendiente" }
        db.outbox().insert(OutboxEntity(kind = kind, targetId = id, payloadJson = json, createdAt = System.currentTimeMillis()))
    }
    /** Solo coincidencias inequívocas; nunca se infiere identidad a partir del nombre. */
    suspend fun resolve(section: Int, owner: Int, code: Int, identifier: String?): StudioBookDto? {
        val id = canonicalPenId(identifier, cachedPens()) ?: return null
        return cachedBooks().filter { it.status == "ready" && it.section == section && it.owner == owner && it.book == code && it.instanceId != null && it.penScope.contains(id) }.singleOrNull()
    }
    suspend fun hasCode(section: Int, owner: Int, code: Int) = cachedBooks().any { it.section == section && it.owner == owner && it.book == code }
    suspend fun designAsset(instanceId: String): String? {
        val books = cachedBooks()
        return books.flatMap { it.instances }.firstOrNull { it.id == instanceId }?.designAssetId
            ?: books.firstOrNull { it.instanceId == instanceId }?.designAssetId
    }
}
