package net.micapum.neopen.app.render

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.StrokeDto

/** Modelo de render: polilíneas ya en px dentro de un bounding box. */
data class RenderModel(
    val width: Float,
    val height: Float,
    val strokeWidth: Float,
    val polylines: List<List<Offset>>,
)

private data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

/**
 * Puerto Kotlin de `buildModel()` de packages/shared (misma fórmula celdas→px,
 * mismos defaults scale=28, padding=24, strokeWidth=1.6 → paridad con la web).
 */
fun buildModel(
    strokes: List<StrokeDto>,
    paper: PaperDto?,
    scale: Float = 28f,
    padding: Float = 24f,
    strokeWidth: Float = 1.6f,
): RenderModel {
    val b = if (paper != null) {
        Bounds(paper.x0, paper.y0, paper.x0 + paper.width, paper.y0 + paper.height)
    } else {
        computeBounds(strokes)
    }
    val polylines = strokes.map { st ->
        st.dots.map { d -> Offset((d.x - b.minX) * scale + padding, (d.y - b.minY) * scale + padding) }
    }
    return RenderModel(
        width = maxOf(1f, (b.maxX - b.minX) * scale + padding * 2),
        height = maxOf(1f, (b.maxY - b.minY) * scale + padding * 2),
        strokeWidth = strokeWidth,
        polylines = polylines,
    )
}

private fun computeBounds(strokes: List<StrokeDto>): Bounds {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (st in strokes) for (d in st.dots) {
        if (d.x < minX) minX = d.x
        if (d.y < minY) minY = d.y
        if (d.x > maxX) maxX = d.x
        if (d.y > maxY) maxY = d.y
    }
    if (!minX.isFinite()) {
        minX = 0f; minY = 0f; maxX = 1f; maxY = 1f
    }
    return Bounds(minX, minY, maxX, maxY)
}

internal const val INK = 0xFF111111.toInt()

/** Pinta las polilíneas del modelo en un Canvas de android.graphics (PNG/PDF). */
internal fun drawModelTo(canvas: android.graphics.Canvas, m: RenderModel) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        style = Paint.Style.STROKE
        strokeWidth = m.strokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (pts in m.polylines) {
        if (pts.isEmpty()) continue
        if (pts.size == 1) {
            canvas.drawPoint(pts[0].x, pts[0].y, paint)
            continue
        }
        val path = android.graphics.Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        canvas.drawPath(path, paint)
    }
}

/**
 * Rasteriza una página a PNG (fondo blanco, tinta #111). `dpiScale=2` para
 * export/OCR nítidos (igual que la web, que exporta a 2×).
 */
fun rasterizePng(strokes: List<StrokeDto>, paper: PaperDto?, dpiScale: Float = 2f): ByteArray {
    val m = buildModel(
        strokes,
        paper,
        scale = 28f * dpiScale,
        padding = 24f * dpiScale,
        strokeWidth = 1.6f * dpiScale,
    )
    val w = m.width.toInt().coerceIn(1, 6000)
    val h = m.height.toInt().coerceIn(1, 6000)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)
    drawModelTo(canvas, m)
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    return out.toByteArray()
}

/** Ids del catálogo de fondos preestablecidos (espejo de BG_PRESETS de la web). */
object BgPresets {
    const val LINED = "lined"
    const val GRID = "grid"
    const val DOTS = "dots"
    const val MUSIC = "music"
    val ALL = listOf(LINED, GRID, DOTS, MUSIC)
}

/** Pinta un preset del catálogo (patrón tenue) dentro del recuadro de la hoja. */
private fun DrawScope.drawBgPreset(id: String, left: Float, top: Float, w: Float, h: Float) {
    val color = Color(0xFF64748B)
    val a = 0.45f
    val line = (h / 220f).coerceIn(0.8f, 2f)
    when (id) {
        BgPresets.LINED -> {
            val step = h / 26f
            var y = top + step
            while (y < top + h) { drawLine(color, Offset(left, y), Offset(left + w, y), line, alpha = a); y += step }
        }
        BgPresets.GRID -> {
            val step = h / 26f
            var y = top + step
            while (y < top + h) { drawLine(color, Offset(left, y), Offset(left + w, y), line, alpha = a); y += step }
            var x = left + step
            while (x < left + w) { drawLine(color, Offset(x, top), Offset(x, top + h), line, alpha = a); x += step }
        }
        BgPresets.DOTS -> {
            val step = h / 26f
            var y = top + step
            while (y < top + h) {
                var x = left + step
                while (x < left + w) { drawCircle(color, radius = line * 0.9f, center = Offset(x, y), alpha = a); x += step }
                y += step
            }
        }
        BgPresets.MUSIC -> {
            val gap = h / 70f
            val groupGap = gap * 4
            var y = top + gap * 2
            while (y + gap * 4 < top + h) {
                for (i in 0 until 5) drawLine(color, Offset(left, y + i * gap), Offset(left + w, y + i * gap), line, alpha = a)
                y += gap * 4 + groupGap
            }
        }
    }
}

/**
 * Dibuja los trazos sobre una hoja, conservando proporción. Si se pasa un fondo
 * (`bgImage` rasterizado o `bgPreset` del catálogo) se pinta TENUE debajo.
 */
@Composable
fun InkCanvas(
    strokes: List<StrokeDto>,
    paper: PaperDto?,
    modifier: Modifier = Modifier,
    inkColor: Color = Color(0xFF111111),
    bgImage: ImageBitmap? = null,
    bgPreset: String? = null,
) {
    val model = remember(strokes, paper) { buildModel(strokes, paper) }
    Canvas(modifier) {
        if (model.width <= 0f || model.height <= 0f || size.minDimension <= 0f) return@Canvas
        val s = minOf(size.width / model.width, size.height / model.height)
        val dx = (size.width - model.width * s) / 2f
        val dy = (size.height - model.height * s) / 2f
        val boxW = model.width * s
        val boxH = model.height * s
        // Fondo (imagen o preset) DEBAJO de los trazos, encajado en el recuadro.
        if (bgImage != null) {
            drawImage(
                image = bgImage,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bgImage.width, bgImage.height),
                dstOffset = IntOffset(dx.toInt(), dy.toInt()),
                dstSize = IntSize(boxW.toInt().coerceAtLeast(1), boxH.toInt().coerceAtLeast(1)),
                alpha = 0.6f,
            )
        } else if (bgPreset != null) {
            drawBgPreset(bgPreset, dx, dy, boxW, boxH)
        }
        val style = Stroke(
            width = (model.strokeWidth * s).coerceAtLeast(1f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        for (pts in model.polylines) {
            if (pts.isEmpty()) continue
            if (pts.size == 1) {
                val p = pts[0]
                drawCircle(inkColor, radius = style.width / 2f, center = Offset(p.x * s + dx, p.y * s + dy))
                continue
            }
            val path = Path()
            pts.forEachIndexed { i, p ->
                val x = p.x * s + dx
                val y = p.y * s + dy
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, inkColor, style = style)
        }
    }
}
