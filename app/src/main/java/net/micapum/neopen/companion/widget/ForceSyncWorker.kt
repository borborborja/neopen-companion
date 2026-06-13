package net.micapum.neopen.companion.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.micapum.neopen.companion.NeopenApp
import net.micapum.neopen.companion.pen.PenStatus

/**
 * Sincronización forzada desde el widget: asegura conexión con el boli, le pide
 * toda la memoria offline y la importa. Las páginas se suben por el flujo normal
 * (PenManager.onPage → Uploader), que también actualiza los timestamps y el widget.
 */
class ForceSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = NeopenApp.from(applicationContext)
        val pen = app.pen

        // 1) Asegura conexión (hasta 15 s esperando a AUTHORIZED).
        if (pen.state.value.status != PenStatus.AUTHORIZED) {
            pen.scanAndConnect()
            withTimeoutOrNull(15_000) {
                pen.state.first { it.status == PenStatus.AUTHORIZED || it.status == PenStatus.IDLE }
            }
        }

        // 2) Si está conectado, vuelca la memoria offline.
        if (pen.state.value.status == PenStatus.AUTHORIZED) {
            pen.listOffline()
            delay(2_500)
            pen.importAllOffline()
            delay(3_000) // margen para que terminen las subidas
            app.syncStatus.markPenSync(System.currentTimeMillis())
        }

        SyncWidget.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        const val NAME = "force-sync"
    }
}
