package net.micapum.neopen.core.capability

import net.micapum.neopen.core.data.OcrResultDto

/**
 * Interfaces de las capacidades despachables. Cada una tiene una implementación
 * "local" (en el teléfono, con credenciales propias) que replica lo que el
 * servidor neopen hace en su backend.
 */

/** Transcripción de una página (imagen PNG) a resultado estructurado. */
interface OcrProvider {
    suspend fun transcribe(pngBytes: ByteArray, instructions: String): OcrResultDto
}

/** Corrección de texto (ortografía/gramática). */
interface TextCorrector {
    suspend fun correct(text: String, language: String?): String
}

/** Conector de ficheros (WebDAV/FTP/SFTP) para la sincronización local. */
interface FileConnector {
    val id: String

    /**
     * Sube [bytes] a `basePath/<relativePath saneado>` creando las carpetas que
     * falten. Devuelve la ruta remota ABSOLUTA resultante (la que se guarda en el
     * estado de sync y se pasa luego a [delete]).
     */
    suspend fun putPath(relativePath: String, bytes: ByteArray, contentType: String): String

    /** Borra el fichero en la ruta remota absoluta devuelta por [putPath]. Idempotente. */
    suspend fun delete(remotePath: String)
}
