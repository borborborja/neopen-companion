package net.micapum.neopen.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.app.data.PageBackground
import net.micapum.neopen.app.render.BgPresets
import net.micapum.neopen.app.render.InkCanvas

/** "section.owner.book" de una hoja (sin la página). */
private fun bookKeyOf(c: Capture): String = "${c.section}.${c.owner}.${c.book}"

/** Nombre legible de cada preset del catálogo. */
fun presetLabel(id: String): String = when (id) {
    BgPresets.LINED -> "Líneas"
    BgPresets.GRID -> "Cuadrícula"
    BgPresets.DOTS -> "Puntos"
    BgPresets.MUSIC -> "Pentagrama"
    else -> id
}

/** URL absoluta de un fondo del servidor (relativo `/backgrounds/...` o ya http). */
private fun bgUrl(serverUrl: String, path: String): String =
    if (path.startsWith("http")) path else serverUrl.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")

/** Carga el fondo (imagen) de un anclaje a un ImageBitmap, o null si es preset/sin imagen. */
@Composable
private fun rememberBgImage(vm: MainViewModel, bg: PageBackground?): ImageBitmap? {
    val ctx = LocalContext.current
    val img = bg?.bgImage
    val server = vm.serverUrl()
    return produceState<ImageBitmap?>(initialValue = null, img, server) {
        value = if (img == null) null else withContext(Dispatchers.IO) {
            runCatching {
                if (img.startsWith("file://")) {
                    BitmapFactory.decodeFile(img.removePrefix("file://"))?.asImageBitmap()
                } else {
                    val req = ImageRequest.Builder(ctx).data(bgUrl(server, img)).allowHardware(false).build()
                    (ImageLoader(ctx).execute(req).drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
                }
            }.getOrNull()
        }
    }.value
}

/**
 * Como [InkCanvas] pero resolviendo y pintando el fondo anclado a la hoja
 * (imagen o preset del catálogo) debajo de los trazos. Usar donde se muestre una
 * captura para que se vea su plantilla.
 */
@Composable
fun PageCanvas(vm: MainViewModel, page: Capture, modifier: Modifier = Modifier) {
    val bk = bookKeyOf(page)
    val bg by remember(bk, page.page) { vm.observeSheetBackground(bk, page.page) }
        .collectAsStateWithLifecycle(initialValue = null)
    val bgImage = rememberBgImage(vm, bg)
    InkCanvas(
        strokes = page.strokes,
        paper = page.paper,
        modifier = modifier,
        bgImage = bgImage,
        bgPreset = if (bgImage == null) bg?.bgPreset else null,
    )
}

/**
 * Selector de fondo (catálogo + subir imagen/PDF + recientes/presets si Studio).
 * `scope` = "book" (fondo de cuaderno) o "page" (fondo de hoja). En ámbito hoja,
 * `page` es su número.
 */
@Composable
fun BackgroundPickerDialog(
    vm: MainViewModel,
    scope: String,
    bookKey: String,
    page: Int?,
    currentLabel: String?,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = rememberCoroutineScope()
    val studioEnabled by vm.studioEnabled.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val server = vm.serverUrl()

    LaunchedEffect(Unit) { vm.refreshStudio() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) cs.launch {
            val bytes = withContext(Dispatchers.IO) { uriToPngBytes(ctx, uri) }
            if (bytes != null) {
                vm.setBackground(scope, bookKey, page, ResolvedBg.Png(bytes, "Plantilla"))
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (scope == "book") "Fondo del cuaderno" else "Fondo de la hoja") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (currentLabel != null) {
                    Text("Plantilla actual: $currentLabel", style = MaterialTheme.typography.bodySmall)
                }
                Text("Catálogo", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BgPresets.ALL.forEach { id ->
                        OutlinedButton(onClick = {
                            vm.setBackground(scope, bookKey, page, ResolvedBg.Preset(id, presetLabel(id)))
                            onDismiss()
                        }) { Text(presetLabel(id)) }
                    }
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }) {
                    Text("Subir imagen o PDF")
                }

                if (studioEnabled && recents.any { it.bgImage != null }) {
                    Text("Recientes", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recents.forEach { r ->
                            val url = r.bgImage ?: return@forEach
                            AsyncImage(
                                model = bgUrl(server, url),
                                contentDescription = r.label,
                                modifier = Modifier.size(56.dp, 78.dp).clickableThumb {
                                    vm.setBackground(scope, bookKey, page, ResolvedBg.ServerUrl(url, r.label.ifBlank { "Reciente" }))
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
                if (studioEnabled && presets.any { it.bgImage != null }) {
                    Text("Presets", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { p ->
                            val url = p.bgImage ?: return@forEach
                            AsyncImage(
                                model = bgUrl(server, url),
                                contentDescription = p.label,
                                modifier = Modifier.size(56.dp, 78.dp).clickableThumb {
                                    vm.setBackground(scope, bookKey, page, ResolvedBg.ServerUrl(url, p.label.ifBlank { "Preset" }))
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = if (onRemove != null) {
            { TextButton(onClick = { onRemove(); onDismiss() }) { Text("Quitar fondo") } }
        } else null,
    )
}

// --- helpers ---------------------------------------------------------------

private fun Modifier.clickableThumb(onClick: () -> Unit): Modifier =
    this.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)

/** Convierte una imagen o PDF (Uri) a PNG (primera página del PDF), reescalado. */
fun uriToPngBytes(ctx: Context, uri: Uri): ByteArray? {
    val type = ctx.contentResolver.getType(uri) ?: ""
    val bmp: Bitmap? = if (type == "application/pdf" || uri.toString().endsWith(".pdf")) {
        renderPdfFirstPage(ctx, uri)
    } else {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }
    val scaled = bmp?.let { downscale(it, 1600) } ?: return null
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
    if (scaled !== bmp) scaled.recycle()
    bmp.recycle()
    return out.toByteArray()
}

private fun renderPdfFirstPage(ctx: Context, uri: Uri): Bitmap? = runCatching {
    val tmp = java.io.File.createTempFile("bgpdf", ".pdf", ctx.cacheDir)
    ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    val page = renderer.openPage(0)
    val scale = 2
    val bmp = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    canvas.drawColor(AndroidColor.WHITE)
    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    page.close()
    renderer.close()
    pfd.close()
    tmp.delete()
    bmp
}.getOrNull()

private fun downscale(src: Bitmap, max: Int): Bitmap {
    val w = src.width
    val h = src.height
    val longest = maxOf(w, h)
    if (longest <= max) return src
    val ratio = max.toFloat() / longest
    return Bitmap.createScaledBitmap(src, (w * ratio).toInt(), (h * ratio).toInt(), true)
}
