package net.micapum.neopen.app.sync

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.SettingsStore
import net.micapum.neopen.core.data.dataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsSnapshotRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun clear() = runBlocking { context.dataStore.edit { it.clear() }; Unit }

    @Test fun firstReadsAreFreshAfterWritesAndOldFormsCannotResetTheServerBinding() = runBlocking {
        context.dataStore.edit { it.clear() }
        val store = SettingsStore(context)
        val form = AppSettings(serverUrl = "https://example.com", password = "first", setupComplete = true)
        store.update(form)
        val first = store.flow.first()
        assertSame(first, store.flow.first())
        store.bindSyncServer("https://EXAMPLE.com:443/")
        assertEquals("https://example.com", store.flow.first().syncServerUrl)
        store.update(form.copy(password = "second", themeMode = "dark"))
        val second = store.flow.first()
        assertNotSame(first, second)
        assertEquals("second", second.password)
        assertEquals("dark", second.themeMode)
        assertEquals("https://example.com", second.syncServerUrl)
        // DataStore sigue siendo la autoridad: también ve escrituras externas al wrapper.
        context.dataStore.edit { it[stringPreferencesKey("theme_mode")] = "light" }
        assertEquals("light", store.flow.first().themeMode)
    }
}
