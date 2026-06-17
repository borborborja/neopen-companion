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
import net.micapum.neopen.core.pen.KnownPen
import net.micapum.neopen.core.pen.StrokeStyle
import org.json.JSONArray
import org.json.JSONObject
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

/** Política al reimportar desde la memoria del boli trazos de una página ya intervenida. */
enum class OfflineReimportPolicy { ASK, DISCARD, NEW_CAPTURE, MERGE }

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
    /** Qué hacer si el volcado offline trae trazos de una página borrada o movida. */
    val offlineReimportPolicy: OfflineReimportPolicy = OfflineReimportPolicy.ASK,
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
    /** Idioma del reconocimiento de escritura en vivo (BCP-47): "es" | "ca" | "en". */
    val liveLanguage: String = "es",
    /** Emitir saltos de línea reales al detectar nueva línea en modo escritura en vivo. */
    val liveNewlines: Boolean = false,
    /** Notificación de control persistente (cambiar color/grueso/boli/segundo plano sin abrir la app). */
    val controlNotification: Boolean = false,
    /** Renderiza el grosor del trazo usando la presión de cada punto cuando está disponible. */
    val pressureWidthEnabled: Boolean = false,
    /** Bolis emparejados (MAC + alias + estilo de trazo). Tope práctico: 2. */
    val knownPens: List<KnownPen> = emptyList(),
    /** Bolis olvidados: se ignoran en conexiones automáticas hasta que el usuario los conecte a mano. */
    val forgottenPens: List<String> = emptyList(),
    /** Si no está vacío, la conexión automática/manual solo acepta este boli. */
    val lockedPenMac: String = "",
    /** Estilo de trazo por defecto para bolis sin estilo propio (color ARGB + grueso). */
    val defaultStyle: StrokeStyle = StrokeStyle(),
    // --- Capacidades despachables (teléfono / servidor / apagado) ---
    /** Quién hace el OCR. SERVER por defecto = comportamiento histórico. */
    val ocrSource: CapabilitySource = CapabilitySource.SERVER,
    /** Quién corrige el texto con LanguageTool. */
    val languageToolSource: CapabilitySource = CapabilitySource.OFF,
    /** Quién detecta marcadores (#tag, *título*, [TIPO]) en la transcripción. */
    val markerDetectionSource: CapabilitySource = CapabilitySource.SERVER,
    /** Quién sincroniza a conectores (WebDAV/FTP). */
    val connectorSyncSource: CapabilitySource = CapabilitySource.OFF,
    /** Credenciales locales del OCR (API compatible con OpenAI). */
    val ocrLocal: OcrLocalConfig = OcrLocalConfig(),
    /** Credenciales locales de LanguageTool. */
    val ltLocal: LanguageToolLocalConfig = LanguageToolLocalConfig(),
    /** Credenciales locales del conector WebDAV. */
    val webdavLocal: WebdavLocalConfig = WebdavLocalConfig(),
    /** Credenciales locales del conector FTP/SFTP. */
    val ftpLocal: FtpLocalConfig = FtpLocalConfig(),
) {
    val configured get() = serverUrl.isNotBlank() && password.isNotBlank()

    /** Estilo del boli (por MAC) o el por defecto si no está registrado. */
    fun styleFor(mac: String?): StrokeStyle =
        knownPens.firstOrNull { it.mac == mac }?.style ?: defaultStyle
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
        val offlineReimportPolicy = stringPreferencesKey("offline_reimport_policy")
        val recentCount = intPreferencesKey("recent_count")
        val recentCleared = stringPreferencesKey("recent_cleared")
        val finishMode = stringPreferencesKey("finish_mode")
        val overlapEnabled = booleanPreferencesKey("overlap_enabled")
        val overlapMinStrokes = intPreferencesKey("overlap_min_strokes")
        val overlapMinArea = intPreferencesKey("overlap_min_area")
        val notebookView = stringPreferencesKey("notebook_view")
        val themeMode = stringPreferencesKey("theme_mode")
        val liveLanguage = stringPreferencesKey("live_language")
        val liveNewlines = booleanPreferencesKey("live_newlines")
        val controlNotification = booleanPreferencesKey("control_notification")
        val pressureWidthEnabled = booleanPreferencesKey("pressure_width_enabled")
        val knownPens = stringPreferencesKey("known_pens")          // JSON
        val forgottenPens = stringPreferencesKey("forgotten_pens")  // JSON array de MACs
        val lockedPenMac = stringPreferencesKey("locked_pen_mac")
        val defaultColor = intPreferencesKey("default_stroke_color")
        val defaultWidth = stringPreferencesKey("default_stroke_width") // Float como texto
        // Capacidades: conmutadores (enum.name) + credenciales (JSON, secreto cifrado)
        val ocrSource = stringPreferencesKey("cap_ocr")
        val ltSource = stringPreferencesKey("cap_lt")
        val markerSource = stringPreferencesKey("cap_marker")
        val connectorSource = stringPreferencesKey("cap_connector")
        val ocrLocal = stringPreferencesKey("ocr_local")            // JSON
        val ltLocal = stringPreferencesKey("lt_local")              // JSON
        val webdavLocal = stringPreferencesKey("webdav_local")      // JSON
        val ftpLocal = stringPreferencesKey("ftp_local")            // JSON
    }

    private fun capSource(raw: String?, default: CapabilitySource): CapabilitySource =
        runCatching { CapabilitySource.valueOf(raw ?: default.name) }.getOrDefault(default)

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
            offlineReimportPolicy = runCatching {
                OfflineReimportPolicy.valueOf(p[Keys.offlineReimportPolicy] ?: OfflineReimportPolicy.ASK.name)
            }.getOrDefault(OfflineReimportPolicy.ASK),
            recentShowCount = p[Keys.recentCount] ?: 10,
            recentClearedAt = p[Keys.recentCleared] ?: "",
            finishMode = p[Keys.finishMode] ?: "newSheet",
            overlapEnabled = p[Keys.overlapEnabled] ?: true,
            overlapMinStrokes = p[Keys.overlapMinStrokes] ?: 3,
            overlapMinAreaPercent = p[Keys.overlapMinArea] ?: 25,
            notebookView = p[Keys.notebookView] ?: "Mosaic",
            themeMode = p[Keys.themeMode] ?: "system",
            liveLanguage = p[Keys.liveLanguage] ?: "es",
            liveNewlines = p[Keys.liveNewlines] ?: false,
            controlNotification = p[Keys.controlNotification] ?: false,
            pressureWidthEnabled = p[Keys.pressureWidthEnabled] ?: false,
            knownPens = decodeKnownPens(p[Keys.knownPens]),
            forgottenPens = decodeStringList(p[Keys.forgottenPens]),
            lockedPenMac = p[Keys.lockedPenMac] ?: "",
            defaultStyle = StrokeStyle(
                color = p[Keys.defaultColor] ?: 0xFF111111.toInt(),
                width = p[Keys.defaultWidth]?.toFloatOrNull() ?: 1.6f,
            ),
            ocrSource = capSource(p[Keys.ocrSource], CapabilitySource.SERVER),
            languageToolSource = capSource(p[Keys.ltSource], CapabilitySource.OFF),
            markerDetectionSource = capSource(p[Keys.markerSource], CapabilitySource.SERVER),
            connectorSyncSource = capSource(p[Keys.connectorSource], CapabilitySource.OFF),
            ocrLocal = decodeOcrLocal(p[Keys.ocrLocal]),
            ltLocal = decodeLtLocal(p[Keys.ltLocal]),
            webdavLocal = decodeWebdavLocal(p[Keys.webdavLocal]),
            ftpLocal = decodeFtpLocal(p[Keys.ftpLocal]),
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
            p[Keys.offlineReimportPolicy] = s.offlineReimportPolicy.name
            p[Keys.recentCount] = s.recentShowCount
            p[Keys.recentCleared] = s.recentClearedAt
            p[Keys.finishMode] = s.finishMode
            p[Keys.overlapEnabled] = s.overlapEnabled
            p[Keys.overlapMinStrokes] = s.overlapMinStrokes
            p[Keys.overlapMinArea] = s.overlapMinAreaPercent
            p[Keys.notebookView] = s.notebookView
            p[Keys.themeMode] = s.themeMode
            p[Keys.liveLanguage] = s.liveLanguage
            p[Keys.liveNewlines] = s.liveNewlines
            p[Keys.controlNotification] = s.controlNotification
            p[Keys.pressureWidthEnabled] = s.pressureWidthEnabled
            p[Keys.knownPens] = encodeKnownPens(s.knownPens)
            p[Keys.forgottenPens] = encodeStringList(s.forgottenPens)
            p[Keys.lockedPenMac] = s.lockedPenMac
            p[Keys.defaultColor] = s.defaultStyle.color
            p[Keys.defaultWidth] = s.defaultStyle.width.toString()
            p[Keys.ocrSource] = s.ocrSource.name
            p[Keys.ltSource] = s.languageToolSource.name
            p[Keys.markerSource] = s.markerDetectionSource.name
            p[Keys.connectorSource] = s.connectorSyncSource.name
            p[Keys.ocrLocal] = encodeOcrLocal(s.ocrLocal)
            p[Keys.ltLocal] = encodeLtLocal(s.ltLocal)
            p[Keys.webdavLocal] = encodeWebdavLocal(s.webdavLocal)
            p[Keys.ftpLocal] = encodeFtpLocal(s.ftpLocal)
        }
    }

    // --- Credenciales de capacidades (JSON; solo el campo secreto va cifrado) ---

    private fun encodeOcrLocal(c: OcrLocalConfig): String = JSONObject()
        .put("baseUrl", c.baseUrl)
        .put("model", c.model)
        .put("instructions", c.instructions)
        .put("apiKey", PwCrypto.encrypt(c.apiKey))
        .toString()

    private fun decodeOcrLocal(json: String?): OcrLocalConfig {
        if (json.isNullOrBlank()) return OcrLocalConfig()
        return runCatching {
            val o = JSONObject(json)
            OcrLocalConfig(
                baseUrl = o.optString("baseUrl"),
                apiKey = PwCrypto.decrypt(o.optString("apiKey")),
                model = o.optString("model"),
                instructions = o.optString("instructions"),
            )
        }.getOrDefault(OcrLocalConfig())
    }

    private fun encodeLtLocal(c: LanguageToolLocalConfig): String = JSONObject()
        .put("baseUrl", c.baseUrl)
        .put("username", c.username)
        .put("apiKey", PwCrypto.encrypt(c.apiKey))
        .toString()

    private fun decodeLtLocal(json: String?): LanguageToolLocalConfig {
        if (json.isNullOrBlank()) return LanguageToolLocalConfig()
        return runCatching {
            val o = JSONObject(json)
            LanguageToolLocalConfig(
                baseUrl = o.optString("baseUrl", "https://api.languagetool.org/v2"),
                username = o.optString("username"),
                apiKey = PwCrypto.decrypt(o.optString("apiKey")),
            )
        }.getOrDefault(LanguageToolLocalConfig())
    }

    private fun encodeWebdavLocal(c: WebdavLocalConfig): String = JSONObject()
        .put("url", c.url)
        .put("username", c.username)
        .put("password", PwCrypto.encrypt(c.password))
        .put("basePath", c.basePath)
        .toString()

    private fun decodeWebdavLocal(json: String?): WebdavLocalConfig {
        if (json.isNullOrBlank()) return WebdavLocalConfig()
        return runCatching {
            val o = JSONObject(json)
            WebdavLocalConfig(
                url = o.optString("url"),
                username = o.optString("username"),
                password = PwCrypto.decrypt(o.optString("password")),
                basePath = o.optString("basePath", "/neopen"),
            )
        }.getOrDefault(WebdavLocalConfig())
    }

    private fun encodeFtpLocal(c: FtpLocalConfig): String = JSONObject()
        .put("url", c.url)
        .put("username", c.username)
        .put("password", PwCrypto.encrypt(c.password))
        .put("basePath", c.basePath)
        .toString()

    private fun decodeFtpLocal(json: String?): FtpLocalConfig {
        if (json.isNullOrBlank()) return FtpLocalConfig()
        return runCatching {
            val o = JSONObject(json)
            FtpLocalConfig(
                url = o.optString("url"),
                username = o.optString("username"),
                password = PwCrypto.decrypt(o.optString("password")),
                basePath = o.optString("basePath", "/neopen"),
            )
        }.getOrDefault(FtpLocalConfig())
    }

    private fun encodeKnownPens(pens: List<KnownPen>): String {
        val arr = JSONArray()
        for (p in pens) {
            arr.put(
                JSONObject()
                    .put("mac", p.mac)
                    .put("alias", p.alias)
                    .put("color", p.style.color)
                    .put("width", p.style.width.toDouble())
                    .put("pin", PwCrypto.encrypt(p.pin)),
            )
        }
        return arr.toString()
    }

    private fun decodeKnownPens(json: String?): List<KnownPen> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                KnownPen(
                    mac = o.getString("mac"),
                    alias = o.optString("alias", o.getString("mac")),
                    style = StrokeStyle(
                        color = o.optInt("color", 0xFF111111.toInt()),
                        width = o.optDouble("width", 1.6).toFloat(),
                    ),
                    pin = PwCrypto.decrypt(o.optString("pin")),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeStringList(values: List<String>): String {
        val arr = JSONArray()
        values.distinct().forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())
    }
}
