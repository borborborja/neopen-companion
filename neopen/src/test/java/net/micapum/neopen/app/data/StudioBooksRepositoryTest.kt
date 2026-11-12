package net.micapum.neopen.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.core.data.*
import net.micapum.neopen.core.pen.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = Application::class)
class StudioBooksRepositoryTest {
    private lateinit var db: NeopenDb
    private lateinit var repo: NeopenRepository
    private val now = "2026-11-12T10:00:00Z"
    private val pens = listOf(PenInfoDto("AA:BB:CC:DD:EE:FF", id = "pen-a"), PenInfoDto("11:22:33:44:55:66", id = "pen-b"))
    private fun book(id: String, penIds: List<String>) = StudioBookDto(id, 1, id, "profile", 3, 27, 512, "a5", StudioRecipeDto(), 4, "ready", penScope = StudioPenScopeDto("selected", penIds = penIds), instanceId = "instance-$id", createdAt = now, updatedAt = now, assignmentId = "assignment-$id", assignmentRevision = 8)
    private fun page(pen: String) = StrokePage(Sobp(3, 27, 512, 0), "realtime", now, listOf(Stroke(1, 2, listOf(Dot(1f, 1f, 1f, 1, 0)))), pen)
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NeopenDb::class.java).allowMainThreadQueries().build(); repo = NeopenRepository(db) }
    @After fun close() { db.close() }
    @Test fun sameCodeIsRoutedToDifferentInstancesBeforeLocalMerge() = runBlocking {
        repo.studio.apply(listOf(book("a", listOf("pen-a")), book("b", listOf("pen-b"))), pens, emptyList())
        repo.ingestPenPageLocal(page(pens[0].mac))
        repo.ingestPenPageLocal(page(pens[1].mac))
        assertEquals(setOf("instance-a", "instance-b"), repo.recent().first().map { it.bookInstanceId }.toSet())
    }
    @Test fun sharedBookMergesDistinctPenStrokesAndRetainsBothOrigins() = runBlocking {
        repo.studio.apply(listOf(book("a", listOf("pen-a", "pen-b"))), pens, emptyList())
        repo.ingestPenPageLocal(page(pens[0].mac)); repo.ingestPenPageLocal(page(pens[1].mac))
        val capture = repo.recent().first().single()
        assertEquals(2, capture.strokes.size)
        assertEquals(pens.map { it.id }.toSet(), capture.strokes.map { it.penId }.toSet())
    }
    @Test fun pendingScopeDoesNotActivateBeforeServerAcknowledgement() = runBlocking {
        val initial = book("a", listOf("pen-a"))
        repo.studio.apply(listOf(initial), pens, emptyList())
        repo.studio.patch(initial, scope = StudioPenScopeDto("selected", penIds = listOf("pen-b")))
        assertTrue(repo.studio.books().first().single().pending)
        assertNotNull(repo.studio.resolve(3, 27, 512, pens[0].mac))
        assertNull(repo.studio.resolve(3, 27, 512, pens[1].mac))
    }
    @Test fun queuedCaptureFreezesEpochAndPenIdentity() = runBlocking {
        repo.studio.apply(listOf(book("a", listOf("pen-a"))), pens, emptyList())
        repo.ingestPenPage(page(pens[0].mac))
        val input = requireNotNull(Json.createCapture.fromJson(repo.outboxPeek(1).single().payloadJson))
        assertEquals("instance-a", input.bookInstanceId)
        assertEquals("assignment-a", input.assignmentId)
        assertEquals(8L, input.assignmentRevision)
        assertEquals("pen-a", input.strokes.single().penId)
    }
    @Test fun availabilityUsesConfirmedRoomProfilesAndRetainsArchivedReservations() = runBlocking {
        val profile = StudioProfileDto(STUDIO_ENABLED_PROFILE_ID, section = 3, owner = 27, bookMin = 512, bookMax = 515, patternValidated = true)
        val initial = listOf(book("a", listOf("pen-a")), book("b", listOf("pen-b")).copy(archived = true),
            book("generating", listOf("pen-a")).copy(book = 513, status = "generating"),
            book("unassigned", emptyList()).copy(book = 514, status = "failed"))
        repo.studio.apply(initial, pens, listOf(profile))
        assertEquals(StudioCodeAvailability(4, 2, 2), repo.studio.availability().first())
        assertEquals(4, repo.studio.books().first().count { it.book.status != "deleted" })
        // El borrado confirmado libera un código solo cuando no lo usa otro libro.
        repo.studio.apply(listOf(initial[0].copy(status = "deleted")), pens, listOf(profile))
        assertEquals(StudioCodeAvailability(4, 2, 2), repo.studio.availability().first())
        repo.studio.apply(listOf(initial[1].copy(status = "deleted")), pens, listOf(profile))
        assertEquals(StudioCodeAvailability(4, 1, 3), repo.studio.availability().first())
    }
    @Test fun availabilityNeverGuessesMissingOrUnsupportedProfiles() = runBlocking {
        assertNull(repo.studio.availability().first())
        repo.studio.apply(emptyList(), pens, listOf(StudioProfileDto("future-encoder", bookMin = 0, bookMax = 1000, patternValidated = true)))
        assertNull(repo.studio.availability().first())
        repo.studio.apply(emptyList(), pens, listOf(StudioProfileDto(STUDIO_ENABLED_PROFILE_ID, bookMin = 0, bookMax = 1000, patternValidated = false)))
        assertNull(repo.studio.availability().first())
    }
    @Test fun penRegistrationIsQueuedBeforeItsFirstCapture() = runBlocking {
        repo.ingestPenPage(page(pens[0].mac))
        val queued = repo.outboxPeek(10)
        assertEquals(listOf(StudioOutbox.REGISTER, OutboxKind.CREATE_CAPTURE), queued.map { it.kind })
        assertEquals(pens[0].mac, requireNotNull(Json.createCapture.fromJson(queued.last().payloadJson)).penId)
    }
    @Test fun wholeOfflineBatchKeepsItsStartingBindingWhenRoomChangesMidDownload() = runBlocking {
        val original = book("a", listOf("pen-a"))
        repo.studio.apply(listOf(original), pens, emptyList())
        val replacement = book("b", listOf("pen-a"))
        repo.studio.apply(listOf(original.copy(status = "deleted"), replacement), pens, emptyList())
        repo.persistPenPage(page(pens[0].mac).copy(source = "offline"), OfflineReimportPolicy.ASK, original, bindingFrozen = true) { false }
        val captured = requireNotNull(Json.createCapture.fromJson(repo.outboxPeek(10).single { it.kind == OutboxKind.CREATE_CAPTURE }.payloadJson))
        assertEquals(original.instanceId, captured.bookInstanceId)
        assertEquals(original.assignmentId, captured.assignmentId)
        assertEquals(8L, captured.assignmentRevision)
        assertEquals(148f / 2.371f, requireNotNull(captured.paper).width)
    }
    @Test fun closingInstanceSealsMovedCapturesAndBlocksLocalReopen() = runBlocking {
        val initial = book("a", listOf("pen-a"))
        repo.studio.apply(listOf(initial), pens, emptyList())
        repo.ingestPenPageLocal(page(pens[0].mac))
        val capture = repo.recent().first().single()
        repo.moveCapture(capture.id, "other-notebook", 0)
        val closed = StudioInstanceDto("instance-a", "a", "notebook-a", now, now, StudioRecipeDto(), "p", "old", 3, 27, 512, "a5", "old-design")
        repo.studio.apply(listOf(initial.copy(instanceId = null, penScope = StudioPenScopeDto("selected"), instances = listOf(closed))), pens, emptyList())
        repo.setCaptureSealedLocal(capture.id, false)
        assertTrue(requireNotNull(repo.capture(capture.id)).sealed)
        assertEquals("old-design", repo.studio.designAsset("instance-a"))
    }
}
