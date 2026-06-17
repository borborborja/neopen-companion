package net.micapum.neopen.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.micapum.neopen.app.R
import net.micapum.neopen.app.graph
import net.micapum.neopen.app.render.renderNotebookPdf
import net.micapum.neopen.app.render.renderPagePdf
import java.io.File

const val EXTRA_SHARE_KIND = "share_kind"
const val EXTRA_SHARE_ID = "share_id"
const val SHARE_KIND_NOTEBOOK = "notebook"
const val SHARE_KIND_CAPTURE = "capture"

class ShareExportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_SHARE_KIND)
        val id = intent.getStringExtra(EXTRA_SHARE_ID)
        if (kind.isNullOrBlank() || id.isNullOrBlank()) {
            finish()
            return
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { buildExportFile(kind, id) }
            if (file == null) {
                Toast.makeText(this@ShareExportActivity, getString(R.string.wgt_share_failed), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val uri = cacheUri(this@ShareExportActivity, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = file.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.exp_share)))
            finish()
        }
    }

    private suspend fun buildExportFile(kind: String, id: String): WidgetExportFile? {
        val g = applicationContext.graph
        val s = g.settings.flow.first()
        return when (kind) {
            SHARE_KIND_NOTEBOOK -> {
                val nb = g.repo.notebook(id) ?: return null
                val pages = g.repo.capturesIn(id).first()
                WidgetExportFile(
                    "${fileBaseName(nb.name)}.pdf",
                    "application/pdf",
                    renderNotebookPdf(pages, s.pressureWidthEnabled, s.pressureWidthContrast),
                )
            }
            SHARE_KIND_CAPTURE -> {
                val cap = g.repo.capture(id) ?: return null
                WidgetExportFile(
                    "${fileBaseName(cap.name)}.pdf",
                    "application/pdf",
                    renderPagePdf(cap.strokes, cap.paper, s.pressureWidthEnabled, s.pressureWidthContrast),
                )
            }
            else -> null
        }
    }
}

private data class WidgetExportFile(val filename: String, val mime: String, val bytes: ByteArray)

private fun cacheUri(context: Context, file: WidgetExportFile): Uri {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, file.filename)
    f.writeBytes(file.bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
}

private fun fileBaseName(name: String): String {
    val cleaned = name.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "").replace(' ', '_').take(60)
    return cleaned.ifBlank { "neopen" }
}
