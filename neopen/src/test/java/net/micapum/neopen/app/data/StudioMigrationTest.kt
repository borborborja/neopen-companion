package net.micapum.neopen.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.micapum.neopen.app.MIGRATION_9_10
import net.micapum.neopen.app.db.NeopenDb
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = Application::class)
class StudioMigrationTest {
    @Test fun upgradesPublishedVersionNineWithoutLosingCaptureOrItsBackground() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "studio-migration-test.db"
        context.deleteDatabase(name)
        val path = "schemas/net.micapum.neopen.app.db.NeopenDb/9.json"
        val schema = JSONObject((File(path).takeIf { it.exists() } ?: File("neopen/$path")).readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indexes = entity.getJSONArray("indices")
                    for (j in 0 until indexes.length()) db.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).build())
        val old = helper.writableDatabase
        old.execSQL("INSERT INTO captures (id,rev,section,owner,book,page,source,capturedAt,importedAt,updatedAt,name,tagsJson,strokesJson,destination,position,sealed) VALUES ('old',42,3,27,512,0,'realtime','then','then','then','Keep me','[\"tag\"]','[{\"startTime\":1,\"endTime\":2,\"dots\":[]}]','inbox',0,1)")
        old.execSQL("INSERT INTO page_backgrounds (id,bookKey,page,scope,bgPreset,label,source,remote,createdAt) VALUES ('design','3.27.512',0,'page','grid','Original','custom',1,'then')")
        helper.close()
        val migrated = Room.databaseBuilder(context, NeopenDb::class.java, name).addMigrations(MIGRATION_9_10).allowMainThreadQueries().build()
        try {
            val cap = requireNotNull(migrated.captures().getById("old"))
            assertEquals(42L, cap.rev)
            assertEquals("Keep me", cap.name)
            assertTrue(cap.sealed)
            assertEquals(1, Json.strokes(cap.strokesJson).size)
            assertNull(cap.bookInstanceId)
            assertEquals("Original", migrated.backgrounds().getById("design")?.label)
            assertTrue(migrated.studio().books().isEmpty())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
