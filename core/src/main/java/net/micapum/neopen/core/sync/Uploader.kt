package net.micapum.neopen.core.sync

import kotlinx.coroutines.flow.first
import net.micapum.neopen.core.data.ApiFactory
import net.micapum.neopen.core.data.CreateCaptureInput
import net.micapum.neopen.core.data.DotDto
import net.micapum.neopen.core.data.RegisterPenInput
import net.micapum.neopen.core.data.SettingsStore
import net.micapum.neopen.core.data.SobpDto
import net.micapum.neopen.core.data.StrokeDto
import net.micapum.neopen.core.pen.StrokePage

/** Sube páginas y registra el boli en el servidor neopen (el servidor archiva por SOBP). */
class Uploader(private val settings: SettingsStore) {

    suspend fun upload(page: StrokePage): Boolean {
        val s = settings.flow.first()
        if (!s.configured) return false
        val api = ApiFactory.create(s.serverUrl)
        val input = CreateCaptureInput(
            sobp = SobpDto(page.sobp.section, page.sobp.owner, page.sobp.book, page.sobp.page),
            source = page.source,
            penId = page.penId,
            capturedAt = page.capturedAt,
            name = "Nota ${page.sobp.section}.${page.sobp.owner}.${page.sobp.book}.${page.sobp.page}",
            strokes = page.strokes.map { st ->
                StrokeDto(st.startTime, st.endTime, st.dots.map { DotDto(it.x, it.y, it.f, it.t) }, st.color, st.width, st.penId ?: page.penId)
            },
        )
        return runCatching { api.createCapture(s.password, input) }.isSuccess
    }

    suspend fun registerPen(mac: String, name: String?, firmware: String?) {
        val s = settings.flow.first()
        if (!s.configured) return
        runCatching {
            ApiFactory.create(s.serverUrl).registerPen(s.password, RegisterPenInput(mac, name, firmware))
        }
    }
}
