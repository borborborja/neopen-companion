package net.micapum.neopen.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.app.graph
import net.micapum.neopen.core.pen.PenStatus

/**
 * Sincronización en segundo plano. Si se le pide ([KEY_IMPORT_OFFLINE], solo en
 * el worker periódico) y el boli está conectado, primero vuelca su memoria
 * offline (las páginas fluyen a Room+sync por el callback del PenManager). Luego
 * ejecuta el push→pull con el servidor y marca el timestamp para el widget.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        var penOk = true
        if (inputData.getBoolean(KEY_IMPORT_OFFLINE, false) &&
            g.pen.state.value.status == PenStatus.AUTHORIZED
        ) {
            penOk = g.pen.importOfflineMemory()
        }
        penOk = g.awaitPenPersistence() && penOk
        val r = g.sync.sync()
        if (r.ok && g.settings.flow.first().appMode != AppMode.LOCAL) g.syncStatus.markServerSync(System.currentTimeMillis())
        return if (r.ok && penOk) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_IMPORT_OFFLINE = "import_offline"
    }
}
