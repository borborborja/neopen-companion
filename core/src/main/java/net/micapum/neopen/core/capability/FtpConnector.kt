package net.micapum.neopen.core.capability

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.micapum.neopen.core.data.FtpLocalConfig
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Properties

/**
 * Conector FTP en sus tres variantes, elegidas por el esquema de la URL (port de
 * `apps/server/src/connectors/ftp.ts`):
 *   - ftp://host:21   → FTP plano (Commons Net)
 *   - ftps://host:21  → FTPS explícito (AUTH TLS); ftps://host:990 → implícito
 *   - sftp://host:22  → SFTP sobre SSH (JSch)
 * Abre una conexión por operación (suficiente para el uso personal de la app).
 */
class FtpConnector(private val cfg: FtpLocalConfig) : FileConnector {

    override val id = "ftp"

    private enum class Protocol { FTP, FTPS, SFTP }

    private val protocol: Protocol
    private val host: String
    private val port: Int
    private val basePath = ConnectorPaths.normalizeDir(cfg.basePath)

    init {
        val uri = URI(cfg.url)
        protocol = when (uri.scheme?.lowercase()) {
            "sftp" -> Protocol.SFTP
            "ftps" -> Protocol.FTPS
            else -> Protocol.FTP
        }
        host = uri.host ?: ""
        port = if (uri.port > 0) uri.port else if (protocol == Protocol.SFTP) 22 else 21
    }

    override suspend fun putPath(relativePath: String, bytes: ByteArray, contentType: String): String =
        withContext(Dispatchers.IO) {
            val remotePath = ConnectorPaths.joinPath(basePath, ConnectorPaths.sanitizeRelative(relativePath))
            if (protocol == Protocol.SFTP) putSftp(bytes, remotePath) else putFtp(bytes, remotePath)
            remotePath
        }

    override suspend fun delete(remotePath: String): Unit = withContext(Dispatchers.IO) {
        if (protocol == Protocol.SFTP) deleteSftp(remotePath) else deleteFtp(remotePath)
    }

    // --- FTP / FTPS ----------------------------------------------------------

    private fun putFtp(bytes: ByteArray, remotePath: String) = withFtp { client ->
        val dir = ConnectorPaths.parentDir(remotePath)
        ensureFtpDir(client, dir)
        if (!client.changeWorkingDirectory(dir)) throw RuntimeException("FTP cd $dir")
        ByteArrayInputStream(bytes).use {
            if (!client.storeFile(ConnectorPaths.baseName(remotePath), it)) {
                throw RuntimeException("FTP store ${client.replyString}")
            }
        }
    }

    private fun deleteFtp(remotePath: String) = withFtp { client ->
        client.deleteFile(remotePath) // 550 = no existe → idempotente, no lanzamos
        Unit
    }

    /** makeDirectory de cada ancestro (ignora "ya existe"). */
    private fun ensureFtpDir(client: FTPClient, dir: String) {
        if (dir == "/" || dir.isEmpty()) return
        var current = ""
        for (seg in ConnectorPaths.segments(dir)) {
            current += "/$seg"
            client.makeDirectory(current)
        }
    }

    private fun <T> withFtp(fn: (FTPClient) -> T): T {
        val client = if (protocol == Protocol.FTPS) FTPSClient(port == 990) else FTPClient()
        try {
            client.connect(host, port)
            if (!client.login(cfg.username, cfg.password)) throw RuntimeException("FTP login")
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            return fn(client)
        } finally {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }

    // --- SFTP ----------------------------------------------------------------

    private fun putSftp(bytes: ByteArray, remotePath: String) = withSftp { ch ->
        mkdirsSftp(ch, ConnectorPaths.parentDir(remotePath))
        ByteArrayInputStream(bytes).use { ch.put(it, remotePath, ChannelSftp.OVERWRITE) }
    }

    private fun deleteSftp(remotePath: String) = withSftp { ch ->
        runCatching { ch.rm(remotePath) } // no existe → idempotente
        Unit
    }

    /** Crea cada carpeta ancestro si falta (stat → mkdir). */
    private fun mkdirsSftp(ch: ChannelSftp, dir: String) {
        if (dir == "/" || dir.isEmpty()) return
        var current = ""
        for (seg in ConnectorPaths.segments(dir)) {
            current += "/$seg"
            val exists = runCatching { ch.stat(current); true }.getOrDefault(false)
            if (!exists) runCatching { ch.mkdir(current) }
        }
    }

    private fun <T> withSftp(fn: (ChannelSftp) -> T): T {
        val session: Session = JSch().getSession(cfg.username, host, port)
        session.setPassword(cfg.password)
        session.setConfig(Properties().apply { put("StrictHostKeyChecking", "no") })
        session.connect(15000)
        try {
            val ch = session.openChannel("sftp") as ChannelSftp
            ch.connect(15000)
            try {
                return fn(ch)
            } finally {
                ch.disconnect()
            }
        } finally {
            session.disconnect()
        }
    }
}
