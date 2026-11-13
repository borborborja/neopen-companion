package net.micapum.neopen.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Archivos privados: una sola descarga por diseño, compartida por sus páginas. */
class PreviewAssetStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "studio-assets").apply { mkdirs() }
    private val downloads = PreviewLocks()

    suspend fun file(
        namespace: String,
        key: String,
        legacyFiles: List<File> = emptyList(),
        download: suspend (File) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        // Cerrar una biblioteca borra los archivos sin recrear el ViewModel/store.
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "Cannot create preview directory" }
        val name = MessageDigest.getInstance("SHA-256").digest("$namespace:$key".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, name)
        downloads.withKey(name) {
            if (target.isFile && target.length() > 0) return@withKey target
            legacyFiles.firstOrNull { it.isFile && it.length() > 0 }?.let { return@withKey it }
            val temporary = File.createTempFile("preview-", ".part", directory)
            try {
                download(temporary)
                currentCoroutineContext().ensureActive()
                check(temporary.length() > 0) { "Empty preview asset" }
                check(temporary.renameTo(target)) { "Cannot persist preview asset" }
                target
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun render(file: File, index: Int, widthPx: Int): Bitmap? {
        val width = widthPx.coerceIn(1, 2048)
        val key = "pdf:${file.canonicalPath}:${file.lastModified()}:${file.length()}:$index:$width"
        return PagePreviewCache.getOrRender(key) {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                if (index !in 0 until renderer.pageCount) return@use null
                renderer.openPage(index).use { page ->
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, 4096)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } catch (error: Throwable) {
                            bitmap.recycle()
                            throw error
                        }
                    }
                }
            }
        }
    }
}
