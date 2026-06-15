package net.micapum.neopen.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "neopen-companion")

/**
 * Cifra la contraseña del servidor en reposo con una clave AES/GCM del Android
 * Keystore (ligada al dispositivo, nunca sale en backups). Retrocompatible: un
 * valor sin el prefijo se trata como texto plano heredado; si el descifrado falla
 * (p. ej. backup restaurado en otro móvil) devuelve "" → el usuario reentra la
 * contraseña. Si el cifrado falla en algún dispositivo raro, guarda en claro
 * (degradado pero funcional).
 */
internal object PwCrypto {
    private const val ALIAS = "neopen_pw_key"
    private const val PREFIX = "enc1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored // texto plano heredado o vacío
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN))
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        }.getOrDefault("")
    }
}

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
    /** Detección de trazos superpuestos (avisa al escribir encima de lo anterior). */
    val overlapEnabled: Boolean = true,
    /** Nº mínimo de trazos superpuestos para avisar. */
    val overlapMinStrokes: Int = 3,
    /** % mínimo de área superpuesta para avisar. */
    val overlapMinAreaPercent: Int = 25,
    /** Vista de la pestaña Cuadernos: "Mosaic" | "Compact" | "List". */
    val notebookView: String = "Mosaic",
    /** Tema de la app: "system" | "light" | "dark". */
    val themeMode: String = "system",
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
        val overlapEnabled = booleanPreferencesKey("overlap_enabled")
        val overlapMinStrokes = intPreferencesKey("overlap_min_strokes")
        val overlapMinArea = intPreferencesKey("overlap_min_area")
        val notebookView = stringPreferencesKey("notebook_view")
        val themeMode = stringPreferencesKey("theme_mode")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        val url = p[Keys.url] ?: ""
        val pw = PwCrypto.decrypt(p[Keys.pw] ?: "")
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
            overlapEnabled = p[Keys.overlapEnabled] ?: true,
            overlapMinStrokes = p[Keys.overlapMinStrokes] ?: 3,
            overlapMinAreaPercent = p[Keys.overlapMinArea] ?: 25,
            notebookView = p[Keys.notebookView] ?: "Mosaic",
            themeMode = p[Keys.themeMode] ?: "system",
        )
    }

    suspend fun update(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.url] = s.serverUrl.trim()
            p[Keys.pw] = PwCrypto.encrypt(s.password)
            p[Keys.bg] = s.backgroundEnabled
            p[Keys.mode] = s.syncMode.name
            p[Keys.interval] = s.syncIntervalMin
            p[Keys.appMode] = s.appMode.name
            p[Keys.setup] = s.setupComplete
            p[Keys.importOnConnect] = s.importOnConnect
            p[Keys.recentCount] = s.recentShowCount
            p[Keys.recentCleared] = s.recentClearedAt
            p[Keys.finishMode] = s.finishMode
            p[Keys.overlapEnabled] = s.overlapEnabled
            p[Keys.overlapMinStrokes] = s.overlapMinStrokes
            p[Keys.overlapMinArea] = s.overlapMinAreaPercent
            p[Keys.notebookView] = s.notebookView
            p[Keys.themeMode] = s.themeMode
        }
    }
}
