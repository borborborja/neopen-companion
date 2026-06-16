package net.micapum.neopen.core.capability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.micapum.neopen.core.data.WebdavLocalConfig
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Conector WebDAV (Nextcloud, ownCloud, Apache mod_dav…) sobre OkHttp, sin
 * dependencias extra. Port de `apps/server/src/connectors/webdav.ts`: sube con PUT
 * y crea las carpetas que falten con MKCOL por cada segmento ancestro.
 */
class WebdavConnector(
    private val cfg: WebdavLocalConfig,
    private val client: OkHttpClient = defaultClient,
) : FileConnector {

    override val id = "webdav"
    private val basePath = ConnectorPaths.normalizeDir(cfg.basePath)
    private val cred = Credentials.basic(cfg.username, cfg.password)
    private val root = cfg.url.trimEnd('/').toHttpUrl()

    override suspend fun putPath(relativePath: String, bytes: ByteArray, contentType: String): String =
        withContext(Dispatchers.IO) {
            val remotePath = ConnectorPaths.joinPath(basePath, ConnectorPaths.sanitizeRelative(relativePath))
            ensureDir(ConnectorPaths.parentDir(remotePath))
            val req = Request.Builder()
                .url(urlFor(remotePath))
                .header("Authorization", cred)
                .put(bytes.toRequestBody(contentType.toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw RuntimeException("WebDAV PUT ${res.code}: $remotePath")
                }
            }
            remotePath
        }

    override suspend fun delete(remotePath: String): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(urlFor(remotePath)).header("Authorization", cred).delete().build()
        client.newCall(req).execute().use { res ->
            // 404 = ya no existe → idempotente.
            if (!res.isSuccessful && res.code != 404) {
                throw RuntimeException("WebDAV DELETE ${res.code}: $remotePath")
            }
        }
    }

    /** Crea cada carpeta ancestro con MKCOL (405 = ya existe → se ignora). */
    private fun ensureDir(dir: String) {
        if (dir == "/" || dir.isEmpty()) return
        var current = ""
        for (seg in ConnectorPaths.segments(dir)) {
            current += "/$seg"
            val req = Request.Builder()
                .url(urlFor(current))
                .header("Authorization", cred)
                .method("MKCOL", null)
                .build()
            client.newCall(req).execute().use { /* 201 creado, 405 ya existe: ignorar */ }
        }
    }

    private fun urlFor(remotePath: String): HttpUrl {
        val b = root.newBuilder()
        for (seg in ConnectorPaths.segments(remotePath)) b.addPathSegment(seg)
        return b.build()
    }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(120, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }
}
