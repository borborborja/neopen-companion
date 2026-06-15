package net.micapum.neopen.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.action.actionStartActivity
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
import net.micapum.neopen.app.data.Notebook
import net.micapum.neopen.app.graph

/** Claves de deep-link que MainActivity lee de los extras del intent. */
const val EXTRA_OPEN_TAB = "open_tab"
const val EXTRA_OPEN_NOTEBOOK = "open_notebook"
const val TAB_NOTEBOOKS = "notebooks"

private val Bg = ColorProvider(R.color.widget_bg)
private val OnBg = ColorProvider(R.color.widget_on)
private val Muted = ColorProvider(R.color.widget_muted)
private val Accent = ColorProvider(R.color.widget_accent)

private val openTabKey = ActionParameters.Key<String>(EXTRA_OPEN_TAB)
private val openNotebookKey = ActionParameters.Key<String>(EXTRA_OPEN_NOTEBOOK)

class NotebooksWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = context.graph.repo.notebooks()
        provideContent {
            val notebooks by flow.collectAsState(emptyList())
            WidgetUi(context, notebooks)
        }
    }

    companion object {
        suspend fun refresh(context: Context) = NotebooksWidget().updateAll(context)
    }
}

class NotebooksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotebooksWidget()
}

@Composable
private fun WidgetUi(context: Context, notebooks: List<Notebook>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Bg)
            .cornerRadius(20.dp)
            .padding(14.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth()
                .clickable(actionStartActivity<MainActivity>(actionParametersOf(openTabKey to TAB_NOTEBOOKS))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                context.getString(R.string.wgt_notebooks_title),
                style = TextStyle(color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text("›", style = TextStyle(color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.height(8.dp))
        if (notebooks.isEmpty()) {
            Text(
                context.getString(R.string.wgt_notebooks_empty),
                style = TextStyle(color = Muted, fontSize = 13.sp),
            )
        } else {
            LazyColumn {
                items(notebooks, itemId = { it.id.hashCode().toLong() }) { nb ->
                    NotebookRow(nb)
                }
            }
        }
    }
}

@Composable
private fun NotebookRow(nb: Notebook) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    actionParametersOf(openTabKey to TAB_NOTEBOOKS, openNotebookKey to nb.id),
                ),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            GlanceModifier.size(10.dp).cornerRadius(5.dp).background(ColorProvider(parseColor(nb.color))),
        ) {}
        Spacer(GlanceModifier.width(8.dp))
        Text(nb.name, style = TextStyle(color = OnBg, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        Spacer(GlanceModifier.defaultWeight())
        Text("${nb.pageCount}", style = TextStyle(color = Muted, fontSize = 13.sp))
    }
}

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
