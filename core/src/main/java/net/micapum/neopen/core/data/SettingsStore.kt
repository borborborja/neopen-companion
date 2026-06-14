package net.micapum.neopen.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "neopen-companion")

/** Modo de sincronización de la escritura offline guardada en el boli. */
enum class SyncMode { MANUAL, INTERVAL }

data class AppSettings(
    val serverUrl: String = "",
    val password: String = "",
    /** Mantener la conexión al boli en segundo plano (servicio en primer plano). */
    val backgroundEnabled: Boolean = false,
    val syncMode: SyncMode = SyncMode.MANUAL,
    /** Intervalo de volcado offline en minutos (solo modo INTERVAL). */
    val syncIntervalMin: Int = 30,
) {
    val configured get() = serverUrl.isNotBlank() && password.isNotBlank()
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val url = stringPreferencesKey("server_url")
        val pw = stringPreferencesKey("password")
        val bg = booleanPreferencesKey("background")
        val mode = stringPreferencesKey("sync_mode")
        val interval = intPreferencesKey("sync_interval")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            serverUrl = p[Keys.url] ?: "",
            password = p[Keys.pw] ?: "",
            backgroundEnabled = p[Keys.bg] ?: false,
            syncMode = runCatching { SyncMode.valueOf(p[Keys.mode] ?: "MANUAL") }.getOrDefault(SyncMode.MANUAL),
            syncIntervalMin = p[Keys.interval] ?: 30,
        )
    }

    suspend fun update(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.url] = s.serverUrl.trim()
            p[Keys.pw] = s.password
            p[Keys.bg] = s.backgroundEnabled
            p[Keys.mode] = s.syncMode.name
            p[Keys.interval] = s.syncIntervalMin
        }
    }
}
