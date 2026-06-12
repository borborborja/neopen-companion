package net.micapum.neopen.companion.sync

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import net.micapum.neopen.companion.NeopenApp
import net.micapum.neopen.companion.R

/**
 * Servicio en primer plano: mantiene viva la conexión BLE (del PenManager de la
 * Application) cuando la app está en segundo plano. Necesario para captar la
 * escritura sin tener la app abierta.
 */
class PenService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        val app = NeopenApp.from(this)
        if (!app.pen.state.value.connected) app.pen.scanAndConnect()
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NeopenApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Conexión del boli en segundo plano")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 42
        fun start(context: Context) =
            context.startForegroundService(Intent(context, PenService::class.java))
        fun stop(context: Context) =
            context.stopService(Intent(context, PenService::class.java))
    }
}
