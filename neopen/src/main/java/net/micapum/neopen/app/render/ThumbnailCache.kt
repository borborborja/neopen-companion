package net.micapum.neopen.app.render

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Mantiene el mismo cerrojo mientras haya peticiones activas o esperando. */
internal class PreviewLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val guard = Mutex()
    private val entries = mutableMapOf<Any, Entry>()

    suspend fun <T> withKey(key: Any, block: suspend () -> T): T {
        val entry = guard.withLock { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            // La liberación debe completarse también al cancelar una fila fuera de pantalla.
            withContext(kotlinx.coroutines.NonCancellable) {
                guard.withLock { if (--entry.users == 0) entries.remove(key) }
            }
        }
    }
}

/** Presupuesto conjunto de miniaturas y páginas PDF; nunca recicla imágenes visibles. */
object PagePreviewCache {
    const val MAX_BYTES = 24 * 1024 * 1024
    private val cache = object : LruCache<Any, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: Any, value: Bitmap): Int = value.allocationByteCount
    }
    private val locks = PreviewLocks()
    private val renderSlots = Semaphore(2)
    internal val renders = AtomicLong()

    fun get(key: Any): Bitmap? = cache.get(key)
    fun clear() = cache.evictAll()
    internal fun bytes(): Int = cache.size()

    suspend fun getOrRender(key: Any, render: suspend () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        return locks.withKey(key) {
            cache.get(key)?.let { return@withKey it }
            renderSlots.withPermit {
                withContext(Dispatchers.Default) {
                    currentCoroutineContext().ensureActive()
                    val result = render()
                    try {
                        currentCoroutineContext().ensureActive()
                        if (result != null) {
                            renders.incrementAndGet()
                            if (result.allocationByteCount <= MAX_BYTES) cache.put(key, result)
                        }
                        result
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        result?.recycle()
                        throw error
                    }
                }
            }
        }
    }
}
