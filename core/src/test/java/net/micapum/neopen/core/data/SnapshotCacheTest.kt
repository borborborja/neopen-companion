package net.micapum.neopen.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SnapshotCacheTest {
    @Test fun simultaneousReadersDecodeOnlyOnceAndNewSnapshotsAreImmediatelyVisible() = runBlocking {
        val calls = AtomicInteger()
        val cache = SnapshotCache<String, Any> { calls.incrementAndGet(); Any() }
        val values = List(20) { async(Dispatchers.Default) { cache.get("first") } }.awaitAll()
        values.forEach { assertSame(values.first(), it) }
        assertEquals(1, calls.get())
        val next = cache.get("second")
        assertSame(next, cache.get("second"))
        assertEquals(2, calls.get())
        cache.get("first") // No es un caché ilimitado de credenciales antiguas.
        assertEquals(3, calls.get())
    }
}
