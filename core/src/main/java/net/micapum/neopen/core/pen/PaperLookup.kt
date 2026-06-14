package net.micapum.neopen.core.pen

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.micapum.neopen.core.data.PaperDto
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Detección del formato real de una libreta NeoLAB (proporciones de página).
 *
 * Replica `NoteServer.extractMarginInfo` del web_pen_sdk: descarga el `.nproj`
 * (XML) de la libreta desde Firebase Storage (bucket público de NeoLAB), busca el
 * `page_item` de la página y convierte de puntos a 72 dpi a celdas Ncode. Devuelve
 * un PaperDto(source="neolab") o null si la libreta no está catalogada / sin red.
 * Cachea el resultado por libreta (incl. los "no encontrados") para no repetir.
 */
object PaperLookup {
    // Timeouts cortos: si no hay red, no debe frenar la importación offline.
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
    private val cache = ConcurrentHashMap<String, Holder>()
    private class Holder(val paper: PaperDto?)

    // ratio = (8*7/600) inch/celda ÷ (1/72) inch/punto = 6.72 puntos por celda.
    private const val POINTS_PER_CELL = 6.72f

    private const val BUCKET = "neonotes2-d0880.appspot.com"

    suspend fun lookup(section: Int, owner: Int, book: Int, page: Int): PaperDto? {
        val key = "$section.$owner.$book"
        cache[key]?.let { return it.paper }
        val paper = runCatching { fetchAndParse(section, owner, book, page) }.getOrNull()
        cache[key] = Holder(paper)
        return paper
    }

    private suspend fun fetchAndParse(section: Int, owner: Int, book: Int, page: Int): PaperDto? =
        withContext(Dispatchers.IO) {
            val path = "nproj/${section}_${owner}_${book}.nproj"
            val url = "https://firebasestorage.googleapis.com/v0/b/$BUCKET/o/" +
                java.net.URLEncoder.encode(path, "UTF-8") + "?alt=media"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val xml = resp.body?.string() ?: return@withContext null
                parse(xml, page)
            }
        }

    private fun parse(xml: String, page: Int): PaperDto? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var startPageText: Int? = null
        var startPageSeg: Int? = null
        var inStartPage = false
        data class Item(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val crop: String?)
        val items = ArrayList<Item>()
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "start_page" -> inStartPage = true
                    "segment_info" ->
                        startPageSeg = parser.getAttributeValue(null, "ncode_start_page")?.trim()?.toIntOrNull()
                    "page_item" -> items.add(
                        Item(
                            parser.getAttributeValue(null, "x1")?.toFloatOrNull() ?: 0f,
                            parser.getAttributeValue(null, "y1")?.toFloatOrNull() ?: 0f,
                            parser.getAttributeValue(null, "x2")?.toFloatOrNull() ?: 0f,
                            parser.getAttributeValue(null, "y2")?.toFloatOrNull() ?: 0f,
                            parser.getAttributeValue(null, "crop_margin"),
                        ),
                    )
                }
                XmlPullParser.TEXT -> if (inStartPage) startPageText = parser.text?.trim()?.toIntOrNull() ?: startPageText
                XmlPullParser.END_TAG -> if (parser.name == "start_page") inStartPage = false
            }
            ev = parser.next()
        }
        val startPage = startPageSeg ?: startPageText ?: 1
        val idx = page - startPage
        val item = items.getOrNull(idx) ?: return null
        val m = item.crop?.split(",")?.map { it.trim().toFloatOrNull() ?: 0f } ?: listOf(0f, 0f, 0f, 0f)
        val l = m.getOrElse(0) { 0f }
        val t = m.getOrElse(1) { 0f }
        val r = m.getOrElse(2) { 0f }
        val b = m.getOrElse(3) { 0f }
        fun toCell(v: Float) = v / POINTS_PER_CELL
        val xMin = toCell(item.x1) + toCell(l)
        val yMin = toCell(item.y1) + toCell(t)
        val xMax = toCell(item.x2) - toCell(r)
        val yMax = toCell(item.y2) - toCell(b)
        if (xMax <= xMin || yMax <= yMin) return null
        return PaperDto(xMin, yMin, xMax - xMin, yMax - yMin, "neolab")
    }
}
