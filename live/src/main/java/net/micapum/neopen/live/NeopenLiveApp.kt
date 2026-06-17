package net.micapum.neopen.live

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.SettingsStore
import net.micapum.neopen.core.pen.KnownPen
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastSettings = AppSettings()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        pen = PenManager(this) { /* live-only: las páginas no se persisten */ }
        pen.styleProvider = { mac -> lastSettings.styleFor(mac) }
        pen.connectionAllowed = { mac, manual ->
            val locked = lastSettings.lockedPenMac
            when {
                locked.isNotBlank() -> mac == locked
                !manual && lastSettings.forgottenPens.contains(mac) -> false
                else -> true
            }
        }
        pen.onPenSeen = { mac, name ->
            val cur = lastSettings
            val alias = name?.takeIf { it.isNotBlank() } ?: mac
            val knownPens = if (cur.knownPens.none { it.mac == mac }) {
                cur.knownPens + KnownPen(mac, alias, cur.defaultStyle)
            } else {
                cur.knownPens
            }
            val forgotten = cur.forgottenPens.filterNot { it == mac }
            if (knownPens != cur.knownPens || forgotten != cur.forgottenPens) {
                scope.launch { settings.update(cur.copy(knownPens = knownPens, forgottenPens = forgotten)) }
            }
        }
        scope.launch { settings.flow.collect { lastSettings = it } }
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
