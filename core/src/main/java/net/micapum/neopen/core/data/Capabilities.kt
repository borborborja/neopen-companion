package net.micapum.neopen.core.data

/**
 * Modelo de "capacidades despachables": cada funcionalidad inteligente del sistema
 * (OCR, corrección con LanguageTool, detección de marcadores y sincronización a
 * conectores) puede ejecutarse en el teléfono, en el servidor, o estar apagada.
 *
 * El servidor neopen las ofrece todas; el companion replica las mismas para poder
 * funcionar en local (con credenciales propias) o delegar en el servidor. Con un
 * servidor conectado, un conmutador de 3 estados decide quién hace el trabajo.
 */

/** Quién ejecuta una capacidad. */
enum class CapabilitySource { OFF, PHONE, SERVER }

/** Capacidades que pueden ejecutarse en el teléfono o el servidor. */
enum class Capability { OCR, LANGUAGE_TOOL, MARKER_DETECTION, CONNECTOR_SYNC }

/**
 * Instrucciones por defecto del OCR (port de `DEFAULT_OCR_INSTRUCTIONS` de
 * packages/shared). El contrato JSON lo añade aparte `LocalOcrProvider`, así que
 * el usuario puede cambiar el "cómo" sin romper el parseo.
 */
const val DEFAULT_OCR_INSTRUCTIONS =
    "Transcribe fielmente esta nota manuscrita. " +
        "Respeta los saltos de línea, las listas y las viñetas. Mantén el idioma original del texto. " +
        "Infiere un título breve y descriptivo. " +
        "Extrae etiquetas temáticas relevantes. " +
        "Detecta tareas pendientes (to-dos, casillas, cosas por hacer) y cualquier fecha o evento mencionado."

/** Credenciales del OCR local: endpoint compatible con OpenAI (`/chat/completions`). */
data class OcrLocalConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** Override opcional de las instrucciones; vacío → [DEFAULT_OCR_INSTRUCTIONS]. */
    val instructions: String = "",
) {
    val isUsable get() = baseUrl.isNotBlank() && model.isNotBlank()
    val effectiveInstructions get() = instructions.ifBlank { DEFAULT_OCR_INSTRUCTIONS }
}

/** Credenciales de LanguageTool local (público en api.languagetool.org o self-hosted). */
data class LanguageToolLocalConfig(
    val baseUrl: String = "https://api.languagetool.org/v2",
    val username: String = "",
    val apiKey: String = "",
) {
    val isUsable get() = baseUrl.isNotBlank()
}

/** Credenciales del conector WebDAV local (Nextcloud/ownCloud/Obsidian remoto). */
data class WebdavLocalConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val basePath: String = "/neopen",
) {
    val isUsable get() = url.isNotBlank()
}

/** Credenciales del conector FTP/FTPS/SFTP local (el esquema de la URL elige el tipo). */
data class FtpLocalConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val basePath: String = "/neopen",
) {
    val isUsable get() = url.isNotBlank()
}

/** Disponibilidad de una capacidad en cada lado (para habilitar/atenuar la UI). */
data class CapabilityAvailability(
    val phoneAvailable: Boolean,
    val serverAvailable: Boolean,
)

/** El conmutador almacenado para una capacidad. */
fun AppSettings.sourceOf(cap: Capability): CapabilitySource = when (cap) {
    Capability.OCR -> ocrSource
    Capability.LANGUAGE_TOOL -> languageToolSource
    Capability.MARKER_DETECTION -> markerDetectionSource
    Capability.CONNECTOR_SYNC -> connectorSyncSource
}

/** ¿Hay credenciales locales utilizables para ejecutar [cap] en el teléfono? */
fun AppSettings.phoneCapable(cap: Capability): Boolean = when (cap) {
    Capability.OCR -> ocrLocal.isUsable
    Capability.LANGUAGE_TOOL -> ltLocal.isUsable
    Capability.MARKER_DETECTION -> true // puro local, sin credenciales
    Capability.CONNECTOR_SYNC -> webdavLocal.isUsable || ftpLocal.isUsable
}

/**
 * Disponibilidad de [cap]. `serverReachable` es la señal viva de conectividad/
 * salud del servidor (Graph.online o el ServerCheck explícito). En modo LOCAL el
 * servidor nunca está disponible aunque haya credenciales.
 */
fun AppSettings.availabilityOf(cap: Capability, serverReachable: Boolean): CapabilityAvailability =
    CapabilityAvailability(
        phoneAvailable = phoneCapable(cap),
        serverAvailable = appMode != AppMode.LOCAL && configured && serverReachable,
    )

/**
 * Fuente efectiva en tiempo de ejecución: degrada a OFF lo que el usuario eligió
 * pero ya no está disponible. SERVER se respeta tal cual; el repliegue al teléfono
 * lo gestiona el dispatcher en el momento de la llamada (si el servidor falla y hay
 * credenciales locales).
 */
fun AppSettings.effectiveSource(cap: Capability, serverReachable: Boolean): CapabilitySource {
    val avail = availabilityOf(cap, serverReachable)
    return when (sourceOf(cap)) {
        CapabilitySource.OFF -> CapabilitySource.OFF
        CapabilitySource.PHONE -> if (avail.phoneAvailable) CapabilitySource.PHONE else CapabilitySource.OFF
        CapabilitySource.SERVER -> if (avail.serverAvailable) CapabilitySource.SERVER else CapabilitySource.OFF
    }
}
