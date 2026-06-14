package net.micapum.neopen.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.micapum.neopen.app.data.NeopenRepository
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.sync.SyncEngine
import net.micapum.neopen.app.sync.SyncWorker
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.core.data.SettingsStore
import net.micapum.neopen.core.data.SyncMode
import net.micapum.neopen.core.data.SyncStatusStore
import net.micapum.neopen.core.pen.PenManager
import java.util.concurrent.TimeUnit

/** Application de la app completa: crea el grafo de dependencias y programa el sync. */
class NeopenFullApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        graph.scheduleFromSettings()
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Boli", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_ID = "pen-connection"
    }
}

/** Grafo de dependencias mínimo (service locator) compartido por la app. */
class Graph(app: Application) {
    val db: NeopenDb = Room.databaseBuilder(app, NeopenDb::class.java, "neopen.db")
        .fallbackToDestructiveMigration()
        .build()
    val settings = SettingsStore(app)
    val syncStatus = SyncStatusStore(app)
    val repo = NeopenRepository(db)
    val sync = SyncEngine(repo, settings)
    private val workManager = WorkManager.getInstance(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Modo local activo (sin servidor): el archivado se hace en el dispositivo. */
    @Volatile
    var localMode = false

    /** Conexión BLE al boli (una sola, compartida). Cada página → Room (+ sync si hay servidor). */
    val pen = PenManager(app) { page ->
        scope.launch {
            if (localMode) {
                repo.ingestPenPageLocal(page)
                syncStatus.markPenSync(System.currentTimeMillis())
            } else {
                repo.ingestPenPage(page)
                syncStatus.markPenSync(System.currentTimeMillis())
                syncNow()
            }
        }
    }

    private val _online = MutableStateFlow(false)
    /** Hay conexión a Internet (para habilitar acciones online como el OCR). */
    val online: StateFlow<Boolean> = _online

    init {
        // Mantén al día el flag de auto-volcado y el modo local desde los ajustes.
        scope.launch {
            settings.flow.collect {
                pen.autoImportOnConnect = it.importOnConnect
                localMode = it.appMode == AppMode.LOCAL
            }
        }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        _online.value = cm?.activeNetwork != null
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _online.value = true
                syncNow() // recuperamos red → sincroniza
            }

            override fun onLost(network: Network) {
                _online.value = false
            }
        })
    }

    private fun netConstraints() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Programa (o cancela) la sincronización periódica según el modo de sync. */
    fun applySyncSchedule(mode: SyncMode, intervalMin: Int) {
        if (mode == SyncMode.INTERVAL) {
            val periodic = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES,
            ).setConstraints(netConstraints())
                // El worker periódico también vuelca la memoria offline del boli si está conectado.
                .setInputData(workDataOf(SyncWorker.KEY_IMPORT_OFFLINE to true))
                .build()
            workManager.enqueueUniquePeriodicWork("neopen-sync", ExistingPeriodicWorkPolicy.UPDATE, periodic)
        } else {
            workManager.cancelUniqueWork("neopen-sync")
        }
    }

    /** Lee el modo de sync persistido y aplica el calendario (al arrancar). */
    fun scheduleFromSettings() {
        scope.launch {
            val s = settings.flow.first()
            applySyncSchedule(s.syncMode, s.syncIntervalMin)
        }
    }

    /** Sincronización inmediata (al abrir la app, recuperar red o editar). */
    fun syncNow() {
        val once = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(netConstraints())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork("neopen-sync-now", ExistingWorkPolicy.REPLACE, once)
    }
}

/** Acceso al grafo desde Composables/Workers. */
val Context.graph: Graph
    get() = (applicationContext as NeopenFullApp).graph
