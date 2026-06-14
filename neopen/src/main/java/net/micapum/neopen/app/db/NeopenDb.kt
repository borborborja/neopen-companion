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

@Database(
    entities = [CaptureEntity::class, NotebookEntity::class, OutboxEntity::class, SyncMetaEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class NeopenDb : RoomDatabase() {
    abstract fun captures(): CaptureDao
    abstract fun notebooks(): NotebookDao
    abstract fun outbox(): OutboxDao
    abstract fun syncMeta(): SyncMetaDao
}
