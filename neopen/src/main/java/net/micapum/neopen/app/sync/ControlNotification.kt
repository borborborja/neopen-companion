package net.micapum.neopen.app.sync

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import net.micapum.neopen.app.NeopenFullApp
import net.micapum.neopen.app.R
import net.micapum.neopen.app.graph
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.pen.StrokePresets

/**
 * Construye la notificación de control persistente: muestra el boli seleccionado, su color/grueso
 * y el estado de segundo plano, con 4 botones de acción que CICLAN entre presets (color, grueso,
 * boli) o alternan el segundo plano. Reutilizada por [PenService] y [NotificationControlReceiver].
 */
object ControlNotification {
    const val NOTIF_ID = 42 // mismo que el del PenService (es la misma notificación foreground)

    const val ACTION_CYCLE_COLOR = "net.micapum.neopen.action.CYCLE_COLOR"
    const val ACTION_CYCLE_WIDTH = "net.micapum.neopen.action.CYCLE_WIDTH"
    const val ACTION_CYCLE_PEN = "net.micapum.neopen.action.CYCLE_PEN"
    const val ACTION_TOGGLE_BG = "net.micapum.neopen.action.TOGGLE_BG"

    fun build(context: Context, settings: AppSettings): Notification {
        val graph = context.graph
        val selected = graph.notifSelectedPen ?: graph.pen.state.value.mac ?: settings.knownPens.firstOrNull()?.mac
        val style = settings.styleFor(selected)
        val alias = settings.knownPens.firstOrNull { it.mac == selected }?.alias
            ?: graph.pen.state.value.name ?: context.getString(R.string.pen_default_name)
        val colorName = StrokePresets.colorName(style.color)
        val widthLabel = StrokePresets.widthLabel(style.width)
        val bgOn = settings.backgroundEnabled

        return NotificationCompat.Builder(context, NeopenFullApp.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.ctrlnotif_title, alias))
            .setContentText(context.getString(R.string.ctrlnotif_text, colorName, widthLabel))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.ctrlnotif_text, colorName, widthLabel) + "\n" +
                        context.getString(if (bgOn) R.string.ctrlnotif_bg_on else R.string.ctrlnotif_bg_off),
                ),
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_edit,
                context.getString(R.string.ctrlnotif_action_color, colorName),
                pending(context, ACTION_CYCLE_COLOR, 1),
            )
            .addAction(
                android.R.drawable.ic_menu_edit,
                context.getString(R.string.ctrlnotif_action_width, widthLabel),
                pending(context, ACTION_CYCLE_WIDTH, 2),
            )
            .addAction(
                android.R.drawable.ic_menu_rotate,
                context.getString(R.string.ctrlnotif_action_pen, alias),
                pending(context, ACTION_CYCLE_PEN, 3),
            )
            .addAction(
                android.R.drawable.ic_popup_sync,
                context.getString(if (bgOn) R.string.ctrlnotif_action_bg_on else R.string.ctrlnotif_action_bg_off),
                pending(context, ACTION_TOGGLE_BG, 4),
            )
            .build()
    }

    private fun pending(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationControlReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
