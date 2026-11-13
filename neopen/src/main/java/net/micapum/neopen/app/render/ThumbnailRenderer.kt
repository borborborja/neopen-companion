package net.micapum.neopen.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Trace
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.micapum.neopen.app.data.Capture

/** La clave usa contenido, no solo rev: los trazos locales aún no tienen revisión remota. */
data class ThumbnailKey(
    val namespace: String,
    val captureId: String,
    val fingerprint: String,
    val width: Int,
    val height: Int,
    val pressure: Boolean,
    val contrast: Float,
    val background: String,
    val instanceId: String?,
    val page: Int,
)

/** Misma geometría y mínimo de un píxel de InkCanvas, preparado una vez fuera de Main. */
internal suspend fun rasterizeThumbnail(
    capture: Capture,
    width: Int,
    height: Int,
    pressure: Boolean,
    contrast: Float,
    background: Bitmap? = null,
    preset: String? = null,
): Bitmap {
    Trace.beginSection("neopen.thumbnail")
    try {
        val model = buildModel(capture.strokes, capture.paper, pressureWidthEnabled = pressure, pressureWidthContrast = contrast)
        currentCoroutineContext().ensureActive()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val scale = minOf(width / model.width, height / model.height)
            val dx = (width - model.width * scale) / 2f
            val dy = (height - model.height * scale) / 2f
            val box = RectF(dx, dy, dx + model.width * scale, dy + model.height * scale)
            if (background != null) {
                canvas.drawBitmap(background, Rect(0, 0, background.width, background.height), box,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 153 })
            } else if (preset != null) drawThumbnailPreset(canvas, preset, box)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            for (poly in model.polylines) {
                currentCoroutineContext().ensureActive()
                if (poly.points.isEmpty()) continue
                paint.color = poly.color
                val widths = poly.pointWidths
                if (poly.points.size == 1) {
                    val point = poly.points[0]
                    paint.style = Paint.Style.FILL
                    val radius = (((widths?.getOrNull(0) ?: poly.width) * scale).coerceAtLeast(1f)) / 2f
                    canvas.drawCircle(point.x * scale + dx, point.y * scale + dy, radius, paint)
                    paint.style = Paint.Style.STROKE
                } else if (widths != null && widths.size == poly.points.size) {
                    for (i in 0 until poly.points.lastIndex) {
                        if (i % 1024 == 0) currentCoroutineContext().ensureActive()
                        val from = poly.points[i]; val to = poly.points[i + 1]
                        paint.strokeWidth = (((widths[i] + widths[i + 1]) / 2f) * scale).coerceAtLeast(1f)
                        canvas.drawLine(from.x * scale + dx, from.y * scale + dy, to.x * scale + dx, to.y * scale + dy, paint)
                    }
                } else {
                    paint.strokeWidth = (poly.width * scale).coerceAtLeast(1f)
                    val path = Path()
                    poly.points.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(point.x * scale + dx, point.y * scale + dy)
                        else path.lineTo(point.x * scale + dx, point.y * scale + dy)
                    }
                    canvas.drawPath(path, paint)
                }
            }
            currentCoroutineContext().ensureActive()
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    } finally { Trace.endSection() }
}

private fun drawThumbnailPreset(canvas: Canvas, preset: String, box: RectF) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 116, 139); alpha = 115; strokeWidth = (box.height() / 220f).coerceIn(.8f, 2f) }
    val step = box.height() / 26f
    if (preset == BgPresets.MUSIC) {
        val gap = box.height() / 70f
        var y = box.top + gap * 2
        while (y + gap * 4 < box.bottom) {
            for (i in 0..4) canvas.drawLine(box.left, y + i * gap, box.right, y + i * gap, paint)
            y += gap * 8
        }
        return
    }
    var y = box.top + step
    while (y < box.bottom) {
        if (preset == BgPresets.DOTS) {
            var x = box.left + step
            while (x < box.right) { canvas.drawCircle(x, y, paint.strokeWidth * .9f, paint); x += step }
        } else if (preset == BgPresets.LINED || preset == BgPresets.GRID) {
            canvas.drawLine(box.left, y, box.right, y, paint)
        }
        y += step
    }
    if (preset == BgPresets.GRID) {
        var x = box.left + step
        while (x < box.right) { canvas.drawLine(x, box.top, x, box.bottom, paint); x += step }
    }
}
