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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import net.micapum.neopen.app.R
import net.micapum.neopen.core.data.SyncStatus
import net.micapum.neopen.core.data.SyncStatusStore

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
        provideContent {
            val status by store.flow.collectAsState(SyncStatus())
            WidgetUi(status)
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
private fun WidgetUi(status: SyncStatus) {
    val size = LocalSize.current
    val tall = size.height >= MEDIUM.height
    val wide = size.width >= LARGE.width

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Bg)
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(actionRunCallback<ForceSyncAction>()),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("neopen", style = TextStyle(color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.defaultWeight())
            Text("↻", style = TextStyle(color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        }
        if (tall) {
            Spacer(GlanceModifier.height(10.dp))
            SyncLine("Boli", status.lastPenSync)
            Spacer(GlanceModifier.height(6.dp))
            SyncLine("Servidor", status.lastServerSync)
            if (wide) {
                Spacer(GlanceModifier.height(10.dp))
                Text("Toca para sincronizar ahora", style = TextStyle(color = Muted, fontSize = 12.sp))
            }
        } else {
            Spacer(GlanceModifier.height(6.dp))
            Text("Servidor: ${relative(status.lastServerSync)}", style = TextStyle(color = OnBg, fontSize = 13.sp))
            Text("Toca para sync", style = TextStyle(color = Muted, fontSize = 11.sp))
        }
    }
}

@Composable
private fun SyncLine(label: String, at: Long) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyle(color = Muted, fontSize = 13.sp))
        Spacer(GlanceModifier.defaultWeight())
        Text(relative(at), style = TextStyle(color = OnBg, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}

private fun relative(at: Long): String =
    if (at <= 0L) "nunca"
    else DateUtils.getRelativeTimeSpanString(
        at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
