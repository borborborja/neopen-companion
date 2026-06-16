package net.micapum.neopen.live.sync

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import net.micapum.neopen.live.NeopenLiveApp
import net.micapum.neopen.live.R

/**
 * Servicio en primer plano: mantiene viva la conexión BLE (del PenManager de la Application)
 * cuando la app está en segundo plano. Imprescindible aquí: mientras escribes en OTRA app con
 * el teclado, neopen Live está en background y el boli debe seguir conectado.
 */
class PenService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        val app = NeopenLiveApp.from(this)
        app.pen.autoReconnect = true
        if (!app.pen.state.value.connected) app.pen.scanAndConnect()
        return START_STICKY
    }

    override fun onDestroy() {
        NeopenLiveApp.from(this).pen.autoReconnect = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NeopenLiveApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.pensvc_notif_text))
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
