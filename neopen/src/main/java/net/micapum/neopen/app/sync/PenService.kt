package net.micapum.neopen.app.sync

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import net.micapum.neopen.app.NeopenFullApp
import net.micapum.neopen.app.R
import net.micapum.neopen.app.graph

/**
 * Servicio en primer plano: mantiene viva la conexión BLE al boli (del PenManager
 * del grafo) cuando la app está en segundo plano, y activa la reconexión
 * automática. Se arranca/para según el ajuste "conexión en segundo plano".
 */
class PenService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        val pen = applicationContext.graph.pen
        pen.autoReconnect = true
        if (!pen.state.value.connected) pen.scanAndConnect()
        return START_STICKY
    }

    override fun onDestroy() {
        applicationContext.graph.pen.autoReconnect = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NeopenFullApp.CHANNEL_ID)
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
