package net.micapum.neopen.app.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.micapum.neopen.app.data.NeopenRepository
import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.core.data.ApiFactory
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.CreateCaptureInput
import net.micapum.neopen.core.data.DotDto
import net.micapum.neopen.core.data.SettingsStore
import net.micapum.neopen.core.data.SobpDto
import net.micapum.neopen.core.data.StrokeDto
import net.micapum.neopen.core.data.dataStore
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private lateinit var context: Context
    private lateinit var database: NeopenDb
    private lateinit var repository: NeopenRepository
    private lateinit var settings: SettingsStore
    private lateinit var server: MockWebServer
    private lateinit var testClient: OkHttpClient

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.dataStore.edit { it.clear() }
        database = Room.inMemoryDatabaseBuilder(context, NeopenDb::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NeopenRepository(database)
        settings = SettingsStore(context)
        val serverCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .build()
        testClient = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        settings.update(
            AppSettings(
                serverUrl = server.url("/").toString(),
                password = "secret",
                setupComplete = true,
            ),
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            database.close()
            server.shutdown()
            context.dataStore.edit { it.clear() }
        }
    }

    @Test
    fun drainsOutboxAndAdvancesDeltaCursor() = runBlocking {
        val input = CreateCaptureInput(
            sobp = SobpDto(3, 27, 388, 5),
            source = "realtime",
            capturedAt = "2026-01-02T10:00:00Z",
            name = "Pending page",
            strokes = listOf(
                StrokeDto(
                    startTime = 1,
                    endTime = 2,
                    dots = listOf(DotDto(10f, 20f, 0.5f, 1)),
                ),
            ),
        )
        repository.enqueueCreateCapture(
            input,
            CaptureEntity(
                id = "local-capture",
                rev = 0,
                section = 3,
                owner = 27,
                book = 388,
                page = 5,
                notebook = null,
                source = "realtime",
                capturedAt = "2026-01-02T10:00:00Z",
                importedAt = "2026-01-02T10:00:01Z",
                updatedAt = "2026-01-02T10:00:01Z",
                name = "Pending page",
            ),
        )
        server.enqueue(jsonResponse("""{"studio":{"enabled":false}}"""))
        server.enqueue(jsonResponse(captureJson("server-capture", 1)))

        val engine = SyncEngine(repository, settings) { ApiFactory.create(it, testClient) }
        val push = engine.push()

        assertTrue(push.error, push.ok)
        assertEquals(1, push.pushed)
        assertTrue(repository.outboxPeek(20).isEmpty())
        assertEquals("/api/capabilities", server.takeRequest().path)
        assertEquals("/api/captures", server.takeRequest().path)

        server.enqueue(jsonResponse("""{"studio":{"enabled":false}}"""))
        server.enqueue(
            jsonResponse(
                """
                {
                  "cursor": 5,
                  "hasMore": false,
                  "reset": false,
                  "captures": [${captureJson("delta-capture", 5)}],
                  "notebooks": [],
                  "deletions": []
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(jsonResponse(notebookJson()))
        server.enqueue(jsonResponse("[]"))
        server.enqueue(jsonResponse("""{"pens":[]}"""))
        server.enqueue(jsonResponse("""{"pressureWidthEnabled":true,"pressureWidthContrast":2.5}"""))

        val pull = engine.pull()

        assertTrue(pull.error, pull.ok)
        assertEquals(1, pull.pulled)
        assertEquals(5, repository.cursor())
        assertEquals("/api/capabilities", server.takeRequest().path)
        val changesRequest = server.takeRequest()
        assertTrue(changesRequest.path.orEmpty().startsWith("/api/changes?since=0"))
        assertEquals("secret", changesRequest.getHeader("X-Auth-Password"))
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun captureJson(id: String, rev: Long) = """
        {
          "id": "$id",
          "rev": $rev,
          "sobp": {"section":3,"owner":27,"book":388,"page":5},
          "source": "realtime",
          "capturedAt": "2026-01-02T10:00:00Z",
          "importedAt": "2026-01-02T10:00:01Z",
          "updatedAt": "2026-01-02T10:00:01Z",
          "name": "Synced page",
          "tags": [],
          "strokes": [],
          "destination": "inbox",
          "position": 0
        }
    """.trimIndent()

    private fun notebookJson() = """
        {
          "id":"unclassified",
          "rev":5,
          "name":"Unclassified",
          "color":"#888888",
          "tags":[],
          "createdAt":"2026-01-01T00:00:00Z",
          "updatedAt":"2026-01-01T00:00:00Z",
          "unclassified":true
        }
    """.trimIndent()
}
