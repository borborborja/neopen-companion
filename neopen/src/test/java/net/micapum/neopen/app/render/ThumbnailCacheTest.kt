package net.micapum.neopen.app.render

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.micapum.neopen.app.data.toDomain
import net.micapum.neopen.app.db.CaptureEntity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = Application::class)
class ThumbnailCacheTest {
    @After fun clear() = PagePreviewCache.clear()

    @Test fun concurrentRequestsShareAnImageAndOnlyTwoRendersRunAtOnce() = runBlocking<Unit> {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val calls = AtomicInteger()
        val images = (0 until 12).map { number -> async(Dispatchers.Default) {
            PagePreviewCache.getOrRender("concurrent-${number / 3}") {
                calls.incrementAndGet()
                val count = active.incrementAndGet()
                maximum.accumulateAndGet(count, ::maxOf)
                try { delay(25); Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888) }
                finally { active.decrementAndGet() }
            }
        } }.awaitAll()
        assertEquals(4, calls.get())
        assertEquals(2, maximum.get())
        for (group in images.chunked(3)) assertTrue(group.all { it === group.first() })
    }

    @Test fun cacheBudgetIsInBytesAndEvictionDoesNotRecycleVisibleImages() = runBlocking<Unit> {
        val first = PagePreviewCache.getOrRender("budget-first") { Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888) }!!
        for (number in 0..8) PagePreviewCache.getOrRender("budget-$number") { Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888) }
        assertTrue(PagePreviewCache.bytes() <= PagePreviewCache.MAX_BYTES)
        assertNull(PagePreviewCache.get("budget-first"))
        assertFalse(first.isRecycled)
    }

    @Test fun cancellationDoesNotPublishAnObsoleteRender() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            PagePreviewCache.getOrRender("obsolete") {
                started.complete(Unit)
                delay(10_000)
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
        }
        started.await(); job.cancelAndJoin()
        assertNull(PagePreviewCache.get("obsolete"))
        assertNotNull(PagePreviewCache.getOrRender("obsolete") { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) })
    }

    @Test fun downloadsShareTheOriginalButUseDifferentServerNamespaces() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = PreviewAssetStore(context)
        val downloads = AtomicInteger()
        val key = "same-original-${System.nanoTime()}"
        suspend fun load(namespace: String): File = store.file(namespace, key) { temporary ->
            downloads.incrementAndGet(); delay(20); temporary.writeText(namespace)
        }
        val files = (0..7).map { async(Dispatchers.Default) { load("server-a") } }.awaitAll()
        assertEquals(1, downloads.get())
        assertEquals(1, files.distinct().size)
        val other = load("server-b")
        assertNotEquals(files.first(), other)
        assertEquals("server-b", other.readText())
        files.first().delete(); other.delete()
    }

    @Test fun localInkAndPaperChangesInvalidateTheImageWithoutChangingRevision() = runBlocking<Unit> {
        val original = CaptureEntity(id = "c", rev = 3, section = 3, owner = 27, book = 512, page = 0,
            notebook = null, source = "realtime", capturedAt = "now", importedAt = "now", updatedAt = "now", name = "page")
        val changed = original.copy(strokesJson = """[{"startTime":1,"endTime":2,"dots":[{"x":1.0,"y":2.0,"f":1.0}]}]""")
        val oldCapture = original.toDomain(); val capture = changed.toDomain()
        assertEquals(oldCapture.rev, capture.rev)
        assertNotEquals(oldCapture.visualFingerprint, capture.visualFingerprint)
        val reformatted = changed.copy(paperJson = """{"x0":0,"y0":0,"width":70,"height":100}""").toDomain()
        assertEquals(capture.rev, reformatted.rev)
        assertNotEquals(capture.visualFingerprint, reformatted.visualFingerprint)
        val bitmap = rasterizeThumbnail(capture, 192, 256, pressure = true, contrast = 2f, preset = BgPresets.LINED)
        assertEquals(192, bitmap.width); assertEquals(256, bitmap.height)
        val key = ThumbnailKey("server", capture.id, capture.visualFingerprint, 192, 256, true, 2f, "background-v1", null, 0)
        assertNotEquals(key, key.copy(background = "background-v2"))
        assertNotEquals(key, key.copy(pressure = false))
        assertNotEquals(key, key.copy(width = 384))
        bitmap.recycle()
    }

    @Test fun storeRecreatesItsDirectoryAfterClosingTheLibrary() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = PreviewAssetStore(context)
        val original = store.file("old-library", "original") { it.writeText("old") }
        original.parentFile!!.deleteRecursively()
        val files = (0 until 8).map { number -> async(Dispatchers.Default) {
            store.file("new-library", "document-$number") { it.writeText("new-$number") }
        } }.awaitAll()
        assertEquals(8, files.distinct().size)
        files.forEachIndexed { index, file -> assertEquals("new-$index", file.readText()) }
    }
}
