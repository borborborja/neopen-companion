package net.micapum.neopen.companion.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Marcas de tiempo (epoch ms) de la última sincronización con el boli y con el servidor. */
data class SyncStatus(
    val lastPenSync: Long = 0L,
    val lastServerSync: Long = 0L,
)

/**
 * Persiste cuándo fue la última lectura del boli y la última subida al servidor.
 * Lo consume el widget de la pantalla de inicio (y, de paso, la app).
 */
class SyncStatusStore(private val context: Context) {
    private object Keys {
        val pen = longPreferencesKey("last_pen_sync")
        val server = longPreferencesKey("last_server_sync")
    }

    val flow: Flow<SyncStatus> = context.dataStore.data.map { p ->
        SyncStatus(lastPenSync = p[Keys.pen] ?: 0L, lastServerSync = p[Keys.server] ?: 0L)
    }

    suspend fun markPenSync(at: Long) {
        context.dataStore.edit { it[Keys.pen] = at }
    }

    suspend fun markServerSync(at: Long) {
        context.dataStore.edit { it[Keys.server] = at }
    }
}
