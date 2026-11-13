package net.micapum.neopen.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.db.PageBackgroundEntity
import net.micapum.neopen.core.data.DotDto
import net.micapum.neopen.core.data.OcrResultDto
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.StrokeDto
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureSummaryRepositoryTest {
    private lateinit var db: NeopenDb
    private lateinit var repo: NeopenRepository
    private val queries = CopyOnWriteArrayList<String>()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NeopenDb::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) { queries.add(sqlQuery) }
            }, Executor { it.run() })
            .build()
        repo = NeopenRepository(db)
    }

    @After fun close() { db.close() }

    private fun row(id: String, day: Int = 1, page: Int = 1) = CaptureEntity(
        id = id, rev = 7, section = 3, owner = 27, book = 512, page = page, notebook = null,
        source = "realtime", capturedAt = "2026-11-01T00:00:00Z",
        importedAt = "2026-11-${day.toString().padStart(2, '0')}T10:00:00Z",
        updatedAt = "2026-11-${day.toString().padStart(2, '0')}T10:00:00Z", name = id,
        strokesJson = Json.strokesJson(listOf(StrokeDto(1, 2, listOf(DotDto(1f, 1f, 1f))))),
    )

    @Test fun recentLimitAndClearAreAppliedBySqlWithoutReadingInkOrOcr() = runBlocking {
        db.captures().upsert((1..11).map { row("page-$it", it, it) })
        queries.clear()
        val shown = repo.recentSummaries(5, "2026-11-07T10:00:00Z").first()
        assertEquals(listOf("page-11", "page-10", "page-9", "page-8"), shown.map { it.id })
        val query = queries.first { it.startsWith("SELECT ") && it.contains("FROM captures") }
        assertTrue(query.contains("LIMIT ?"))
        assertFalse(query.contains("strokesJson"))
        assertFalse(query.contains("transcriptionJson"))
        assertTrue(shown.all { it.asMetadataCapture().strokes.isEmpty() && it.asMetadataCapture().transcription == null })
        assertEquals(8, repo.recentSummaries(8).first().size)
    }

    @Test fun searchIncludesOldPagesAcrossOcrNameAndTagsAndRequiresEveryTerm() = runBlocking {
        db.captures().upsert(listOf(
            row("old", 1).copy(name = "Acta", tagsJson = Json.tagsJson(listOf("Proyecto")), transcriptionJson = Json.ocrJson(OcrResultDto(title = "Encuentro", text = "Revisión de noviembre"))),
            row("new", 11).copy(name = "Proyecto sin OCR"),
            row("other", 10).copy(transcriptionJson = Json.ocrJson(OcrResultDto(text = "Revisión"))),
        ))
        assertEquals(listOf("new", "other"), repo.recentSummaries(5, "2026-11-05").first().map { it.id })
        queries.clear()
        assertEquals(listOf("old"), repo.searchCaptureSummaries("  PROYECTO\t noviembre acta encuentro ").first().map { it.id })
        assertTrue(repo.searchCaptureSummaries("Proyecto imposible").first().isEmpty())
        val query = queries.first { it.startsWith("SELECT ") && it.contains("transcriptionJson") }
        assertFalse(query.contains("strokesJson"))
    }

    @Test fun notebookSummariesKeepManualOrderAndIdentityMetadata() = runBlocking {
        val later = row("later", 11, 2).copy(notebookId = "nb", position = 2)
        val first = row("first", 10, 1).copy(notebookId = "nb", position = 1, penId = "pen", bookInstanceId = "instance", assignmentId = "binding", assignmentRevision = 12, sealed = true)
        db.captures().upsert(listOf(later, first, row("elsewhere", 12).copy(notebookId = "other")))
        val shown = repo.captureSummariesIn("nb").first()
        assertEquals(listOf("first", "later"), shown.map { it.id })
        val metadata = shown.first().asMetadataCapture()
        assertEquals("instance:1", metadata.writingPageKey)
        assertEquals("pen", metadata.penId)
        assertEquals("binding", metadata.assignmentId)
        assertEquals(12L, metadata.assignmentRevision)
        assertTrue(metadata.sealed)
    }

    @Test fun supersededKeysRespectInstancesAndIgnoreClonesWithoutLoadingInk() = runBlocking {
        db.captures().upsert(listOf(
            row("legacy-a"), row("legacy-b"),
            row("epoch-a").copy(bookInstanceId = "epoch"), row("epoch-b").copy(bookInstanceId = "epoch"),
            row("new-epoch").copy(bookInstanceId = "new"),
            row("clone").copy(bookInstanceId = "new", clonedFrom = "new-epoch"),
            row("another-page", page = 2).copy(bookInstanceId = "epoch"),
        ))
        queries.clear()
        assertEquals(setOf("3.27.512.1", "epoch:1"), repo.supersededWritingPages().first())
        assertFalse(queries.first { it.startsWith("SELECT ") && it.contains("writingPageKey") }.contains("strokesJson"))
    }

    @Test fun visualFingerprintChangesWithUnsyncedInkAndPaperButNotRename() = runBlocking {
        val original = row("page")
        db.captures().upsertOne(original)
        val before = requireNotNull(repo.capture("page"))
        val newInk = original.copy(strokesJson = Json.strokesJson(listOf(StrokeDto(1, 2, listOf(DotDto(2f, 1f, 1f))))))
        db.captures().upsertOne(newInk)
        val afterInk = requireNotNull(repo.captureFlow("page").first())
        assertEquals(before.rev, afterInk.rev)
        assertEquals(before.updatedAt, afterInk.updatedAt)
        assertNotEquals(before.visualFingerprint, afterInk.visualFingerprint)
        val newPaper = newInk.copy(paperJson = Json.paperJson(PaperDto(0f, 0f, 10f, 20f)))
        db.captures().upsertOne(newPaper)
        val afterPaper = requireNotNull(repo.capture("page"))
        assertNotEquals(afterInk.visualFingerprint, afterPaper.visualFingerprint)
        db.captures().upsertOne(newPaper.copy(name = "renamed"))
        assertEquals(afterPaper.visualFingerprint, requireNotNull(repo.capture("page")).visualFingerprint)
    }

    @Test fun backgroundVersionSurvivesMappingWhenItsImagePathStaysTheSame() {
        val original = PageBackgroundEntity("bg", "3.27.512", bgImage = "file:///bg.png", createdAt = "before")
        val changed = original.copy(createdAt = "after")
        assertEquals(original.toDomain().bgImage, changed.toDomain().bgImage)
        assertNotEquals(original.toDomain().createdAt, changed.toDomain().createdAt)
    }
}
