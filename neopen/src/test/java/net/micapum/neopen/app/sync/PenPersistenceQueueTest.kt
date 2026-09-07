package net.micapum.neopen.app.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PenPersistenceQueueTest {
    @Test
    fun laterPagesStillPersistAfterAnEarlierFailureButCountDoesNot() = runBlocking {
        val queue = PenPersistenceQueue(this)
        var laterPage = false
        var countWritten = false
        queue.enqueue { error("disk full") }
        queue.enqueue(requiresPreviousSuccess = false) { laterPage = true }
        queue.enqueue { countWritten = true }
        assertFalse(queue.completion().await())
        assertTrue(laterPage)
        assertFalse(countWritten)
    }
    @Test
    fun waitsForPagesBeforeCountersAndCompletion() = runBlocking {
        val queue = PenPersistenceQueue(this)
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        queue.beginBatch()
        queue.enqueue { gate.await(); events.add("page") }
        queue.enqueue { events.add("count") }
        val finished = queue.completion()
        yield()
        assertFalse(finished.isCompleted)
        assertTrue(events.isEmpty())
        gate.complete(Unit)
        assertTrue(finished.await())
        assertEquals(listOf("page", "count"), events)
    }

    @Test
    fun failedPageNeverPersistsCountOrReportsSuccessAndNextBatchCanRetry() = runBlocking {
        val queue = PenPersistenceQueue(this)
        var countWritten = false
        queue.enqueue { error("disk full") }
        queue.enqueue { countWritten = true }
        assertFalse(queue.completion().await())
        assertFalse(countWritten)
        queue.beginBatch()
        queue.enqueue { countWritten = true }
        assertTrue(queue.completion().await())
        assertTrue(countWritten)
    }

    @Test
    fun beginningAnotherBatchDoesNotOvertakePendingWrites() = runBlocking {
        val queue = PenPersistenceQueue(this)
        val gate = CompletableDeferred<Unit>()
        var written = false
        queue.enqueue { gate.await() }
        queue.beginBatch()
        queue.enqueue { written = true }
        yield()
        assertFalse(written)
        gate.complete(Unit)
        assertTrue(queue.completion().await())
        assertTrue(written)
    }
}
