package net.micapum.neopen.companion.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import net.micapum.neopen.companion.NeopenApp
import net.micapum.neopen.core.pen.PenStatus

/**
 * Volcado offline periódico (modo INTERVAL): si el boli está conectado, pide su
 * memoria e importa todas las libretas. Las páginas resultantes se suben por el
 * flujo normal (PenManager.onPage → Uploader).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = NeopenApp.from(applicationContext)
        if (app.pen.state.value.status != PenStatus.AUTHORIZED) return Result.success()
        app.pen.listOffline()
        delay(2_000)
        app.pen.importAllOffline()
        return Result.success()
    }

    companion object {
        const val NAME = "offline-sync"
    }
}
