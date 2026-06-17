package net.micapum.neopen.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.micapum.neopen.app.graph
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.pen.KnownPen
import net.micapum.neopen.core.pen.StrokePresets

/**
 * Procesa los toques en los botones de la notificación de control: cicla color/grueso/boli o
 * alterna el segundo plano, persiste el cambio en los ajustes y re-postea la notificación.
 */
class NotificationControlReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appCtx = context.applicationContext
        val graph = appCtx.graph
        val pending = goAsync()
        scope.launch {
            try {
                val cur = graph.settings.flow.first()
                val selected = graph.notifSelectedPen ?: graph.pen.state.value.mac
                    ?: cur.knownPens.firstOrNull()?.mac
                val next: AppSettings = when (action) {
                    ControlNotification.ACTION_CYCLE_COLOR -> {
                        val s = cur.styleFor(selected)
                        applyStyle(cur, selected, s.copy(color = StrokePresets.nextColor(s.color)))
                    }
                    ControlNotification.ACTION_CYCLE_WIDTH -> {
                        val s = cur.styleFor(selected)
                        applyStyle(cur, selected, s.copy(width = StrokePresets.nextWidth(s.width)))
                    }
                    ControlNotification.ACTION_CYCLE_PEN -> {
                        cyclePen(graph, cur, selected)
                        cur // no cambia ajustes; solo el boli seleccionado (estado del grafo)
                    }
                    ControlNotification.ACTION_TOGGLE_BG -> {
                        val on = !cur.backgroundEnabled
                        graph.pen.autoReconnect = on
                        if (on && !graph.pen.state.value.connected) graph.pen.scanAndConnectAuto()
                        cur.copy(backgroundEnabled = on)
                    }
                    else -> cur
                }
                if (next !== cur) graph.settings.update(next)
                // Re-postea con los valores nuevos (no esperamos a que el flow propague).
                NotificationManagerCompat.from(appCtx)
                    .notify(ControlNotification.NOTIF_ID, ControlNotification.build(appCtx, next))
            } finally {
                pending.finish()
            }
        }
    }

    /** Persiste el estilo en el boli `mac` (lo añade si no estaba); si `mac` es null → por defecto. */
    private fun applyStyle(cur: AppSettings, mac: String?, style: net.micapum.neopen.core.pen.StrokeStyle): AppSettings {
        if (mac == null) return cur.copy(defaultStyle = style)
        val pens = if (cur.knownPens.any { it.mac == mac })
            cur.knownPens.map { if (it.mac == mac) it.copy(style = style) else it }
        else cur.knownPens + KnownPen(mac, mac, style)
        return cur.copy(knownPens = pens)
    }

    /** Avanza el boli seleccionado por la lista de emparejados (cíclico). */
    private fun cyclePen(graph: net.micapum.neopen.app.Graph, cur: AppSettings, selected: String?) {
        val pens = cur.knownPens
        if (pens.isEmpty()) return
        val i = pens.indexOfFirst { it.mac == selected }
        graph.notifSelectedPen = pens[(if (i < 0) 0 else i + 1) % pens.size].mac
    }
}
