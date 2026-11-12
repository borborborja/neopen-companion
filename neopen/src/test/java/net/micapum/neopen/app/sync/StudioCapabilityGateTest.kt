package net.micapum.neopen.app.sync

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.micapum.neopen.app.data.*
import net.micapum.neopen.app.db.*
import net.micapum.neopen.core.data.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = Application::class)
class StudioCapabilityGateTest {
    private lateinit var context: Context
    private lateinit var db: NeopenDb
    private lateinit var repo: NeopenRepository
    private lateinit var settings: SettingsStore
    private lateinit var server: MockWebServer
    private lateinit var api: NeopenApi
    private lateinit var engine: SyncEngine
    @Volatile private var capabilityBody = "{}"
    @Volatile private var rejectStudio = false
    private val paths = Collections.synchronizedList(mutableListOf<String>())
    private val now = "2026-11-12T00:00:00Z"
    private val book = StudioBookDto("book", 1, "Original", STUDIO_ENABLED_PROFILE_ID, 3, 27, 512, "a5", StudioRecipeDto(), 4, "ready", createdAt = now, updatedAt = now)
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.dataStore.edit { it.clear() }
        db = Room.inMemoryDatabaseBuilder(context, NeopenDb::class.java).allowMainThreadQueries().build()
        repo = NeopenRepository(db); settings = SettingsStore(context)
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCert = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager).build()
        server = MockWebServer().apply { useHttps(serverCert.sslSocketFactory(), false) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty(); paths.add(path)
                return when {
                    path == "/api/capabilities" -> json(capabilityBody)
                    path.startsWith("/api/changes?") -> json("""{"cursor":1,"captures":[],"notebooks":[]}""")
                    path == "/api/pens" && request.method == "POST" -> json("""{"id":"pen","mac":"AA:BB:CC:DD:EE:FF","identifiers":["AA:BB:CC:DD:EE:FF"]}""")
                    path == "/api/pens" -> json("""{"pens":[]}""")
                    path == "/api/anchors" -> json("[]")
                    path == "/api/render-settings" -> json("{}")
                    path == "/api/studio/books/book" && request.method == "PATCH" -> if (rejectStudio) json("""{"code":"STUDIO_DISABLED"}""").setResponseCode(403) else json(StudioJson.book.toJson(book.copy(rev = 2, name = "Renamed")))
                    path.startsWith("/api/studio/") -> json("[]")
                    path == "/api/captures" && request.method == "POST" -> json("""{"id":"uploaded","rev":1,"sobp":{"section":1,"owner":1,"book":1,"page":0},"source":"realtime","capturedAt":"$now","importedAt":"$now","name":"Ink"}""")
                    path == "/api/captures/old/design" -> MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF-original")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val url = server.url("/").toString()
        settings.update(AppSettings(serverUrl = url, syncServerUrl = url, password = "audit", setupComplete = true))
        api = ApiFactory.create(url, client)
        engine = SyncEngine(repo, settings) { ApiFactory.create(it, client) }
        repo.studio.apply(listOf(book), emptyList(), emptyList())
    }
    @After fun close() = runBlocking { db.close(); server.shutdown(); context.dataStore.edit { it.clear() }; Unit }

    @Test fun startupMissingFalseAndFailedVerificationNeverEnableStudio() = runBlocking {
        assertFalse(engine.studioEnabled.first())
        engine.refreshCapabilities()
        assertFalse(engine.studioEnabled.first())
        capabilityBody = """{"studio":{"enabled":false}}"""
        engine.refreshCapabilities()
        assertFalse(engine.studioEnabled.first())
        capabilityBody = "invalid-json"
        engine.refreshCapabilities()
        assertFalse(engine.studioEnabled.first())
        assertEquals(1, repo.studio.cachedBooks().size)
        assertTrue(paths.none { it.startsWith("/api/studio/") })
    }
    @Test fun disabledStudioRetainsItsOutboxAndStillUploadsOrdinaryInkAndPenRegistration() = runBlocking {
        repo.studio.patch(book, name = "Renamed")
        repo.studio.registerPen("AA:BB:CC:DD:EE:FF")
        val input = CreateCaptureInput(SobpDto(1, 1, 1, 0), "realtime", now, "Ink", strokes = emptyList())
        repo.enqueueCreateCapture(input, CaptureEntity("local-ink", 0, 1, 1, 1, 0, null, "realtime", now, now, now, "Ink"))
        val result = engine.sync()
        assertFalse(result.ok)
        assertEquals(SyncEngine.STUDIO_SUSPENDED, result.error)
        val pending = repo.outboxPeek(20).single()
        assertEquals(StudioOutbox.PATCH, pending.kind)
        assertEquals(SyncEngine.STUDIO_SUSPENDED, pending.lastError)
        assertNotNull(repo.capture("uploaded"))
        assertEquals("Original", repo.studio.cachedBooks().single().name)
        assertTrue(paths.any { it == "/api/pens" })
        assertTrue(paths.any { it == "/api/captures" })
        assertTrue(paths.none { it.startsWith("/api/studio/") })
    }
    @Test fun explicitTrueAllowsBookMutationAndSwitchingOffClosesTheSameUiSource() = runBlocking {
        capabilityBody = """{"studio":{"enabled":true}}"""
        repo.studio.patch(book, name = "Renamed")
        assertTrue(engine.push().ok)
        assertTrue(engine.studioEnabled.first())
        assertEquals("Renamed", repo.studio.cachedBooks().single().name)
        capabilityBody = """{"studio":{"enabled":false}}"""
        engine.refreshCapabilities()
        assertFalse(engine.studioEnabled.first()) // La pestaña abierta usa este mismo Flow y vuelve a Bandeja.
        assertEquals("Renamed", repo.studio.cachedBooks().single().name)
    }
    @Test fun serverRejectionImmediatelyHidesStudioAndRetainsMutationForLaterEnablement() = runBlocking {
        capabilityBody = """{"studio":{"enabled":true}}"""
        rejectStudio = true
        repo.studio.patch(book, name = "Renamed")
        val paused = engine.push()
        assertFalse(paused.ok)
        assertEquals(SyncEngine.STUDIO_SUSPENDED, paused.error)
        assertFalse(engine.studioEnabled.first())
        assertEquals("Original", repo.studio.cachedBooks().single().name)
        assertEquals(1, repo.outboxPeek(10).size)
        rejectStudio = false
        assertTrue(engine.push().ok)
        assertTrue(engine.studioEnabled.first())
        assertTrue(repo.outboxPeek(10).isEmpty())
    }
    @Test fun serverChangeAndLocalModeInvalidateWithoutContactingAnotherServer() = runBlocking {
        capabilityBody = """{"studio":{"enabled":true}}"""
        engine.refreshCapabilities()
        assertTrue(engine.studioEnabled.first())
        val previous = settings.flow.first()
        settings.update(previous.copy(serverUrl = "https://different.invalid/"))
        assertFalse(engine.studioEnabled.first())
        val count = paths.size
        engine.refreshCapabilities()
        assertEquals(count, paths.size)
        settings.update(previous.copy(appMode = AppMode.LOCAL))
        engine.refreshCapabilities()
        assertFalse(engine.studioEnabled.first())
        assertEquals(count, paths.size)
        assertEquals(1, repo.studio.cachedBooks().size)
    }
    @Test fun historicalDesignUsesOrdinaryCaptureResourceWhileStudioIsOff() = runBlocking {
        engine.refreshCapabilities()
        assertFalse(engine.studioEnabled.first())
        assertEquals("%PDF-original", api.captureDesign("audit", "old").use { it.string() })
        assertTrue(paths.none { it.startsWith("/api/studio/") })
    }
}
