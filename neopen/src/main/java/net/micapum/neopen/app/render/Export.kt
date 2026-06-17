package net.micapum.neopen.app.render

import android.graphics.pdf.PdfDocument
import androidx.compose.ui.geometry.Offset
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.StrokeDto
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Formatos de exportación de una página. */
enum class ExportFormat(val ext: String, val mime: String) {
    PDF("pdf", "application/pdf"),
    PNG("png", "image/png"),
    SVG("svg", "image/svg+xml"),
}

// --- SVG (espejo exacto de modelToSvg de packages/shared) -------------------

fun renderSvg(
    strokes: List<StrokeDto>,
    paper: PaperDto?,
    pressureWidthEnabled: Boolean = false,
    pressureWidthContrast: Float = 2f,
): String {
    val m = buildModel(strokes, paper, pressureWidthEnabled = pressureWidthEnabled, pressureWidthContrast = pressureWidthContrast)
    // Estilo por <path> (color + grueso del trazo) en vez de global en el <g>.
    val paths = m.polylines
        .filter { it.points.isNotEmpty() }
        .joinToString("") { poly -> svgPaths(poly) }
    val w = roundCoord(m.width)
    val h = roundCoord(m.height)
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" " +
        "viewBox=\"0 0 $w $h\">" +
        "<g fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\">$paths</g></svg>"
}

private fun svgPaths(poly: StyledPolyline): String {
    val pts = poly.points
    val widths = poly.pointWidths
    if (widths == null || widths.size != pts.size || pts.size <= 1) {
        return "<path d=\"${toPathData(pts)}\" stroke=\"${svgColor(poly.color)}\" " +
            "stroke-width=\"${poly.width}\" />"
    }
    return (0 until pts.lastIndex).joinToString("") { i ->
        val w = ((widths[i] + widths[i + 1]) / 2f).coerceAtLeast(0.5f)
        "<path d=\"M${roundCoord(pts[i].x)} ${roundCoord(pts[i].y)} " +
            "L${roundCoord(pts[i + 1].x)} ${roundCoord(pts[i + 1].y)}\" " +
            "stroke=\"${svgColor(poly.color)}\" stroke-width=\"${roundCoord(w)}\" />"
    }
}

/** ARGB Int → "#RRGGBB" para SVG (se ignora el alfa). */
private fun svgColor(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

private fun toPathData(pts: List<Offset>): String {
    if (pts.size == 1) {
        val p = pts[0]
        return "M${roundCoord(p.x)} ${roundCoord(p.y)} l0.01 0"
    }
    return pts.joinToString(" ") { p ->
        // El primer punto usa M; el resto L (se construye con índice abajo).
        "L${roundCoord(p.x)} ${roundCoord(p.y)}"
    }.replaceFirst("L", "M")
}

/** Redondea a 2 decimales y elimina el `.0` sobrante (como roundCoord/JSON de la web). */
private fun roundCoord(n: Float): String {
    val r = (n * 100f).roundToInt() / 100f
    return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
}

// --- PDF (android.graphics.pdf.PdfDocument) ---------------------------------

fun renderPagePdf(
    strokes: List<StrokeDto>,
    paper: PaperDto?,
    pressureWidthEnabled: Boolean = false,
    pressureWidthContrast: Float = 2f,
): ByteArray {
    val doc = PdfDocument()
    addPdfPage(doc, buildModel(strokes, paper, pressureWidthEnabled = pressureWidthEnabled, pressureWidthContrast = pressureWidthContrast), 1)
    return finishPdf(doc)
}

/** PDF multipágina: una página por captura (mismo bucle que notebookExport de la web). */
fun renderNotebookPdf(
    pages: List<Capture>,
    pressureWidthEnabled: Boolean = false,
    pressureWidthContrast: Float = 2f,
): ByteArray {
    val doc = PdfDocument()
    val models = if (pages.isEmpty()) {
        listOf(buildModel(emptyList(), null, pressureWidthEnabled = pressureWidthEnabled, pressureWidthContrast = pressureWidthContrast))
    } else {
        pages.map { buildModel(it.strokes, it.paper, pressureWidthEnabled = pressureWidthEnabled, pressureWidthContrast = pressureWidthContrast) }
    }
    models.forEachIndexed { i, m -> addPdfPage(doc, m, i + 1) }
    return finishPdf(doc)
}

private fun addPdfPage(doc: PdfDocument, m: RenderModel, pageNumber: Int) {
    val w = m.width.roundToInt().coerceIn(1, 5000)
    val h = m.height.roundToInt().coerceIn(1, 5000)
    val info = PdfDocument.PageInfo.Builder(w, h, pageNumber).create()
    val page = doc.startPage(info)
    page.canvas.drawColor(android.graphics.Color.WHITE)
    drawModelTo(page.canvas, m)
    doc.finishPage(page)
}

private fun finishPdf(doc: PdfDocument): ByteArray {
    val out = ByteArrayOutputStream()
    doc.writeTo(out)
    doc.close()
    return out.toByteArray()
}
