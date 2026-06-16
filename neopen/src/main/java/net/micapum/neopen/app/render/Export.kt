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

fun renderSvg(strokes: List<StrokeDto>, paper: PaperDto?): String {
    val m = buildModel(strokes, paper)
    // Estilo por <path> (color + grueso del trazo) en vez de global en el <g>.
    val paths = m.polylines
        .filter { it.points.isNotEmpty() }
        .joinToString("") { poly ->
            "<path d=\"${toPathData(poly.points)}\" stroke=\"${svgColor(poly.color)}\" " +
                "stroke-width=\"${poly.width}\" />"
        }
    val w = roundCoord(m.width)
    val h = roundCoord(m.height)
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" " +
        "viewBox=\"0 0 $w $h\">" +
        "<g fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\">$paths</g></svg>"
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

fun renderPagePdf(strokes: List<StrokeDto>, paper: PaperDto?): ByteArray {
    val doc = PdfDocument()
    addPdfPage(doc, buildModel(strokes, paper), 1)
    return finishPdf(doc)
}

/** PDF multipágina: una página por captura (mismo bucle que notebookExport de la web). */
fun renderNotebookPdf(pages: List<Capture>): ByteArray {
    val doc = PdfDocument()
    val models = if (pages.isEmpty()) {
        listOf(buildModel(emptyList(), null))
    } else {
        pages.map { buildModel(it.strokes, it.paper) }
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
