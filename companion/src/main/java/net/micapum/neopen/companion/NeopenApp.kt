package net.micapum.neopen.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.micapum.neopen.core.data.SettingsStore
import net.micapum.neopen.core.data.SyncMode
import net.micapum.neopen.core.data.SyncStatusStore
import net.micapum.neopen.core.pen.PenManager
import net.micapum.neopen.companion.sync.SyncWorker
import net.micapum.neopen.core.sync.Uploader
import net.micapum.neopen.companion.widget.SyncWidget

/**
 * Singletons de la app: ajustes, el PenManager (una sola conexión BLE) y el uploader.
 * El PenManager vive aquí para que la UI y el servicio en primer plano compartan
 * la misma conexión.
 */
class NeopenApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var syncStatus: SyncStatusStore
        private set
    lateinit var uploader: Uploader
        private set
    lateinit var pen: PenManager
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Scope ligado a la Application (no a la Activity): para escrituras que NO deben
     *  cancelarse si la pantalla se cierra (p. ej. guardar ajustes). */
    val appScope: CoroutineScope get() = scope

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        syncStatus = SyncStatusStore(this)
        uploader = Uploader(settings)
        pen = PenManager(this) { page ->
            scope.launch {
                val now = System.currentTimeMillis()
                // Llegó una página del boli → sync con el boli.
                syncStatus.markPenSync(now)
                // Subida correcta → sync con el servidor.
                val ok = uploader.upload(page)
                if (ok) {
                    syncStatus.markServerSync(System.currentTimeMillis())
                    pen.pushLog("✓ Subida OK al servidor (${page.strokes.size} trazos)")
                } else {
                    pen.pushLog("✗ Subida FALLÓ (¿servidor/contraseña configurados?)")
                }
                notifyUpload(ok, page.strokes.size)
                SyncWidget.refresh(this@NeopenApp)
            }
        }
        createChannel()
    }

    /** Reprograma el volcado offline periódico según los ajustes. */
    fun applySyncSchedule(mode: SyncMode, intervalMin: Int) {
        val wm = WorkManager.getInstance(this)
        if (mode == SyncMode.INTERVAL) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES,
            ).build()
            wm.enqueueUniquePeriodicWork(SyncWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(SyncWorker.NAME)
        }
    }

    /**
     * Notifica el resultado real de una subida (reusa una misma notificación que
     * se actualiza en sitio, para no acumular). En Android 13+ requiere
     * POST_NOTIFICATIONS; si no está concedido, no-op silencioso.
     */
    private fun notifyUpload(ok: Boolean, strokes: Int) {
        val text = if (ok) "Subida correcta · $strokes trazos"
        else "Error de red al subir — se reintentará"
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(UPLOAD_NOTIF_ID, notif) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_ID = "pen-connection"
        private const val UPLOAD_NOTIF_ID = 43
        fun from(context: Context) = context.applicationContext as NeopenApp
    }
}
