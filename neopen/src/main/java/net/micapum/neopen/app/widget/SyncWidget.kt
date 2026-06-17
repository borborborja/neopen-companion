package net.micapum.neopen.app.widget

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import net.micapum.neopen.app.R
import net.micapum.neopen.app.graph
import net.micapum.neopen.app.sync.PenService
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.SyncStatus
import net.micapum.neopen.core.data.SyncStatusStore
import net.micapum.neopen.core.pen.PenState
import net.micapum.neopen.core.pen.PenStatus

private val Bg = ColorProvider(R.color.widget_bg)
private val OnBg = ColorProvider(R.color.widget_on)
private val Muted = ColorProvider(R.color.widget_muted)
private val Accent = ColorProvider(R.color.widget_accent)

private val SMALL = DpSize(140.dp, 60.dp)
private val MEDIUM = DpSize(180.dp, 120.dp)
private val LARGE = DpSize(290.dp, 120.dp)

class SyncWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = SyncStatusStore(context)
        val graph = context.applicationContext.graph
        provideContent {
            val status by store.flow.collectAsState(SyncStatus())
            val pen by graph.pen.state.collectAsState(PenState())
            val settings by graph.settings.flow.collectAsState(AppSettings())
            WidgetUi(context, status, pen, settings)
        }
    }

    companion object {
        suspend fun refresh(context: Context) = SyncWidget().updateAll(context)
    }
}

class SyncWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SyncWidget()
}

/** Acción al pulsar: encola la sincronización forzada (offline import + push/pull). */
class ForceSyncAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ForceSyncWorker.NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ForceSyncWorker>().build(),
        )
        SyncWidget().update(context, glanceId)
    }
}

@Composable
private fun WidgetUi(context: Context, status: SyncStatus, pen: PenState, settings: AppSettings) {
    val size = LocalSize.current
    val tall = size.height >= MEDIUM.height
    val wide = size.width >= LARGE.width

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Bg)
            .cornerRadius(20.dp)
            .padding(14.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(context.getString(R.string.wgt_pen_title), style = TextStyle(color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.defaultWeight())
            Text(penStatusIcon(pen), style = TextStyle(color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        }
        if (tall) {
            Spacer(GlanceModifier.height(10.dp))
            SyncLine(context.getString(R.string.wgt_pen_state), penStatusText(context, pen))
            Spacer(GlanceModifier.height(6.dp))
            SyncLine(context.getString(R.string.wgt_pen_battery), batteryText(context, pen))
            if (wide) {
                Spacer(GlanceModifier.height(10.dp))
                SyncLine(context.getString(R.string.wgt_sync_pen), relative(context, status.lastPenSync))
            }
            Spacer(GlanceModifier.height(10.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ActionPill(context.getString(R.string.wgt_pen_sync_now), actionRunCallback<ForceSyncAction>())
                Spacer(GlanceModifier.width(8.dp))
                ActionPill(
                    if (settings.backgroundEnabled) context.getString(R.string.wgt_pen_permanent_on) else context.getString(R.string.wgt_pen_permanent_off),
                    actionRunCallback<TogglePermanentConnectionAction>(),
                )
            }
        } else {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                "${penStatusText(context, pen)} · ${batteryText(context, pen)}",
                style = TextStyle(color = OnBg, fontSize = 13.sp),
            )
            Text(context.getString(R.string.wgt_pen_tap_compact), modifier = GlanceModifier.clickable(actionRunCallback<ForceSyncAction>()), style = TextStyle(color = Muted, fontSize = 11.sp))
        }
    }
}

class TogglePermanentConnectionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appCtx = context.applicationContext
        val g = appCtx.graph
        val cur = g.currentSettings
        val on = !cur.backgroundEnabled
        val next = cur.copy(backgroundEnabled = on)
        g.settings.update(next)
        g.pen.autoReconnect = on
        if (on) {
            PenService.start(appCtx)
            if (!g.pen.state.value.connected) g.pen.scanAndConnectAuto()
        } else if (!next.controlNotification) {
            PenService.stop(appCtx)
        }
        SyncWidget().update(context, glanceId)
    }
}

@Composable
private fun SyncLine(label: String, value: String) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyle(color = Muted, fontSize = 13.sp))
        Spacer(GlanceModifier.defaultWeight())
        Text(value, style = TextStyle(color = OnBg, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}

private fun relative(context: Context, at: Long): String =
    if (at <= 0L) context.getString(R.string.wgt_sync_never)
    else DateUtils.getRelativeTimeSpanString(
        at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

@Composable
private fun ActionPill(label: String, action: androidx.glance.action.Action) {
    Text(
        label,
        modifier = GlanceModifier
            .cornerRadius(14.dp)
            .background(Accent)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clickable(action),
        style = TextStyle(color = Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold),
    )
}

private fun penStatusText(context: Context, pen: PenState): String = when (pen.status) {
    PenStatus.AUTHORIZED -> pen.name ?: context.getString(R.string.wgt_pen_connected)
    PenStatus.SCANNING, PenStatus.CONNECTING -> context.getString(R.string.wgt_pen_connecting)
    PenStatus.LOCKED -> context.getString(R.string.wgt_pen_locked)
    else -> context.getString(R.string.wgt_pen_disconnected)
}

private fun penStatusIcon(pen: PenState): String = when (pen.status) {
    PenStatus.AUTHORIZED -> "●"
    PenStatus.SCANNING, PenStatus.CONNECTING -> "↻"
    PenStatus.LOCKED -> "!"
    else -> "○"
}

private fun batteryText(context: Context, pen: PenState): String {
    val battery = pen.settings.battery ?: return context.getString(R.string.wgt_pen_battery_unknown)
    return if (pen.settings.charging) context.getString(R.string.pen_battery_charging, battery)
    else context.getString(R.string.pen_battery, battery)
}
