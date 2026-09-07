package net.micapum.neopen.core.pen

internal enum class OfflineListMode { MANUAL, AUTO_IMPORT }

internal data class OfflineListCompletion(
    val notes: List<OfflineNote>,
    val autoImport: Boolean,
)

/** Distingue una lectura manual de la lista que debe encadenar el auto-volcado. */
internal class OfflineListCoordinator {
    private var mode: OfflineListMode? = null

    val active: Boolean
        @Synchronized get() = mode != null

    @Synchronized
    fun start(requestedMode: OfflineListMode): Boolean {
        if (mode != null) return false
        mode = requestedMode
        return true
    }

    /** Un worker puede unirse a una lectura manual sin enviar otra petición. */
    @Synchronized
    fun requestImportAfterList() {
        if (mode != null) mode = OfflineListMode.AUTO_IMPORT
    }

    @Synchronized
    fun complete(notes: List<OfflineNote>): OfflineListCompletion? {
        val completedMode = mode ?: return null
        mode = null
        return OfflineListCompletion(notes, completedMode == OfflineListMode.AUTO_IMPORT)
    }

    /** Devuelve true si había una lectura pendiente que acaba de cancelarse. */
    @Synchronized
    fun cancel(): Boolean {
        val wasActive = mode != null
        mode = null
        return wasActive
    }
}
