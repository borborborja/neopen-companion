package net.micapum.neopen.app.render

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** Rasteriza una página a PNG (fondo blanco, tinta #111) para enviar al OCR. */
fun rasterizePng(strokes: List<StrokeDto>, paper: PaperDto?): ByteArray {
    val m = buildModel(strokes, paper)
    val w = m.width.toInt().coerceIn(1, 4000)
    val h = m.height.toInt().coerceIn(1, 4000)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
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
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    return out.toByteArray()
}

/** Dibuja los trazos en un Canvas, encajando el modelo y conservando proporción. */
@Composable
fun InkCanvas(
    strokes: List<StrokeDto>,
    paper: PaperDto?,
    modifier: Modifier = Modifier,
    inkColor: Color = Color(0xFF111111),
) {
    val model = remember(strokes, paper) { buildModel(strokes, paper) }
    Canvas(modifier) {
        if (model.width <= 0f || model.height <= 0f || size.minDimension <= 0f) return@Canvas
        val s = minOf(size.width / model.width, size.height / model.height)
        val dx = (size.width - model.width * s) / 2f
        val dy = (size.height - model.height * s) / 2f
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
