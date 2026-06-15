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

/**
 * Modo de la app: solo puente (boli→servidor), versión completa (cuadernos con
 * servidor), o local (cuadernos sin servidor; se puede vincular uno más tarde).
 */
enum class AppMode { BRIDGE, FULL, LOCAL }

data class AppSettings(
    val serverUrl: String = "",
    val password: String = "",
    /** Mantener la conexión al boli en segundo plano (servicio en primer plano). */
    val backgroundEnabled: Boolean = false,
    val syncMode: SyncMode = SyncMode.MANUAL,
    /** Intervalo de volcado offline en minutos (solo modo INTERVAL). */
    val syncIntervalMin: Int = 30,
    /** Modo elegido en el wizard de primer arranque (cambiable luego en Ajustes). */
    val appMode: AppMode = AppMode.FULL,
    /** El wizard de primer arranque ya se completó. */
    val setupComplete: Boolean = false,
    /** Volcar la memoria offline del boli automáticamente al conectar. */
    val importOnConnect: Boolean = true,
    /** Cuántas mostrar en "Últimas ediciones" (5/10/20). */
    val recentShowCount: Int = 10,
    /** ISO de "limpiar últimas ediciones": oculta lo anterior hasta una nueva edición. */
    val recentClearedAt: String = "",
    /** Qué pasa al finalizar una hoja: "newSheet" | "newNotebook". */
    val finishMode: String = "newSheet",
    /** Vista de la pestaña Cuadernos: "Mosaic" | "Compact" | "List". */
    val notebookView: String = "Mosaic",
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
        val appMode = stringPreferencesKey("app_mode")
        val setup = booleanPreferencesKey("setup_complete")
        val importOnConnect = booleanPreferencesKey("import_on_connect")
        val recentCount = intPreferencesKey("recent_count")
        val recentCleared = stringPreferencesKey("recent_cleared")
        val finishMode = stringPreferencesKey("finish_mode")
        val notebookView = stringPreferencesKey("notebook_view")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        val url = p[Keys.url] ?: ""
        val pw = p[Keys.pw] ?: ""
        AppSettings(
            serverUrl = url,
            password = pw,
            backgroundEnabled = p[Keys.bg] ?: false,
            syncMode = runCatching { SyncMode.valueOf(p[Keys.mode] ?: "MANUAL") }.getOrDefault(SyncMode.MANUAL),
            syncIntervalMin = p[Keys.interval] ?: 30,
            appMode = runCatching { AppMode.valueOf(p[Keys.appMode] ?: "FULL") }.getOrDefault(AppMode.FULL),
            // Migración: quien ya tenía servidor+contraseña no debe pasar por el wizard.
            setupComplete = p[Keys.setup] ?: (url.isNotBlank() && pw.isNotBlank()),
            importOnConnect = p[Keys.importOnConnect] ?: true,
            recentShowCount = p[Keys.recentCount] ?: 10,
            recentClearedAt = p[Keys.recentCleared] ?: "",
            finishMode = p[Keys.finishMode] ?: "newSheet",
            notebookView = p[Keys.notebookView] ?: "Mosaic",
        )
    }

    suspend fun update(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.url] = s.serverUrl.trim()
            p[Keys.pw] = s.password
            p[Keys.bg] = s.backgroundEnabled
            p[Keys.mode] = s.syncMode.name
            p[Keys.interval] = s.syncIntervalMin
            p[Keys.appMode] = s.appMode.name
            p[Keys.setup] = s.setupComplete
            p[Keys.importOnConnect] = s.importOnConnect
            p[Keys.recentCount] = s.recentShowCount
            p[Keys.recentCleared] = s.recentClearedAt
            p[Keys.finishMode] = s.finishMode
            p[Keys.notebookView] = s.notebookView
        }
    }
}
