package net.micapum.neopen.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.micapum.neopen.app.graph
import net.micapum.neopen.core.pen.PenStatus

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
            pen.scanAndConnect()
            withTimeoutOrNull(15_000) {
                pen.state.first { it.status == PenStatus.AUTHORIZED || it.status == PenStatus.IDLE }
            }
        }
        if (pen.state.value.status == PenStatus.AUTHORIZED) {
            pen.listOffline()
            delay(2_500)
            pen.importAllOffline()
            delay(3_000)
            g.syncStatus.markPenSync(System.currentTimeMillis())
        }

        val r = g.sync.sync()
        if (r.ok) g.syncStatus.markServerSync(System.currentTimeMillis())

        SyncWidget.refresh(applicationContext)
        NotebooksWidget.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        const val NAME = "force-sync"
    }
}
