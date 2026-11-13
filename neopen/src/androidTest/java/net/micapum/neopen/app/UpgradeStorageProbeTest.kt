package net.micapum.neopen.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Probe explícito para ejecutar el mismo test APK antes y después de actualizar la app. */
@RunWith(AndroidJUnit4::class)
class UpgradeStorageProbeTest {
    @Test fun preservesInkAndEncryptedSettingsAcrossApkUpgrade() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("upgradeStage")
        assumeTrue("Probe solo para la comprobación explícita de actualización", stage in listOf("seed", "verify"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStore(context)
        val ciphertextRecord = File(context.filesDir, "upgrade-probe-ciphertext.txt")
        val preferencesFile = File(context.filesDir, "datastore/neopen-companion.preferences_pb")

        openExistingDatabase(context.getDatabasePath("neopen.db")).use { database ->
            when (stage) {
                "seed" -> {
                    // No referencia a Graph, entidades Room o repositorios: sus nombres
                    // pueden cambiar entre el APK antiguo y el APK minificado.
                    settings.update(AppSettings(
                        serverUrl = "https://upgrade-probe.invalid",
                        password = SECRET,
                        appMode = AppMode.LOCAL,
                        themeMode = "dark",
                        setupComplete = true,
                        importOnConnect = false,
                    ))
                    val row = ContentValues().apply {
                        put("id", CAPTURE_ID)
                        put("rev", 7L)
                        put("section", 3); put("owner", 27); put("book", 512); put("page", 0)
                        put("source", "realtime")
                        put("capturedAt", TIMESTAMP); put("importedAt", TIMESTAMP); put("updatedAt", TIMESTAMP)
                        put("name", "Upgrade storage probe")
                        put("tagsJson", "[\"upgrade-probe\"]")
                        put("strokesJson", STROKES)
                        put("destination", "inbox"); put("position", 0); put("sealed", 0)
                    }
                    assertTrue(database.insertWithOnConflict("captures", null, row, SQLiteDatabase.CONFLICT_REPLACE) >= 0)
                    val encoded = preferencesFile.readBytes().toString(Charsets.ISO_8859_1)
                    val ciphertext = requireNotNull(CIPHERTEXT.find(encoded)?.value) { "Falta contraseña cifrada en DataStore" }
                    assertFalse("El secreto no debe guardarse en claro", encoded.contains(SECRET))
                    ciphertextRecord.writeText(ciphertext)
                }
                "verify" -> {
                    assertTrue("Falta la instantánea anterior a la actualización", ciphertextRecord.isFile)
                    val encoded = preferencesFile.readBytes().toString(Charsets.ISO_8859_1)
                    assertTrue("La actualización debe conservar el ciphertext", encoded.contains(ciphertextRecord.readText()))
                    assertFalse("El secreto no debe guardarse en claro", encoded.contains(SECRET))
                }
            }
            database.rawQuery("SELECT rev, name, strokesJson, tagsJson FROM captures WHERE id = ?", arrayOf(CAPTURE_ID)).use { cursor ->
                assertTrue("La captura debe sobrevivir a la actualización", cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals("Upgrade storage probe", cursor.getString(1))
                assertEquals(STROKES, cursor.getString(2))
                assertEquals("[\"upgrade-probe\"]", cursor.getString(3))
                assertFalse(cursor.moveToNext())
            }
        }
        val stored = settings.flow.first()
        assertEquals(SECRET, stored.password)
        assertEquals("https://upgrade-probe.invalid", stored.serverUrl)
        assertEquals("dark", stored.themeMode)
        assertEquals(AppMode.LOCAL, stored.appMode)
        assertTrue(stored.setupComplete)
        assertFalse(stored.importOnConnect)
    }

    /** Room abre de forma asíncrona al arrancar Application; no crea una BD paralela vacía. */
    private fun openExistingDatabase(file: File): SQLiteDatabase {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var failure: Exception? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile) {
                try {
                    val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                    try {
                        database.rawQuery("SELECT id FROM captures LIMIT 0", null).use { }
                        return database
                    } catch (error: Exception) { database.close(); throw error }
                } catch (error: Exception) { failure = error }
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("La base existente no terminó de abrirse", failure)
    }

    private companion object {
        const val CAPTURE_ID = "__apk_upgrade_storage_probe__"
        const val SECRET = "upgrade-probe-secret-2026"
        const val TIMESTAMP = "2026-11-12T00:00:00.000Z"
        const val STROKES = "[{\"startTime\":10,\"endTime\":20,\"dots\":[{\"x\":1.25,\"y\":2.5,\"f\":0.7,\"t\":10},{\"x\":3.5,\"y\":4.75,\"f\":0.9,\"t\":20}],\"color\":-65536,\"width\":1.6}]"
        val CIPHERTEXT = Regex("enc1:[A-Za-z0-9+/=]+")
    }
}
