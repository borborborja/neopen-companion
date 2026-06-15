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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.micapum.neopen.app.R
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
 * (imagen o preset del catálogo) debajo de los trazos.
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
 * Miniatura NÍTIDA de un preset del catálogo para el selector (el patrón real del
 * fondo es tenue; aquí lo dibujamos marcado en oscuro sobre el chip blanco).
 */
@Composable
private fun PresetThumb(id: String, modifier: Modifier = Modifier) {
    val ink = Color(0xFF334155)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sw = (h / 80f).coerceIn(2f, 4f)
        when (id) {
            BgPresets.LINED -> {
                val step = h / 7f
                var y = step
                while (y < h - 1f) {
                    drawLine(ink, Offset(w * 0.12f, y), Offset(w * 0.88f, y), sw)
                    y += step
                }
            }
            BgPresets.GRID -> {
                val step = h / 7f
                var y = step
                while (y < h - 1f) {
                    drawLine(ink, Offset(0f, y), Offset(w, y), sw)
                    y += step
                }
                var x = step
                while (x < w - 1f) {
                    drawLine(ink, Offset(x, 0f), Offset(x, h), sw)
                    x += step
                }
            }
            BgPresets.DOTS -> {
                val step = h / 7f
                var y = step
                while (y < h - 1f) {
                    var x = step
                    while (x < w - 1f) {
                        drawCircle(ink, radius = sw, center = Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
            BgPresets.MUSIC -> {
                val gap = h / 22f
                val groupGap = gap * 3f
                var y = gap * 1.5f
                while (y + gap * 4f < h) {
                    for (i in 0 until 5) {
                        drawLine(ink, Offset(w * 0.1f, y + i * gap), Offset(w * 0.9f, y + i * gap), sw * 0.7f)
                    }
                    y += gap * 4f + groupGap
                }
            }
        }
    }
}

/** Tarjeta de una opción: preview arriba (cuadro blanco) + etiqueta completa debajo. */
@Composable
private fun BgCard(label: String, onClick: () -> Unit, preview: @Composable BoxScope.() -> Unit) {
    Column(
        modifier = Modifier.width(100.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .clickable(onClick = onClick),
            content = preview,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Selector de fondo (diálogo ancho con rejilla de previews). `scope` = "book"
 * (fondo de cuaderno) o "page" (fondo de hoja). En ámbito hoja, `page` es su nº.
 * Catálogo + subir imagen/PDF siempre; recientes/presets si Studio está activo
 * (modo completo). Igual que la versión web.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    val gridH = Arrangement.spacedBy(12.dp)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 640.dp),
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    if (scope == "book") stringResource(R.string.bg_title_book) else stringResource(R.string.bg_title_sheet),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (currentLabel != null) {
                    Text(
                        stringResource(R.string.bg_current, currentLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(stringResource(R.string.bg_catalog), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = gridH, verticalArrangement = gridH) {
                    BgPresets.ALL.forEach { id ->
                        BgCard(
                            label = presetLabel(id),
                            onClick = {
                                vm.setBackground(scope, bookKey, page, ResolvedBg.Preset(id, presetLabel(id)))
                                onDismiss()
                            },
                        ) { PresetThumb(id, Modifier.fillMaxSize()) }
                    }
                }

                OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bg_upload_image_or_pdf))
                }

                if (studioEnabled && presets.any { it.bgImage != null }) {
                    Text(stringResource(R.string.bg_presets), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = gridH, verticalArrangement = gridH) {
                        presets.forEach { p ->
                            val url = p.bgImage ?: return@forEach
                            val label = p.label.ifBlank { "Preset" }
                            BgCard(
                                label = label,
                                onClick = {
                                    vm.setBackground(scope, bookKey, page, ResolvedBg.ServerUrl(url, label))
                                    onDismiss()
                                },
                            ) {
                                AsyncImage(
                                    model = bgUrl(server, url),
                                    contentDescription = label,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                if (studioEnabled && recents.any { it.bgImage != null }) {
                    Text(stringResource(R.string.bg_recents), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = gridH, verticalArrangement = gridH) {
                        recents.forEach { r ->
                            val url = r.bgImage ?: return@forEach
                            val label = r.label.ifBlank { "Reciente" }
                            BgCard(
                                label = label,
                                onClick = {
                                    vm.setBackground(scope, bookKey, page, ResolvedBg.ServerUrl(url, label))
                                    onDismiss()
                                },
                            ) {
                                AsyncImage(
                                    model = bgUrl(server, url),
                                    contentDescription = label,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (onRemove != null) {
                        TextButton(onClick = { onRemove(); onDismiss() }) { Text(stringResource(R.string.bg_remove)) }
                        Spacer(Modifier.size(8.dp))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.bg_close)) }
                }
            }
        }
    }
}

// --- helpers ---------------------------------------------------------------

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
