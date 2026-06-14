package net.micapum.neopen.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

private fun cacheUri(context: Context, file: ExportFile): Uri {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, file.filename)
    f.writeBytes(file.bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
}

/** Abre la hoja de compartir de Android con el fichero exportado (incluye «Guardar en Archivos»). */
fun shareExport(context: Context, file: ExportFile) {
    val uri = cacheUri(context, file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = file.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Compartir"))
}

/** Guarda en Descargas (API 29+); en versiones previas recurre a la hoja de compartir. */
fun saveExport(context: Context, file: ExportFile) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        shareExport(context, file)
        return
    }
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, file.filename)
        put(MediaStore.Downloads.MIME_TYPE, file.mime)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        Toast.makeText(context, "No se pudo guardar", Toast.LENGTH_SHORT).show()
        return
    }
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { it.write(file.bytes) } ?: error("sin stream")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }.isSuccess
    Toast.makeText(context, if (ok) "Guardado en Descargas" else "No se pudo guardar", Toast.LENGTH_SHORT).show()
}
