package net.micapum.neopen.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import net.micapum.neopen.app.graph

/** Ejecuta una sincronización (push→pull) en segundo plano vía WorkManager. */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val r = applicationContext.graph.sync.sync()
        return if (r.ok) Result.success() else Result.retry()
    }
}
