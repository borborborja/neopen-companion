package net.micapum.neopen.app.sync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.micapum.neopen.app.NeopenFullApp
import net.micapum.neopen.app.R
import net.micapum.neopen.app.graph
import net.micapum.neopen.core.data.AppSettings

/**
 * Servicio en primer plano: mantiene viva la conexión BLE al boli (del PenManager del grafo) y,
 * opcionalmente, muestra la **notificación de control** (cambiar color/grueso/boli/segundo plano
 * sin abrir la app). Se arranca/para según los ajustes "conexión en segundo plano" y
 * "notificación de control".
 */
class PenService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = applicationContext.graph
        val settings = graph.currentSettings
        startForeground(ControlNotification.NOTIF_ID, notif(settings))
        graph.pen.autoReconnect = settings.backgroundEnabled
        if (settings.backgroundEnabled && !graph.pen.state.value.connected) graph.pen.scanAndConnectAuto()
        startObserving()
        return START_STICKY
    }

    override fun onDestroy() {
        applicationContext.graph.pen.autoReconnect = false
        scope.cancel()
        super.onDestroy()
    }

    /** Notificación según ajustes: la de control si está activa, si no la simple. */
    private fun notif(settings: AppSettings): Notification =
        if (settings.controlNotification) ControlNotification.build(this, settings) else plain()

    private fun plain(): Notification =
        NotificationCompat.Builder(this, NeopenFullApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.pensvc_notif_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

    /** Re-postea la notificación cuando cambian los ajustes (estilo/boli/2º plano) o la conexión. */
    @SuppressLint("MissingPermission")
    private fun startObserving() {
        if (observing) return
        observing = true
        val graph = applicationContext.graph
        scope.launch {
            graph.settings.flow.collect { s ->
                graph.pen.autoReconnect = s.backgroundEnabled
                NotificationManagerCompat.from(this@PenService).notify(ControlNotification.NOTIF_ID, notif(s))
                net.micapum.neopen.app.widget.SyncWidget.refresh(applicationContext)
            }
        }
        scope.launch {
            // Solo los campos del boli que se muestran (no capturedPages, que cambia en cada trazo).
            graph.pen.state
                .map { listOf(it.mac, it.status.name, it.name, it.settings.battery?.toString(), it.settings.charging.toString()) }
                .distinctUntilChanged()
                .collect {
                    NotificationManagerCompat.from(this@PenService).notify(ControlNotification.NOTIF_ID, notif(graph.currentSettings))
                    net.micapum.neopen.app.widget.SyncWidget.refresh(applicationContext)
                }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) =
            context.startForegroundService(Intent(context, PenService::class.java))
        fun stop(context: Context) =
            context.stopService(Intent(context, PenService::class.java))
    }
}
