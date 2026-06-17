package net.micapum.neopen.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.micapum.neopen.app.capability.CapabilityDispatcher
import net.micapum.neopen.app.capability.LocalConnectorSync
import net.micapum.neopen.app.data.NeopenRepository
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.sync.SyncEngine
import net.micapum.neopen.app.sync.SyncWorker
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.pen.KnownPen
import net.micapum.neopen.core.data.OverlapConfig
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
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.appsvc_channel_pen), NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_ID = "pen-connection"
    }
}

/** v2→v3: añade la tabla de fondos (anclajes de plantilla) sin perder capturas. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS page_backgrounds (
                id TEXT NOT NULL PRIMARY KEY,
                bookKey TEXT NOT NULL,
                page INTEGER,
                scope TEXT NOT NULL,
                bgPreset TEXT,
                bgImage TEXT,
                label TEXT NOT NULL,
                source TEXT NOT NULL,
                remote INTEGER NOT NULL,
                createdAt TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_page_backgrounds_bookKey ON page_backgrounds(bookKey)")
    }
}

/** v3→v4: añade la columna `sealed` (hoja finalizada) a captures. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE captures ADD COLUMN sealed INTEGER NOT NULL DEFAULT 0")
    }
}

/** v4→v5: añade la columna `overlapJson` (aviso de trazos superpuestos) a captures. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE captures ADD COLUMN overlapJson TEXT")
    }
}

/** v5→v6: añade la columna `clonedFrom` (hoja clon) a captures. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE captures ADD COLUMN clonedFrom TEXT")
    }
}

/** v6→v7: tabla de estado de la sincronización local a conectores (WebDAV/FTP). */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS connector_sync_state (
                itemId TEXT NOT NULL,
                connectorId TEXT NOT NULL,
                format TEXT NOT NULL,
                remotePath TEXT NOT NULL,
                sourceHash TEXT NOT NULL,
                syncedAt INTEGER NOT NULL,
                PRIMARY KEY (itemId, connectorId, format)
            )
            """.trimIndent(),
        )
    }
}

/** v7→v8: avisos locales de reimportación offline y acciones previas por SOBP. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pen_page_user_actions (
                sobpKey TEXT NOT NULL PRIMARY KEY,
                section INTEGER NOT NULL,
                owner INTEGER NOT NULL,
                book INTEGER NOT NULL,
                page INTEGER NOT NULL,
                action TEXT NOT NULL,
                captureId TEXT,
                captureName TEXT,
                destination TEXT,
                notebookId TEXT,
                notebookName TEXT,
                paperJson TEXT,
                createdAt TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pen_page_user_actions_section_owner_book_page " +
                "ON pen_page_user_actions(section, owner, book, page)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS offline_import_warnings (
                id TEXT NOT NULL PRIMARY KEY,
                section INTEGER NOT NULL,
                owner INTEGER NOT NULL,
                book INTEGER NOT NULL,
                page INTEGER NOT NULL,
                source TEXT NOT NULL,
                capturedAt TEXT NOT NULL,
                importedAt TEXT NOT NULL,
                name TEXT NOT NULL,
                strokesJson TEXT NOT NULL,
                paperJson TEXT,
                reasonAction TEXT NOT NULL,
                actionCaptureId TEXT,
                actionCaptureName TEXT,
                actionDestination TEXT,
                actionNotebookId TEXT,
                actionNotebookName TEXT,
                actionAt TEXT NOT NULL,
                targetCaptureId TEXT,
                status TEXT NOT NULL,
                resolvedAt TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_import_warnings_status_importedAt ON offline_import_warnings(status, importedAt)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_offline_import_warnings_section_owner_book_page " +
                "ON offline_import_warnings(section, owner, book, page)",
        )
    }
}

/** Grafo de dependencias mínimo (service locator) compartido por la app. */
class Graph(app: Application) {
    val db: NeopenDb = Room.databaseBuilder(app, NeopenDb::class.java, "neopen.db")
        .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
        // Solo permite recrear la BD desde la v1 efímera (dev, nunca publicada; el
        // esquema arrancó en v2). De v2 en adelante SIEMPRE hay migración explícita,
        // así que jamás se borran datos del usuario en una actualización.
        .fallbackToDestructiveMigrationFrom(1)
        .build()
    val settings = SettingsStore(app)
    val syncStatus = SyncStatusStore(app)
    val repo = NeopenRepository(db)
    val sync = SyncEngine(repo, settings)
    /** Despacha las capacidades (OCR/LanguageTool/marcadores) entre teléfono y servidor. */
    val dispatcher = CapabilityDispatcher(this)
    /** Espejo local a conectores (WebDAV/FTP) cuando la capacidad está en TELÉFONO. */
    val connectorSync = LocalConnectorSync(this)
    private val workManager = WorkManager.getInstance(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Debounce del espejo a conectores (colapsa ráfagas de cambios). */
    @Volatile
    private var connectorSyncJob: Job? = null

    /** Modo local activo (sin servidor): el archivado se hace en el dispositivo. */
    @Volatile
    var localMode = false

    /**
     * Boli al que la notificación de control aplica los cambios de color/grueso. null → se usa el
     * conectado, o el primero emparejado. Compartido por PenService y el receiver (mismo proceso).
     */
    @Volatile
    var notifSelectedPen: String? = null

    /** MAC efectiva del boli seleccionado en la notificación de control. */
    fun effectiveNotifPen(): String? =
        notifSelectedPen ?: pen.state.value.mac ?: lastSettings.knownPens.firstOrNull()?.mac

    /** Conexión BLE al boli (una sola, compartida). Cada página → Room (+ sync si hay servidor). */
    val pen = PenManager(app) { page ->
        scope.launch {
            if (localMode) {
                repo.ingestPenPageLocal(page, lastSettings.offlineReimportPolicy)
                syncStatus.markPenSync(System.currentTimeMillis())
            } else {
                repo.ingestPenPage(page, lastSettings.offlineReimportPolicy)
                syncStatus.markPenSync(System.currentTimeMillis())
                syncNow()
            }
            scheduleConnectorSync()
            net.micapum.neopen.app.widget.NotebooksWidget.refresh(app)
            net.micapum.neopen.app.widget.RecentEditsWidget.refresh(app)
        }
    }

    private val _online = MutableStateFlow(false)
    /** Hay conexión a Internet (para habilitar acciones online como el OCR). */
    val online: StateFlow<Boolean> = _online

    /** Últimos ajustes observados (para que el PenManager resuelva el estilo por boli). */
    @Volatile
    private var lastSettings = AppSettings()

    /** Instantánea de los ajustes actuales (para el servicio/receiver de la notificación). */
    val currentSettings: AppSettings get() = lastSettings

    init {
        // El estilo de trazo a sellar depende del boli (MAC) y de los ajustes actuales.
        pen.styleProvider = { mac -> lastSettings.styleFor(mac) }
        pen.connectionAllowed = { mac, manual ->
            val cur = lastSettings
            val locked = cur.lockedPenMac
            when {
                locked.isNotBlank() -> mac == locked
                !manual -> cur.knownPens.any { it.mac == mac } && !cur.forgottenPens.contains(mac)
                else -> true
            }
        }
        // Registra un boli recién visto en el historial de bolis emparejados (si es nuevo).
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
                val next = cur.copy(knownPens = knownPens, forgottenPens = forgotten)
                lastSettings = next
                scope.launch { settings.update(next) }
            }
        }
        // PIN recordado para desbloqueo automático (cifrado en reposo).
        pen.savedPinFor = { mac ->
            lastSettings.knownPens.firstOrNull { it.mac == mac }?.pin?.takeIf { it.isNotEmpty() }
        }
        pen.onPinRemembered = { mac, pin ->
            val cur = lastSettings
            val exists = cur.knownPens.any { it.mac == mac }
            val updated = when {
                exists -> cur.knownPens.map { if (it.mac == mac) it.copy(pin = pin ?: "") else it }
                pin != null -> cur.knownPens + KnownPen(mac, mac, cur.defaultStyle, pin)
                else -> cur.knownPens
            }
            val next = cur.copy(knownPens = updated)
            lastSettings = next
            scope.launch { settings.update(next) }
        }
        // Mantén al día el flag de auto-volcado y el modo local desde los ajustes.
        scope.launch {
            settings.flow.collect {
                lastSettings = it
                pen.autoImportOnConnect = it.importOnConnect
                localMode = it.appMode == AppMode.LOCAL
                // El umbral de superposición lo lee la ingesta local (modo local).
                repo.overlapConfig = OverlapConfig(
                    enabled = it.overlapEnabled,
                    minStrokes = it.overlapMinStrokes,
                    minAreaPercent = it.overlapMinAreaPercent,
                )
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

    /** Encola la subida de una transcripción hecha en el teléfono y sincroniza. */
    fun enqueueTranscriptionSync(captureId: String) {
        scope.launch {
            repo.enqueueTranscription(captureId)
            syncNow()
        }
    }

    /** Sincronización inmediata (al abrir la app, recuperar red o editar). */
    fun syncNow() {
        val once = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(netConstraints())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork("neopen-sync-now", ExistingWorkPolicy.REPLACE, once)
        scheduleConnectorSync()
    }

    /**
     * Programa una pasada del espejo a conectores con debounce de 1,5 s (igual que
     * el servidor). Solo hace trabajo si la capacidad está en TELÉFONO; si no, sale
     * enseguida. Colapsa ráfagas de ediciones/ingestas en una sola pasada.
     */
    fun scheduleConnectorSync() {
        connectorSyncJob?.cancel()
        connectorSyncJob = scope.launch {
            kotlinx.coroutines.delay(1500)
            runCatching { connectorSync.sync() }
        }
    }
}

/** Acceso al grafo desde Composables/Workers. */
val Context.graph: Graph
    get() = (applicationContext as NeopenFullApp).graph
