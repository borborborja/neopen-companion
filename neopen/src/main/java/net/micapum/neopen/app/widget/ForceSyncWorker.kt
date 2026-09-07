package net.micapum.neopen.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.micapum.neopen.app.graph
import net.micapum.neopen.core.pen.PenStatus
import net.micapum.neopen.core.data.AppMode

/**
 * Sincronización forzada desde el widget: asegura conexión con el boli, le pide
 * toda la memoria offline (las páginas fluyen a Room+sync por el callback del
 * PenManager), ejecuta el push→pull con el servidor y refresca los widgets.
 */
class ForceSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val pen = g.pen

        if (pen.state.value.status != PenStatus.AUTHORIZED) {
            withContext(Dispatchers.Main.immediate) { pen.scanAndConnect() }
            withTimeoutOrNull(15_000) {
                pen.state.first { it.status == PenStatus.AUTHORIZED || it.status == PenStatus.IDLE || it.status == PenStatus.DISCONNECTED }
            }
        }
        val penOk = pen.state.value.connected && pen.importOfflineMemory()
        val persisted = g.awaitPenPersistence()

        val r = g.sync.sync()
        if (r.ok && g.settings.flow.first().appMode != AppMode.LOCAL) {
            g.syncStatus.markServerSync(System.currentTimeMillis())
        }

        SyncWidget.refresh(applicationContext)
        NotebooksWidget.refresh(applicationContext)
        RecentEditsWidget.refresh(applicationContext)
        return if (r.ok && penOk && persisted) Result.success() else Result.retry()
    }

    companion object {
        const val NAME = "force-sync"
    }
}
