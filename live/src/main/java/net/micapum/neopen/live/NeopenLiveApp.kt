package net.micapum.neopen.live

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import net.micapum.neopen.core.data.SettingsStore
import net.micapum.neopen.core.pen.PenManager

/**
 * App slim de escritura en vivo. Singletons mínimos: ajustes + una sola conexión BLE
 * (`PenManager`), compartida entre la UI, el servicio en primer plano y el teclado IME.
 *
 * A diferencia de la app completa, aquí NO hay base de datos, sync ni cuadernos: en modo
 * escritura en vivo las páginas no se guardan, así que el callback de página es un no-op.
 */
class NeopenLiveApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var pen: PenManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        pen = PenManager(this) { /* live-only: las páginas no se persisten */ }
        createChannel()
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
        fun from(context: Context) = context.applicationContext as NeopenLiveApp
    }
}
