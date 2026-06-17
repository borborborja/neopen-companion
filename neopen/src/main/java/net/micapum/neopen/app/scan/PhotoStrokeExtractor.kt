package net.micapum.neopen.app.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.Context
import net.micapum.neopen.core.data.DotDto
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.StrokeDto
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class PhotoPoint(val x: Float, val y: Float)

data class PhotoStroke(val points: List<PhotoPoint>)

data class PhotoStrokeExtraction(
    val width: Int,
    val height: Int,
    val strokes: List<PhotoStroke>,
    val candidates: List<PhotoPoint>,
)

data class PaperPoint(val x: Float, val y: Float)

data class CalibrationPair(val image: PhotoPoint, val paper: PaperPoint)

object PhotoStrokeExtractor {
    private const val MAX_SIDE = 1400
    private const val CELL_STEP = 18
    private const val MIN_COMPONENT_PIXELS = 12
    private const val MAX_COMPONENT_RATIO = 0.22f

    fun extract(context: Context, uri: Uri): PhotoStrokeExtraction {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = sampleFor(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return PhotoStrokeExtraction(1, 1, emptyList(), emptyList())
        return try {
            extract(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun extract(bitmap: Bitmap): PhotoStrokeExtraction {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val gray = IntArray(w * h)
        var total = 0L
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val v = (r * 30 + g * 59 + b * 11) / 100
                gray[y * w + x] = v
                total += v
            }
        }
        val mean = (total / (w * h)).toInt()
        val threshold = (mean - 38).coerceIn(70, 188)
        val ink = BooleanArray(w * h)
        for (i in gray.indices) ink[i] = gray[i] < threshold

        val seen = BooleanArray(w * h)
        val strokes = ArrayList<PhotoStroke>()
        val candidates = ArrayList<PhotoPoint>()
        val qx = IntArray(w * h)
        val qy = IntArray(w * h)
        val maxPixels = (w * h * MAX_COMPONENT_RATIO).toInt()
        for (sy in 1 until h - 1) {
            for (sx in 1 until w - 1) {
                val start = sy * w + sx
                if (!ink[start] || seen[start]) continue
                var head = 0
                var tail = 0
                var count = 0
                var minX = sx
                var maxX = sx
                var minY = sy
                var maxY = sy
                qx[tail] = sx
                qy[tail++] = sy
                seen[start] = true
                while (head < tail) {
                    val x = qx[head]
                    val y = qy[head++]
                    count++
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx <= 0 || ny <= 0 || nx >= w - 1 || ny >= h - 1) continue
                        val ni = ny * w + nx
                        if (ink[ni] && !seen[ni]) {
                            seen[ni] = true
                            qx[tail] = nx
                            qy[tail++] = ny
                        }
                    }
                }
                if (count < MIN_COMPONENT_PIXELS || count > maxPixels) continue
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                if (bw < 3 && bh < 3) continue
                val points = traceComponent(ink, w, h, minX, minY, maxX, maxY)
                if (points.size < 2) continue
                strokes += PhotoStroke(points)
                candidates += points.first()
                candidates += points.last()
            }
        }
        return PhotoStrokeExtraction(w, h, strokes.sortedBy { it.points.first().y }, candidates.take(16))
    }

    fun transform(
        extraction: PhotoStrokeExtraction,
        pairs: List<CalibrationPair>,
        paper: PaperDto?,
        color: Int?,
        width: Float?,
    ): List<StrokeDto>? {
        val t = affineFrom(pairs) ?: return null
        val now = System.currentTimeMillis()
        val p = paper
        val out = extraction.strokes.mapIndexedNotNull { i, stroke ->
            val dots = stroke.points.map { t.apply(it) }
                .filter { pp ->
                    p == null || (
                        pp.x >= p.x0 - 2f && pp.y >= p.y0 - 2f &&
                            pp.x <= p.x0 + p.width + 2f && pp.y <= p.y0 + p.height + 2f
                        )
                }
                .dedupeClose()
            if (dots.size < 2) return@mapIndexedNotNull null
            val start = now + i * 10_000L
            StrokeDto(
                startTime = start,
                endTime = start + dots.size * 16L,
                dots = dots.mapIndexed { j, pp -> DotDto(pp.x, pp.y, 0.5f, start + j * 16L) },
                color = color,
                width = width,
            )
        }
        return out.ifEmpty { null }
    }

    private fun List<PaperPoint>.dedupeClose(): List<PaperPoint> {
        val out = ArrayList<PaperPoint>()
        for (p in this) {
            val last = out.lastOrNull()
            if (last == null || hypot((p.x - last.x).toDouble(), (p.y - last.y).toDouble()) > 0.25) out += p
        }
        return out
    }

    private fun sampleFor(w: Int, h: Int): Int {
        var sample = 1
        var side = max(w, h)
        while (side / sample > MAX_SIDE) sample *= 2
        return sample
    }

    private fun traceComponent(ink: BooleanArray, w: Int, h: Int, minX: Int, minY: Int, maxX: Int, maxY: Int): List<PhotoPoint> {
        val points = ArrayList<PhotoPoint>()
        val step = max(4, min(CELL_STEP, max(maxX - minX, maxY - minY) / 5))
        if ((maxX - minX) >= (maxY - minY)) {
            var x = minX
            while (x <= maxX) {
                var sumY = 0
                var count = 0
                for (yy in minY..maxY) {
                    for (xx in x until min(x + step, maxX + 1)) {
                        if (xx in 0 until w && yy in 0 until h && ink[yy * w + xx]) {
                            sumY += yy
                            count++
                        }
                    }
                }
                if (count > 0) points += PhotoPoint((x + step / 2f).coerceAtMost(maxX.toFloat()), sumY.toFloat() / count)
                x += step
            }
        } else {
            var y = minY
            while (y <= maxY) {
                var sumX = 0
                var count = 0
                for (yy in y until min(y + step, maxY + 1)) {
                    for (xx in minX..maxX) {
                        if (xx in 0 until w && yy in 0 until h && ink[yy * w + xx]) {
                            sumX += xx
                            count++
                        }
                    }
                }
                if (count > 0) points += PhotoPoint(sumX.toFloat() / count, (y + step / 2f).coerceAtMost(maxY.toFloat()))
                y += step
            }
        }
        return points
    }
}

private data class Affine(
    val ax: Float, val bx: Float, val cx: Float,
    val ay: Float, val by: Float, val cy: Float,
) {
    fun apply(p: PhotoPoint): PaperPoint =
        PaperPoint(ax * p.x + bx * p.y + cx, ay * p.x + by * p.y + cy)
}

private fun affineFrom(pairs: List<CalibrationPair>): Affine? {
    if (pairs.size < 3) return null
    val p1 = pairs[0]
    val p2 = pairs[1]
    val p3 = pairs[2]
    val det = p1.image.x * (p2.image.y - p3.image.y) +
        p2.image.x * (p3.image.y - p1.image.y) +
        p3.image.x * (p1.image.y - p2.image.y)
    if (abs(det) < 0.001f) return null
    fun coeff(v1: Float, v2: Float, v3: Float): Triple<Float, Float, Float> {
        val a = (v1 * (p2.image.y - p3.image.y) + v2 * (p3.image.y - p1.image.y) + v3 * (p1.image.y - p2.image.y)) / det
        val b = (v1 * (p3.image.x - p2.image.x) + v2 * (p1.image.x - p3.image.x) + v3 * (p2.image.x - p1.image.x)) / det
        val c = (v1 * (p2.image.x * p3.image.y - p3.image.x * p2.image.y) +
            v2 * (p3.image.x * p1.image.y - p1.image.x * p3.image.y) +
            v3 * (p1.image.x * p2.image.y - p2.image.x * p1.image.y)) / det
        return Triple(a, b, c)
    }
    val x = coeff(p1.paper.x, p2.paper.x, p3.paper.x)
    val y = coeff(p1.paper.y, p2.paper.y, p3.paper.y)
    return Affine(x.first, x.second, x.third, y.first, y.second, y.third)
}
