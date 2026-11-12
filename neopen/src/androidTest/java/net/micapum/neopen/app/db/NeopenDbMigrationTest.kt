package net.micapum.neopen.app.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.micapum.neopen.app.MIGRATION_2_3
import net.micapum.neopen.app.MIGRATION_3_4
import net.micapum.neopen.app.MIGRATION_4_5
import net.micapum.neopen.app.MIGRATION_5_6
import net.micapum.neopen.app.MIGRATION_6_7
import net.micapum.neopen.app.MIGRATION_7_8
import net.micapum.neopen.app.MIGRATION_8_9
import net.micapum.neopen.app.MIGRATION_9_10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeopenDbMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NeopenDb::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val migrations = arrayOf(
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
    )

    @Test
    fun everyPublishedUpgradeHopValidates() {
        migrations.forEachIndexed { index, migration ->
            val from = index + 2
            val databaseName = "migration-$from-${from + 1}"
            helper.createDatabase(databaseName, from).close()
            helper.runMigrationsAndValidate(databaseName, from + 1, true, migration).close()
        }
    }

    @Test
    fun migrationFromV2ToV10PreservesUserData() {
        val databaseName = "migration-2-10"
        helper.createDatabase(databaseName, 2).apply {
            execSQL(
                """
                INSERT INTO captures (
                    id, rev, section, owner, book, page, source, capturedAt,
                    importedAt, updatedAt, name, tagsJson, strokesJson,
                    destination, position
                ) VALUES (
                    'capture-legacy', 7, 3, 27, 388, 5, 'offline',
                    '2025-01-01T10:00:00Z', '2025-01-01T10:00:01Z',
                    '2025-01-01T10:00:02Z', 'Legacy page', '[]', '[]',
                    'inbox', 0
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(databaseName, 10, true, *migrations)
        val cursor = migrated.query(
            "SELECT name, rev, sealed, overlapJson, clonedFrom, penId, bookInstanceId, assignmentId, assignmentRevision FROM captures WHERE id = 'capture-legacy'",
        )
        cursor.use {
            it.moveToFirst()
            assertEquals("Legacy page", it.getString(0))
            assertEquals(7, it.getLong(1))
            assertEquals(0, it.getInt(2))
            assertNull(it.getString(3))
            assertNull(it.getString(4))
            assertNull(it.getString(5))
            assertNull(it.getString(6))
            assertNull(it.getString(7))
            assertTrue(it.isNull(8))
        }
        migrated.close()
    }
}
