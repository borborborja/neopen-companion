package net.micapum.neopen.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Comparte la decodificación de una instantánea, sin sustituir la lectura del origen. */
internal class SnapshotCache<K, V>(private val decode: (K) -> V) {
    private val mutex = Mutex()
    private var cached: Pair<K, V>? = null

    suspend fun get(snapshot: K): V = mutex.withLock {
        cached?.takeIf { it.first == snapshot }?.second
            ?: decode(snapshot).also { cached = snapshot to it }
    }
}
