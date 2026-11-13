package net.micapum.neopen.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import net.micapum.neopen.app.MainActivity
import net.micapum.neopen.app.R
import net.micapum.neopen.app.data.CaptureSummary
import net.micapum.neopen.app.graph

private val RecentSmall = DpSize(180.dp, 110.dp)
private val RecentLarge = DpSize(290.dp, 180.dp)

private val Bg = ColorProvider(R.color.widget_bg)
private val OnBg = ColorProvider(R.color.widget_on)
private val Muted = ColorProvider(R.color.widget_muted)
private val Accent = ColorProvider(R.color.widget_accent)

private val shareKindKey = ActionParameters.Key<String>(EXTRA_SHARE_KIND)
private val shareIdKey = ActionParameters.Key<String>(EXTRA_SHARE_ID)

class RecentEditsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(RecentSmall, RecentLarge))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = context.graph.repo.recentSummaries(8)
        provideContent {
            val recent by flow.collectAsState(emptyList())
            RecentWidgetUi(context, recent)
        }
    }

    companion object {
        suspend fun refresh(context: Context) = RecentEditsWidget().updateAll(context)
    }
}

class RecentEditsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentEditsWidget()
}

@Composable
private fun RecentWidgetUi(context: Context, recent: List<CaptureSummary>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Bg)
            .cornerRadius(20.dp)
            .padding(14.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                context.getString(R.string.wgt_recent_title),
                style = TextStyle(color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text("›", style = TextStyle(color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.height(8.dp))
        if (recent.isEmpty()) {
            Text(context.getString(R.string.wgt_recent_empty), style = TextStyle(color = Muted, fontSize = 13.sp))
        } else {
            LazyColumn {
                items(recent, itemId = { it.id.hashCode().toLong() }) { cap ->
                    RecentRow(cap)
                }
            }
        }
    }
}

@Composable
private fun RecentRow(cap: CaptureSummary) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.size(10.dp).cornerRadius(5.dp).background(ColorProvider(Color(0xFF8B5CF6)))) {}
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
            Text(cap.name, style = TextStyle(color = OnBg, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            Text(cap.sobpLabel, style = TextStyle(color = Muted, fontSize = 11.sp))
        }
        Spacer(GlanceModifier.width(8.dp))
        Text(
            "⇪",
            modifier = GlanceModifier
                .cornerRadius(12.dp)
                .background(Accent)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clickable(
                    actionStartActivity<ShareExportActivity>(
                        actionParametersOf(shareKindKey to SHARE_KIND_CAPTURE, shareIdKey to cap.id),
                    ),
                ),
            style = TextStyle(color = Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold),
        )
    }
}
