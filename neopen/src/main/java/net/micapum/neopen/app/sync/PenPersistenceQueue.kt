package net.micapum.neopen.app.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** FIFO para páginas, contadores y barrera de fin de volcado; nunca adelantar el sync. */
internal class PenPersistenceQueue(private val scope: CoroutineScope) {
    private var tail: Deferred<Boolean>? = null

    /** Una nueva descarga puede reintentar tras un error anterior, sin solapar escrituras. */
    @Synchronized
    fun beginBatch() {
        val previous = tail
        tail = scope.async { previous?.await(); true }
    }

    @Synchronized
    fun enqueue(requiresPreviousSuccess: Boolean = true, write: suspend () -> Unit) {
        val previous = tail
        tail = scope.async {
            val previousOk = previous?.await() != false
            if (requiresPreviousSuccess && !previousOk) return@async false
            try {
                write()
                previousOk
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Mantiene la copia del boli: no guardar el contador ni anunciar éxito.
                false
            }
        }
    }

    @Synchronized
    fun completion(): Deferred<Boolean> = tail ?: scope.async { true }
}
