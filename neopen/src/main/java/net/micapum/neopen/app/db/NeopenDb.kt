package net.micapum.neopen.app.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// --- Entidades (espejo local de los tipos del servidor) ----------------------
// strokes/paper/transcription/tags se guardan como JSON (igual que el servidor
// los almacena como TEXT en SQLite).

@Entity(tableName = "captures", indices = [Index("notebookId", "position"), Index("importedAt")])
data class CaptureEntity(
    @PrimaryKey val id: String,
    val rev: Long,
    val section: Int,
    val owner: Int,
    val book: Int,
    val page: Int,
    val notebook: String?,
    val source: String,
    val capturedAt: String,
    val importedAt: String,
    val updatedAt: String,
    val name: String,
    val tagsJson: String = "[]",
    val strokesJson: String = "[]",
    val transcriptionJson: String? = null,
    val destination: String = "inbox",
    val notebookId: String? = null,
    val position: Int = 0,
    val paperJson: String? = null,
    val kind: String? = null,
    val sealed: Boolean = false,
    /** Aviso de trazos superpuestos (JSON de OverlapDto); null = sin aviso. */
    val overlapJson: String? = null,
    /** Id de la hoja origen si es un clon; las hojas clon no reciben fusiones por SOBP. */
    val clonedFrom: String? = null,
)

@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey val id: String,
    val rev: Long,
    val name: String,
    val color: String,
    val tagsJson: String = "[]",
    val createdAt: String,
    val updatedAt: String,
    val paperJson: String? = null,
    val paperSource: String? = null,
    val bookKey: String? = null,
    val coverUrl: String? = null,
    val unclassified: Boolean = false,
    val custom: Boolean = false,
    val category: String? = null,
)

/** Operación local pendiente de subir al servidor (FIFO). */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val kind: String,
    val targetId: String? = null,
    val payloadJson: String = "{}",
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

/** Estado del sincronizador (fila única id=1). */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val id: Int = 1,
    val cursor: Long = 0,
    val lastPullAt: Long = 0,
    val lastPushAt: Long = 0,
)

/**
 * Fondo (plantilla) anclado a un cuaderno o a una hoja. Espejo local de un
 * `DesignAnchor` del servidor (modo completo) o fila puramente local (modo
 * local). `page` null = ámbito cuaderno; con valor = ámbito hoja.
 */
@Entity(tableName = "page_backgrounds", indices = [Index("bookKey")])
data class PageBackgroundEntity(
    @PrimaryKey val id: String,
    val bookKey: String,
    val page: Int? = null,
    val scope: String = "book",
    val bgPreset: String? = null,
    val bgImage: String? = null,
    val label: String = "",
    val source: String = "custom",
    val remote: Boolean = false,
    val createdAt: String,
)

/**
 * Estado de la sincronización local a conectores (espejo de `sync_state` del
 * servidor): qué fichero ya está subido y con qué hash, para no re-subir lo que
 * no cambió y poder borrar lo que se deja de desear. Clave: ítem+conector+formato.
 */
@Entity(tableName = "connector_sync_state", primaryKeys = ["itemId", "connectorId", "format"])
data class ConnectorSyncStateEntity(
    val itemId: String,
    val connectorId: String,
    val format: String,
    val remotePath: String,
    val sourceHash: String,
    val syncedAt: Long,
)

/** Última acción explícita del usuario sobre una página física (SOBP). */
@Entity(
    tableName = "pen_page_user_actions",
    indices = [Index("section", "owner", "book", "page")],
)
data class PenPageUserActionEntity(
    @PrimaryKey val sobpKey: String,
    val section: Int,
    val owner: Int,
    val book: Int,
    val page: Int,
    val action: String,
    val captureId: String?,
    val captureName: String?,
    val destination: String?,
    val notebookId: String?,
    val notebookName: String?,
    val paperJson: String?,
    val createdAt: String,
)

/** Aviso local de trazos reimportados desde la memoria del boli que requieren decisión. */
@Entity(
    tableName = "offline_import_warnings",
    indices = [Index("status", "importedAt"), Index("section", "owner", "book", "page")],
)
data class OfflineImportWarningEntity(
    @PrimaryKey val id: String,
    val section: Int,
    val owner: Int,
    val book: Int,
    val page: Int,
    val source: String,
    val capturedAt: String,
    val importedAt: String,
    val name: String,
    val strokesJson: String,
    val paperJson: String?,
    val reasonAction: String,
    val actionCaptureId: String?,
    val actionCaptureName: String?,
    val actionDestination: String?,
    val actionNotebookId: String?,
    val actionNotebookName: String?,
    val actionAt: String,
    val targetCaptureId: String?,
    val status: String = "pending",
    val resolvedAt: String? = null,
)

// --- DAOs --------------------------------------------------------------------

@Dao
interface CaptureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<CaptureEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOne(row: CaptureEntity)

    @Query("DELETE FROM captures WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun getById(id: String): CaptureEntity?

    @Query("SELECT * FROM captures WHERE id = :id")
    fun observeById(id: String): Flow<CaptureEntity?>

    @Query("SELECT * FROM captures ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<CaptureEntity>>

    @Query("SELECT * FROM captures WHERE destination = 'inbox' ORDER BY importedAt DESC")
    fun observeInbox(): Flow<List<CaptureEntity>>

    @Query("SELECT * FROM captures WHERE notebookId = :notebookId ORDER BY position ASC, importedAt ASC")
    fun observeByNotebook(notebookId: String): Flow<List<CaptureEntity>>

    @Query("SELECT * FROM captures WHERE notebookId = :notebookId")
    suspend fun getByNotebook(notebookId: String): List<CaptureEntity>

    @Query("SELECT * FROM captures WHERE section = :s AND owner = :o AND book = :b AND page = :p AND clonedFrom IS NULL AND sealed = 0 ORDER BY importedAt DESC LIMIT 1")
    suspend fun findBySobp(s: Int, o: Int, b: Int, p: Int): CaptureEntity?

    @Query("UPDATE captures SET sealed = :sealed, updatedAt = :now WHERE id = :id")
    suspend fun setSealed(id: String, sealed: Boolean, now: String)

    @Query("UPDATE captures SET sealed = 1, updatedAt = :now WHERE notebookId = :notebookId")
    suspend fun sealByNotebook(notebookId: String, now: String)

    @Query("SELECT * FROM captures")
    suspend fun getAll(): List<CaptureEntity>

    @Query("UPDATE captures SET notebookId = :newId WHERE notebookId = :oldId")
    suspend fun repointNotebook(oldId: String, newId: String)

    @Query("DELETE FROM captures")
    suspend fun clear()
}

/** id + nº de hojas, para pintar el contador del cuaderno sin cargar las capturas. */
data class NotebookCount(val id: String, val count: Int)

@Dao
interface NotebookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<NotebookEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOne(row: NotebookEntity)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun getById(id: String): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE bookKey = :bookKey LIMIT 1")
    suspend fun findByBookKey(bookKey: String): NotebookEntity?

    @Query("SELECT * FROM notebooks")
    suspend fun getAll(): List<NotebookEntity>

    @Query("SELECT * FROM notebooks ORDER BY unclassified ASC, updatedAt DESC")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Query("SELECT notebookId AS id, COUNT(*) AS count FROM captures WHERE notebookId IS NOT NULL GROUP BY notebookId")
    fun observeCounts(): Flow<List<NotebookCount>>

    @Query("DELETE FROM notebooks")
    suspend fun clear()
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(row: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY localId ASC LIMIT :limit")
    suspend fun peek(limit: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE localId = :localId")
    suspend fun deleteById(localId: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE localId = :localId")
    suspend fun markFailed(localId: Long, error: String?)

    @Query("UPDATE outbox SET targetId = :newId WHERE targetId = :oldId")
    suspend fun repointTarget(oldId: String, newId: String)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
interface SyncMetaDao {
    @Query("SELECT * FROM sync_meta WHERE id = 1")
    suspend fun get(): SyncMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncMetaEntity)
}

@Dao
interface PageBackgroundDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PageBackgroundEntity)

    @Query("SELECT * FROM page_backgrounds WHERE bookKey = :bookKey")
    suspend fun forBook(bookKey: String): List<PageBackgroundEntity>

    @Query("SELECT * FROM page_backgrounds WHERE bookKey = :bookKey")
    fun observeForBook(bookKey: String): Flow<List<PageBackgroundEntity>>

    @Query("SELECT * FROM page_backgrounds WHERE id = :id")
    suspend fun getById(id: String): PageBackgroundEntity?

    @Query("DELETE FROM page_backgrounds WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM page_backgrounds WHERE remote = 1")
    suspend fun clearRemote()

    @Query("DELETE FROM page_backgrounds")
    suspend fun clear()
}

@Dao
interface ConnectorSyncStateDao {
    @Query("SELECT * FROM connector_sync_state")
    suspend fun all(): List<ConnectorSyncStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ConnectorSyncStateEntity)

    @Query("DELETE FROM connector_sync_state WHERE itemId = :itemId AND connectorId = :connectorId AND format = :format")
    suspend fun delete(itemId: String, connectorId: String, format: String)

    @Query("DELETE FROM connector_sync_state")
    suspend fun clear()
}

@Dao
interface PenPageUserActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PenPageUserActionEntity)

    @Query("SELECT * FROM pen_page_user_actions WHERE sobpKey = :sobpKey")
    suspend fun getBySobpKey(sobpKey: String): PenPageUserActionEntity?

    @Query("DELETE FROM pen_page_user_actions")
    suspend fun clear()
}

@Dao
interface OfflineImportWarningDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: OfflineImportWarningEntity)

    @Query("SELECT * FROM offline_import_warnings WHERE id = :id")
    suspend fun getById(id: String): OfflineImportWarningEntity?

    @Query("SELECT * FROM offline_import_warnings WHERE status = 'pending' ORDER BY importedAt DESC")
    fun observePending(): Flow<List<OfflineImportWarningEntity>>

    @Query("SELECT COUNT(*) FROM offline_import_warnings WHERE status = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM offline_import_warnings WHERE id = :id AND status != 'pending'")
    suspend fun getResolved(id: String): OfflineImportWarningEntity?

    @Query("UPDATE offline_import_warnings SET status = :status, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, resolvedAt: String)

    @Query("DELETE FROM offline_import_warnings")
    suspend fun clear()
}

/**
 * Contador de trazos ya importado por nota del boli (gate de re-descarga offline).
 * Indexado por (mac, section, owner, note): si al re-volcar el total que reporta el boli
 * coincide con `strokeCount`, se omite la transferencia de esa nota (no destructivo).
 */
@Entity(tableName = "offline_note_state", primaryKeys = ["mac", "section", "owner", "note"])
data class OfflineNoteStateEntity(
    val mac: String,
    val section: Int,
    val owner: Int,
    val note: Int,
    val strokeCount: Int,
    val importedAt: Long,
)

@Dao
interface OfflineNoteStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: OfflineNoteStateEntity)

    @Query("SELECT * FROM offline_note_state WHERE mac = :mac")
    suspend fun forPen(mac: String): List<OfflineNoteStateEntity>

    @Query("DELETE FROM offline_note_state")
    suspend fun clear()
}

@Database(
    entities = [
        CaptureEntity::class, NotebookEntity::class, OutboxEntity::class,
        SyncMetaEntity::class, PageBackgroundEntity::class, ConnectorSyncStateEntity::class,
        PenPageUserActionEntity::class, OfflineImportWarningEntity::class,
        OfflineNoteStateEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class NeopenDb : RoomDatabase() {
    abstract fun captures(): CaptureDao
    abstract fun notebooks(): NotebookDao
    abstract fun outbox(): OutboxDao
    abstract fun syncMeta(): SyncMetaDao
    abstract fun backgrounds(): PageBackgroundDao
    abstract fun connectorSyncState(): ConnectorSyncStateDao
    abstract fun penPageUserActions(): PenPageUserActionDao
    abstract fun offlineImportWarnings(): OfflineImportWarningDao
    abstract fun offlineNoteState(): OfflineNoteStateDao
}
