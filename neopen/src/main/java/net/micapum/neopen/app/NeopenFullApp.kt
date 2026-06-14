package net.micapum.neopen.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.micapum.neopen.app.data.NeopenRepository
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.sync.SyncEngine
import net.micapum.neopen.app.sync.SyncWorker
import net.micapum.neopen.core.data.SettingsStore
import net.micapum.neopen.core.pen.PenManager
import java.util.concurrent.TimeUnit

/** Application de la app completa: crea el grafo de dependencias y programa el sync. */
class NeopenFullApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        graph.schedulePeriodicSync()
    }
}

/** Grafo de dependencias mínimo (service locator) compartido por la app. */
class Graph(app: Application) {
    val db: NeopenDb = Room.databaseBuilder(app, NeopenDb::class.java, "neopen.db")
        .fallbackToDestructiveMigration()
        .build()
    val settings = SettingsStore(app)
    val repo = NeopenRepository(db)
    val sync = SyncEngine(repo, settings)
    private val workManager = WorkManager.getInstance(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Conexión BLE al boli (una sola, compartida). Cada página → Room + outbox. */
    val pen = PenManager(app) { page ->
        scope.launch {
            repo.ingestPenPage(page)
            syncNow()
        }
    }

    private val _online = MutableStateFlow(false)
    /** Hay conexión a Internet (para habilitar acciones online como el OCR). */
    val online: StateFlow<Boolean> = _online

    init {
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

    fun schedulePeriodicSync() {
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(netConstraints())
            .build()
        workManager.enqueueUniquePeriodicWork(
            "neopen-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
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
