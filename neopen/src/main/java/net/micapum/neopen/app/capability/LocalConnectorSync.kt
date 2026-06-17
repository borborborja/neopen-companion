package net.micapum.neopen.app.capability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.micapum.neopen.app.Graph
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.app.data.Json
import net.micapum.neopen.app.data.Notebook
import net.micapum.neopen.app.data.toDomain
import net.micapum.neopen.app.db.ConnectorSyncStateEntity
import net.micapum.neopen.app.render.rasterizePng
import net.micapum.neopen.app.render.renderNotebookPdf
import net.micapum.neopen.app.render.renderPagePdf
import net.micapum.neopen.app.render.renderSvg
import net.micapum.neopen.core.capability.FileConnector
import net.micapum.neopen.core.capability.FtpConnector
import net.micapum.neopen.core.capability.WebdavConnector
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.CapabilitySource
import java.security.MessageDigest

/**
 * Espejo (mirroring) de las capturas/cuadernos a los conectores locales del
 * teléfono (WebDAV/FTP), port de `apps/server/src/sync/engine.ts`. Calcula el
 * conjunto de ficheros deseado, lo compara con lo ya subido (tabla
 * connector_sync_state) y sube/borra lo necesario. Reutiliza los render de la app.
 *
 * Solo actúa cuando la capacidad CONNECTOR_SYNC está en TELÉFONO (en SERVIDOR lo
 * hace el motor del servidor; el repliegue de conectores no aplica al failover de
 * la transcripción que pidió el usuario).
 */
class LocalConnectorSync(private val graph: Graph) {

    private val mutex = Mutex()

    suspend fun sync() = withContext(Dispatchers.IO) {
        // Una sola pasada a la vez (igual que el servidor).
        if (!mutex.tryLock()) return@withContext
        try {
            reconcile()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun reconcile() {
        val s = graph.settings.flow.first()
        if (s.connectorSyncSource != CapabilitySource.PHONE) return
        val connectors = buildConnectors(s)
        if (connectors.isEmpty()) return
        val connectorById = connectors.associateBy { it.id }

        val db = graph.db
        val captures = db.captures().getAll().map { it.toDomain() }
        val notebooks = db.notebooks().getAll().map { it.toDomain() }
        val nbById = notebooks.associateBy { it.id }
        val noSync = notebooks.filter { it.tags.contains(NO_SYNC_TAG) }.map { it.id }.toSet()

        val desired = computeDesired(captures, notebooks, nbById, noSync, connectors.map { it.id }, s.pressureWidthEnabled)
        val stateRows = db.connectorSyncState().all()
        val stateByKey = stateRows.associateBy { fileKey(it.itemId, it.connectorId, it.format) }

        // Altas / cambios / movimientos.
        for ((key, d) in desired) {
            val prev = stateByKey[key]
            if (prev != null && prev.sourceHash == d.hash) continue
            val connector = connectorById[d.connectorId] ?: continue
            runCatching {
                val (bytes, contentType) = d.render()
                val remotePath = connector.putPath(d.relativePath, bytes, contentType)
                if (prev != null && prev.remotePath != remotePath) {
                    runCatching { connector.delete(prev.remotePath) }
                }
                db.connectorSyncState().upsert(
                    ConnectorSyncStateEntity(
                        itemId = d.itemId,
                        connectorId = d.connectorId,
                        format = d.format,
                        remotePath = remotePath,
                        sourceHash = d.hash,
                        syncedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

        // Bajas: lo que está en estado pero ya no se desea.
        for (r in stateRows) {
            if (desired.containsKey(fileKey(r.itemId, r.connectorId, r.format))) continue
            val connector = connectorById[r.connectorId]
            if (connector == null) {
                db.connectorSyncState().delete(r.itemId, r.connectorId, r.format)
                continue
            }
            runCatching {
                connector.delete(r.remotePath)
                db.connectorSyncState().delete(r.itemId, r.connectorId, r.format)
            }
        }
    }

    private fun buildConnectors(s: AppSettings): List<FileConnector> = buildList {
        if (s.webdavLocal.isUsable) add(WebdavConnector(s.webdavLocal))
        if (s.ftpLocal.isUsable) add(FtpConnector(s.ftpLocal))
    }

    private fun computeDesired(
        captures: List<Capture>,
        notebooks: List<Notebook>,
        nbById: Map<String, Notebook>,
        noSync: Set<String>,
        connectorIds: List<String>,
        pressureWidthEnabled: Boolean,
    ): Map<String, Desired> {
        val desired = LinkedHashMap<String, Desired>()

        for (cap in captures) {
            if (!isSyncable(cap, noSync)) continue
            val folder = folderFor(cap, nbById)
            val base = pageBaseName(cap)
            for (format in PAGE_FORMATS) {
                // El PDF de un cuaderno es un libro combinado (se trata aparte).
                if (format == "pdf" && cap.destination == "notebook") continue
                if (format == "md") {
                    if (cap.destination == "inbox") continue // inbox sin Markdown
                    if (cap.transcription == null) continue // solo hojas transcritas
                }
                val relativePath = "$folder/$base.$format"
                val hash = pageHash(cap, format, relativePath)
                for (cid in connectorIds) {
                    desired[fileKey(cap.id, cid, format)] = Desired(
                        itemId = cap.id, connectorId = cid, format = format,
                        relativePath = relativePath, hash = hash,
                        render = { renderFormat(cap, format, "$base.png", nbById, pressureWidthEnabled) },
                    )
                }
            }
        }

        // PDF-libro por cuaderno (item "nb:<id>").
        for (nb in notebooks) {
            if (noSync.contains(nb.id)) continue
            val pages = captures
                .filter { it.destination == "notebook" && it.notebookId == nb.id }
                .sortedBy { it.position }
            if (pages.isEmpty()) continue
            val folder = slug(nb.name)
            val relativePath = "$folder/${slug(nb.name)}.pdf"
            val hash = notebookHash(nb, pages, relativePath)
            for (cid in connectorIds) {
                desired[fileKey("nb:${nb.id}", cid, "pdf")] = Desired(
                    itemId = "nb:${nb.id}", connectorId = cid, format = "pdf",
                    relativePath = relativePath, hash = hash,
                        render = { renderNotebookPdf(pages, pressureWidthEnabled) to "application/pdf" },
                )
            }
        }

        return desired
    }

    private fun isSyncable(cap: Capture, noSync: Set<String>): Boolean = when (cap.destination) {
        "inbox", "loose" -> true
        "notebook" -> cap.notebookId == null || !noSync.contains(cap.notebookId)
        else -> false
    }

    private fun folderFor(cap: Capture, nbById: Map<String, Notebook>): String = when (cap.destination) {
        "inbox" -> FOLDER_INBOX
        "loose" -> FOLDER_LOOSE
        else -> slug(nbById[cap.notebookId]?.name ?: "cuaderno")
    }

    /** Nombre de fichero de una hoja: slug del nombre + SOBP para evitar colisiones. */
    private fun pageBaseName(cap: Capture): String = "${slug(cap.name)}-${cap.sobpLabel}"

    private fun renderFormat(
        cap: Capture,
        format: String,
        attachmentName: String,
        nbById: Map<String, Notebook>,
        pressureWidthEnabled: Boolean,
    ): Pair<ByteArray, String> = when (format) {
        "svg" -> renderSvg(cap.strokes, cap.paper, pressureWidthEnabled).toByteArray() to "image/svg+xml"
        "png" -> rasterizePng(cap.strokes, cap.paper, pressureWidthEnabled = pressureWidthEnabled) to "image/png"
        "pdf" -> renderPagePdf(cap.strokes, cap.paper, pressureWidthEnabled) to "application/pdf"
        "md" -> buildMarkdown(cap, nbById[cap.notebookId]?.name, attachmentName).toByteArray() to "text/markdown"
        else -> throw IllegalArgumentException(format)
    }

    /** Frontmatter YAML + transcripción + enlace al adjunto (port de buildMarkdown). */
    private fun buildMarkdown(cap: Capture, notebookName: String?, attachmentName: String): String {
        val ocr = cap.transcription ?: return ""
        val fm = StringBuilder("---\n")
        fm.append("title: ${yaml(ocr.title ?: cap.name)}\n")
        fm.append("date: ${cap.capturedAt.take(10)}\n")
        fm.append("source: neo-smartpen\n")
        if (!notebookName.isNullOrBlank()) fm.append("notebook: ${yaml(notebookName)}\n")
        fm.append("sobp: \"${cap.sobpLabel}\"\n")
        if (!ocr.language.isNullOrBlank()) fm.append("lang: ${ocr.language}\n")
        if (ocr.tags.isNotEmpty()) fm.append("tags: [${ocr.tags.joinToString(", ") { yaml(it) }}]\n")
        if (ocr.dates.isNotEmpty()) {
            fm.append("dates:\n")
            for (d in ocr.dates) {
                val txt = if (!d.date.isNullOrBlank()) "${d.date} — ${d.text.orEmpty()}" else d.text.orEmpty()
                fm.append("  - ${yaml(txt)}\n")
            }
        }
        fm.append("---\n\n")

        val body = StringBuilder()
        body.append((ocr.text ?: "").trim()).append("\n\n")
        if (ocr.todos.isNotEmpty()) {
            body.append("## Tareas\n\n")
            for (t in ocr.todos) body.append("- [${if (t.done == true) "x" else " "}] ${t.text}\n")
            body.append("\n")
        }
        body.append("![[${attachmentName}]]\n")
        return fm.toString() + body.toString()
    }

    // --- Hashes (estables entre pasadas; no necesitan coincidir con el servidor) ---

    private fun pageHash(cap: Capture, format: String, path: String): String =
        sha1(Json.strokesJson(cap.strokes) + "|" + cap.name + "|" + (Json.ocrJson(cap.transcription) ?: "") + "|" + format + "|" + path)

    private fun notebookHash(nb: Notebook, pages: List<Capture>, path: String): String =
        sha1(pages.joinToString("|") { it.id + ":" + it.position + ":" + Json.strokesJson(it.strokes) } + "||" + nb.name + "|" + path)

    private fun yaml(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""

    private fun fileKey(itemId: String, connectorId: String, format: String): String =
        "$itemId|$connectorId|$format"

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class Desired(
        val itemId: String,
        val connectorId: String,
        val format: String,
        val relativePath: String,
        val hash: String,
        val render: () -> Pair<ByteArray, String>,
    )

    companion object {
        private const val NO_SYNC_TAG = "no-sync"
        private const val FOLDER_LOOSE = "Sueltas"
        private const val FOLDER_INBOX = "Inbox"

        /** Formatos por hoja. png alimenta el embed del .md; pdf/md según reglas. */
        private val PAGE_FORMATS = listOf("png", "pdf", "md")
    }
}

/** Slug seguro para nombres de fichero/carpeta (port de `slug` de packages/shared). */
fun slug(s: String): String =
    s.lowercase()
        .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "nota" }
