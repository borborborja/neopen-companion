package net.micapum.neopen.app.capability

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.app.data.Notebook
import net.micapum.neopen.app.data.toDomain
import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.ConnectorSyncStateEntity
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.db.NotebookEntity
import net.micapum.neopen.core.capability.FileConnector
import net.micapum.neopen.core.data.OcrResultDto
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.SettingsStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalConnectorSyncRegressionTest {
    private lateinit var db: NeopenDb
    private lateinit var mirror: LocalConnectorSync
    private val now = "2026-11-12T00:00:00Z"
    private val connectorId = LocalConnectorSync.destinationId("webdav", "https://example.test", "user", "/notes")

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, NeopenDb::class.java).allowMainThreadQueries().build()
        mirror = LocalConnectorSync(db, SettingsStore(context))
    }

    @After fun cleanup() { db.close() }

    private fun capture(id: String, notebookId: String? = null): Capture =
        CaptureEntity(id, 0, 3, 27, 388, 1, null, "realtime", now, now, now, "Nota",
            destination = if (notebookId == null) "loose" else "notebook", notebookId = notebookId)
            .toDomain().copy(transcription = OcrResultDto(text = "Texto"))

    private fun notebook(id: String): Notebook =
        NotebookEntity(id, 0, "Cuaderno", "#123456", createdAt = now, updatedAt = now).toDomain()

    private fun desired(captures: List<Capture>, notebooks: List<Notebook> = emptyList(), cid: String = connectorId) =
        mirror.computeDesired(captures, notebooks, notebooks.associateBy { it.id }, emptySet(), listOf(cid), false, 1f)

    private fun file(itemId: String, path: String, hash: String = "new-$itemId", cid: String = connectorId) =
        LocalConnectorSync.Desired(itemId, cid, "png", path, hash) { path.toByteArray() to "image/png" }

    private fun files(vararg desired: LocalConnectorSync.Desired) =
        desired.associateBy { "${it.itemId}|${it.connectorId}|${it.format}" }

    private class MemoryConnector(override val id: String) : FileConnector {
        val remote = mutableMapOf<String, ByteArray>()
        val deletes = mutableListOf<String>()
        val failingPaths = mutableSetOf<String>()
        override suspend fun putPath(relativePath: String, bytes: ByteArray, contentType: String): String {
            check(relativePath !in failingPaths) { "Upload failed" }
            val path = "/notes/$relativePath"
            remote[path] = bytes
            return path
        }
        override suspend fun delete(remotePath: String) { deletes.add(remotePath); remote.remove(remotePath) }
    }

    @Test fun splitSheetsWithIdenticalNameAndSobpHaveIndependentFilesAndMarkdownAttachments() {
        val items = desired(listOf(capture("first"), capture("second")))
        assertEquals(6, items.size)
        assertEquals(6, items.values.map { it.relativePath }.toSet().size)
        for (id in listOf("first", "second")) {
            val png = items.values.single { it.itemId == id && it.format == "png" }.relativePath.substringAfterLast('/')
            val markdown = items.values.single { it.itemId == id && it.format == "md" }.render().first.decodeToString()
            assertTrue(markdown, markdown.contains("![[${png}]]"))
        }
    }

    @Test fun notebooksWithTheSameNameHaveIndependentFoldersAndBooks() {
        val items = desired(listOf(capture("a", "one"), capture("b", "two")), listOf(notebook("one"), notebook("two")))
        val books = items.values.filter { it.itemId.startsWith("nb:") }
        assertEquals(2, books.map { it.relativePath }.toSet().size)
        assertTrue(books.single { it.itemId == "nb:one" }.relativePath.startsWith("cuaderno-one/"))
        assertTrue(books.single { it.itemId == "nb:two" }.relativePath.startsWith("cuaderno-two/"))
    }

    @Test fun importedUnsafeIdsCannotCollapseToTheSameSanitizedPath() {
        val items = desired(listOf(capture("a/b"), capture("a?b"), capture("a_b")))
        assertEquals(9, items.values.map { it.relativePath }.toSet().size)
    }

    @Test fun longImportedIdsKeepDistinctFilesWithinFilesystemSegmentLimits() {
        val longName = "n".repeat(80)
        val firstBook = notebook("a".repeat(200)).copy(name = longName)
        val secondBook = notebook("b".repeat(200)).copy(name = longName)
        val items = desired(listOf(
            capture("c".repeat(200), firstBook.id).copy(name = longName),
            capture("d".repeat(200), secondBook.id).copy(name = longName),
        ), listOf(firstBook, secondBook))
        assertEquals(items.size, items.values.map { it.relativePath }.toSet().size)
        for (path in items.values.map { it.relativePath }) {
            assertTrue(path, path.split('/').all { it.toByteArray().size <= 255 })
        }
    }

    @Test fun paperChangesInvalidatePageImagesPdfsAndCombinedNotebookPdf() {
        val old = capture("page").copy(paper = PaperDto(0f, 0f, 100f, 200f))
        val changed = old.copy(paper = PaperDto(0f, 0f, 200f, 100f))
        for (format in listOf("png", "pdf")) {
            assertNotEquals(desired(listOf(old)).values.single { it.format == format }.hash,
                desired(listOf(changed)).values.single { it.format == format }.hash)
        }
        val book = notebook("book")
        val before = desired(listOf(old.copy(destination = "notebook", notebookId = book.id)), listOf(book))
        val after = desired(listOf(changed.copy(destination = "notebook", notebookId = book.id)), listOf(book))
        assertNotEquals(before.values.single { it.itemId == "nb:book" }.hash, after.values.single { it.itemId == "nb:book" }.hash)
    }

    @Test fun changingAnyDestinationComponentPublishesExistingContentWithoutDeletingOnTheNewEndpoint() = runBlocking {
        val first = MemoryConnector(connectorId)
        mirror.reconcileFiles(files(file("page", "page.png")), listOf(first))
        for ((url, user, path) in listOf(
            Triple("https://new.example.test", "user", "/notes"),
            Triple("https://example.test", "another", "/notes"),
            Triple("https://example.test", "user", "/another"),
        )) {
            val cid = LocalConnectorSync.destinationId("webdav", url, user, path)
            val next = MemoryConnector(cid)
            mirror.reconcileFiles(files(file("page", "page.png", cid = cid)), listOf(next))
            assertTrue(next.remote.containsKey("/notes/page.png"))
            assertTrue(next.deletes.isEmpty())
        }
        assertTrue(first.remote.containsKey("/notes/page.png"))
    }

    @Test fun destinationFingerprintNormalizesEquivalentFoldersAndDoesNotExposeIdentity() {
        val id = LocalConnectorSync.destinationId("webdav", "https://example.test/", "user", "//notes/")
        assertEquals(connectorId, id)
        assertFalse(id.contains("example"))
        assertFalse(id.contains("user"))
    }

    @Test fun legacyDestinationRowsCannotDeleteFilesAtTheCurrentDestination() = runBlocking {
        db.connectorSyncState().upsert(ConnectorSyncStateEntity("page", "webdav", "png", "/notes/old.png", "old", 0))
        val connector = MemoryConnector(connectorId)
        mirror.reconcileFiles(files(file("page", "page.png")), listOf(connector))
        assertTrue(connector.deletes.isEmpty())
        assertEquals(listOf(connectorId), db.connectorSyncState().all().map { it.connectorId })
    }

    @Test fun sharedOldPathSurvivesUntilEveryReferencingUploadSucceeds() = runBlocking {
        val connector = MemoryConnector(connectorId)
        val oldPath = "/notes/shared.png"
        connector.remote[oldPath] = byteArrayOf(1)
        for (id in listOf("first", "second")) {
            db.connectorSyncState().upsert(ConnectorSyncStateEntity(id, connectorId, "png", oldPath, "old", 0))
        }
        val target = files(file("first", "first.png"), file("second", "second.png"))
        connector.failingPaths += "second.png"
        mirror.reconcileFiles(target, listOf(connector))
        assertTrue(connector.remote.containsKey(oldPath))
        assertFalse(connector.deletes.contains(oldPath))
        connector.failingPaths.clear()
        mirror.reconcileFiles(target, listOf(connector))
        assertFalse(connector.remote.containsKey(oldPath))
        assertTrue(connector.remote.containsKey("/notes/first.png"))
        assertTrue(connector.remote.containsKey("/notes/second.png"))
    }

    @Test fun deletingOneLegacySharedItemKeepsTheRemainingItemsFile() = runBlocking {
        val connector = MemoryConnector(connectorId)
        val oldPath = "/notes/shared.png"
        connector.remote[oldPath] = byteArrayOf(1)
        for (id in listOf("first", "second")) {
            db.connectorSyncState().upsert(ConnectorSyncStateEntity(id, connectorId, "png", oldPath, "old", 0))
        }
        mirror.reconcileFiles(files(file("second", "shared.png", hash = "old")), listOf(connector))
        assertTrue(connector.remote.containsKey(oldPath))
        assertTrue(connector.deletes.isEmpty())
        assertEquals(listOf("second"), db.connectorSyncState().all().map { it.itemId })
    }
}
