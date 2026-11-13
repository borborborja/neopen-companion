package net.micapum.neopen.app.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Comparte solo trabajo simultáneo: la siguiente llamada vuelve a consultar el origen. */
internal class InFlightRequests<K, V> {
    private val mutex = Mutex()
    private val requests = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        while (true) {
            var owner = false
            val pending = mutex.withLock {
                requests[key] ?: CompletableDeferred<V>().also { requests[key] = it; owner = true }
            }
            if (!owner) {
                try {
                    return pending.await()
                } catch (cancelled: CancellationException) {
                    // Cerrar una pantalla puede cancelar al propietario de una consulta
                    // compartida. El worker que esperaba sigue vivo y vuelve a intentarlo.
                    // Una cancelación del propio waiter, en cambio, sí debe propagarse.
                    currentCoroutineContext().ensureActive()
                    mutex.withLock { if (requests[key] === pending) requests.remove(key) }
                    continue
                }
            }
            try {
                return block().also { pending.complete(it) }
            } catch (error: Throwable) {
                pending.completeExceptionally(error)
                throw error
            } finally {
                // También se limpia cuando el propietario se cancela.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    mutex.withLock { if (requests[key] === pending) requests.remove(key) }
                }
            }
        }
    }
}
