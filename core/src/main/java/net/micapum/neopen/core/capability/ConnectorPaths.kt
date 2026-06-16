package net.micapum.neopen.core.capability

/**
 * Utilidades de ruta para los conectores (port de `apps/server/src/connectors/paths.ts`).
 * Centralizadas para que WebDAV y FTP saneen/normalicen igual que el servidor.
 */
internal object ConnectorPaths {
    private val SLASHES = Regex("/+")
    private val DOTS = Regex("\\.\\.+")
    private val SEP = Regex("[/\\\\]")

    /** Evita path traversal y caracteres problemáticos en un nombre de fichero. */
    fun sanitizeName(name: String): String = name.replace(SEP, "_").replace(DOTS, "_")

    /** Igual que [sanitizeName] pero conservando las barras de subcarpeta. */
    fun sanitizeRelative(path: String): String =
        path.split("/").map { sanitizeName(it) }.filter { it.isNotEmpty() }.joinToString("/")

    /** Normaliza una carpeta a "/a/b" (con barra inicial, sin barra final). */
    fun normalizeDir(p: String): String {
        val trimmed = "/$p".replace(SLASHES, "/").trimEnd('/')
        return trimmed.ifEmpty { "/" }
    }

    /** Une carpeta + nombre colapsando barras repetidas (rutas absolutas). */
    fun joinPath(dir: String, name: String): String = "$dir/$name".replace(SLASHES, "/")

    /** Carpeta padre de una ruta absoluta ("/a/b/c" → "/a/b"). */
    fun parentDir(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "/" else path.substring(0, idx)
    }

    /** Último segmento de una ruta ("/a/b/c" → "c"). */
    fun baseName(path: String): String = path.substring(path.lastIndexOf('/') + 1)

    /** Segmentos no vacíos de una ruta. */
    fun segments(path: String): List<String> = path.split("/").filter { it.isNotEmpty() }
}
