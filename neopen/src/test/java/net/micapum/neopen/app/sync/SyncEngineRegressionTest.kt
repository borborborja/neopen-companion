package net.micapum.neopen.app.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import net.micapum.neopen.app.data.Json
import net.micapum.neopen.app.data.NeopenRepository
import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.db.NotebookEntity
import net.micapum.neopen.core.data.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Regresiones Android↔web con Room real y HTTP/TLS simulado. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class SyncEngineRegressionTest {
    private lateinit var context: Context
    private lateinit var db: NeopenDb
    private lateinit var repo: NeopenRepository
    private lateinit var settings: SettingsStore
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var engine: SyncEngine
    private val now = "2026-09-07T00:00:00Z"

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.dataStore.edit { it.clear() }
        db = Room.inMemoryDatabaseBuilder(context, NeopenDb::class.java).allowMainThreadQueries().build()
        repo = NeopenRepository(db)
        settings = SettingsStore(context)
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCert = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager).build()
        server = MockWebServer().apply { useHttps(serverCert.sslSocketFactory(), false); start() }
        settings.update(AppSettings(serverUrl = server.url("/").toString(), password = "audit", setupComplete = true))
        engine = SyncEngine(repo, settings) { ApiFactory.create(it, client) }
    }

    @After fun teardown() = runBlocking {
        db.close()
        server.shutdown()
        context.dataStore.edit { it.clear() }
        Unit
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun capture(id: String = "server-cap", name: String = "Original", color: String? = null): String =
        """{"id":"$id","rev":1,"sobp":{"section":3,"owner":27,"book":388,"page":1},"source":"realtime","capturedAt":"$now","importedAt":"$now","name":"$name","strokes":${if (color == null) "[]" else """[{"startTime":1,"endTime":2,"dots":[{"x":1,"y":2,"f":0.5}],"color":$color}]"""},"destination":"notebook","notebookId":"server-nb","position":0}"""
    private fun notebook(id: String = "server-nb") = """{"id":"$id","name":"Notebook","color":"#123456","createdAt":"$now","updatedAt":"$now"}"""
    private fun entity(id: String = "server-cap") = CaptureEntity(id, 1, 3, 27, 388, 1, null, "realtime", now, now, now, "Original")
    private fun input() = CreateCaptureInput(SobpDto(3, 27, 388, 1), "realtime", now, "Original", strokes = emptyList())

    @Test fun temporaryCaptureEditUsesAcknowledgedId() = runBlocking {
        repo.enqueueCreateCapture(input(), entity("local-cap"))
        repo.renameCapture("local-cap", "Offline edit")
        server.enqueue(json(capture()))
        server.enqueue(json(capture(name = "Offline edit")))
        val result = engine.push()
        assertTrue(result.toString(), result.ok)
        assertEquals(2, result.pushed)
        assertEquals("/api/captures", server.takeRequest().path)
        assertEquals("/api/captures/server-cap", server.takeRequest().path)
        assertTrue(repo.outboxPeek(20).isEmpty())
        assertNull(repo.capture("local-cap"))
        assertEquals("Offline edit", repo.capture("server-cap")!!.name)
    }

    @Test fun temporaryNotebookRepointsTargetsAndEmbeddedIds() = runBlocking {
        db.captures().upsertOne(entity())
        val temp = repo.createNotebook("Offline notebook", "#123456")
        repo.editNotebook(temp, name = "Renamed notebook")
        repo.moveCapture("server-cap", temp, 0)
        repo.enqueueCreateCapture(input().copy(notebookId = temp), entity("local-cap"))
        server.enqueue(json(notebook()))
        server.enqueue(json(notebook().replace("Notebook", "Renamed notebook")))
        server.enqueue(json(capture()))
        server.enqueue(json(capture("new-cap")))
        assertTrue(engine.push().ok)
        server.takeRequest()
        assertEquals("/api/notebooks/server-nb", server.takeRequest().path)
        repeat(2) {
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"notebookId\":\"server-nb\""))
            assertFalse(body.contains(temp))
        }
        assertNull(repo.notebook(temp))
    }

    @Test fun localModeDoesNotPushOrPullWithStoredCredentials() = runBlocking {
        settings.update(settings.flow.first().copy(appMode = AppMode.LOCAL))
        db.captures().upsertOne(entity())
        repo.renameCapture("server-cap", "Private local edit")
        assertTrue(engine.push().ok)
        assertTrue(engine.pull().ok)
        assertTrue(engine.sync().ok)
        assertEquals(0, server.requestCount)
        assertEquals(1, repo.outboxPeek(20).size)
        assertEquals("Private local edit", repo.capture("server-cap")!!.name)
    }

    @Test fun rateLimitRetainsEditAndHonoursRetryAfterAcrossEngineRestart() = runBlocking {
        db.captures().upsertOne(entity())
        repo.renameCapture("server-cap", "Offline edit")
        server.enqueue(json("{}", 429).setHeader("Retry-After", "60"))
        assertFalse(engine.sync().ok)
        assertEquals(1, server.requestCount) // no pull sobre el cambio pendiente
        assertEquals(1, repo.outboxPeek(20).size)
        assertEquals(1, repo.outboxPeek(20).single().attempts)
        val restarted = SyncEngine(repo, settings) { ApiFactory.create(it, client) }
        assertFalse(restarted.push().ok)
        assertEquals(1, server.requestCount)
        assertEquals("Offline edit", repo.capture("server-cap")!!.name)
    }

    @Test fun rejectedEditIsRetainedAndOptimisticStateSurvivesCreateAck() = runBlocking {
        repo.enqueueCreateCapture(input(), entity("local-cap"))
        repo.renameCapture("local-cap", "Offline edit")
        server.enqueue(json(capture()))
        server.enqueue(json("{}", 409))
        val result = engine.sync()
        assertFalse(result.ok)
        assertEquals(1, result.pushed)
        assertEquals("server-cap", repo.outboxPeek(20).single().targetId)
        assertEquals("Offline edit", repo.capture("server-cap")!!.name)
        assertEquals(2, server.requestCount)
    }

    @Test fun onlyDeleteTreats404AsSuccess() = runBlocking {
        repo.deleteCapture("already-gone")
        server.enqueue(json("{}", 404))
        assertTrue(engine.push().ok)
        db.captures().upsertOne(entity())
        repo.renameCapture("server-cap", "Pending")
        server.enqueue(json("{}", 404))
        assertFalse(engine.push().ok)
        assertEquals(1, repo.outboxPeek(20).size)
    }

    @Test fun explicitNullPaperSurvivesStorageAndNetworkWithoutOtherNulls() = runBlocking {
        db.captures().upsertOne(entity().copy(paperJson = Json.paperJson(PaperDto(0f, 0f, 40f, 60f))))
        repo.setCapturePaper("server-cap", null)
        assertEquals("{\"paper\":null}", repo.outboxPeek(1).single().payloadJson)
        server.enqueue(json(capture()))
        assertTrue(engine.push().ok)
        assertEquals("{\"paper\":null}", server.takeRequest().body.readUtf8())
        assertEquals("{\"name\":\"Rename\"}", Json.capturePatch.toJson(CapturePatchDto(name = "Rename")))
    }

    @Test fun webUnsignedArgbAndSignedArgbDecodeIdentically() {
        for (color in listOf("4294901760", "-65536")) {
            val body = """{"cursor":1,"captures":[${capture(color = color)}]}"""
            val dto = Json.moshi.adapter(ChangesResponseDto::class.java).fromJson(body)!!
            assertEquals(-65536, dto.captures.single().strokes.single().color)
        }
        assertTrue(runCatching {
            Json.moshi.adapter(StrokeDto::class.java).fromJson("""{"startTime":1,"endTime":2,"dots":[],"color":4294967296}""")
        }.isFailure)
    }

    @Test fun linkingPreservesFullMetadataAndRoomRows() = runBlocking {
        settings.update(settings.flow.first().copy(appMode = AppMode.LOCAL))
        db.notebooks().upsertOne(NotebookEntity("local-nb", 0, "Local notebook", "#123456", createdAt = now, updatedAt = now, bookKey = "3.27.388"))
        val ocr = OcrResultDto(title = "My title", text = "Unique local transcription")
        val local = entity("local-cap").copy(notebookId = "local-nb", destination = "notebook",
            transcriptionJson = Json.ocrJson(ocr), sealed = true, kind = "todo", position = 4,
            clonedFrom = "original", overlapJson = Json.overlapJson(OverlapDto(1, 2, 3, 4)))
        db.captures().upsertOne(local)
        db.captures().upsertOne(local.copy(id = "local-revision", position = 5))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        assertTrue(engine.linkAndMerge(server.url("/").toString(), "audit").ok)
        val notebookRequest = server.takeRequest()
        assertEquals("PUT", notebookRequest.method)
        assertEquals("/api/migration", notebookRequest.path)
        val migrated = (1..2).map { Json.migration.fromJson(server.takeRequest().body.readUtf8())!!.captures.single() }
        assertEquals(setOf("local-cap", "local-revision"), migrated.map { it.id }.toSet())
        migrated.forEach {
            assertEquals(ocr, it.transcription)
            assertTrue(it.sealed)
            assertEquals("todo", it.kind)
            assertTrue(it.position in 4..5)
            assertEquals("original", it.clonedFrom)
            assertEquals(OverlapDto(1, 2, 3, 4), it.overlap)
            assertEquals("local-nb", it.notebookId)
        }
        assertEquals(2, db.captures().getAll().size)
        assertEquals(1, db.notebooks().getAll().size)
        assertTrue(repo.outboxPeek(20).isEmpty())
    }

    @Test fun migrationFailureIsResumableWithStableIdsAndNoLocalDeletion() = runBlocking {
        settings.update(settings.flow.first().copy(appMode = AppMode.LOCAL))
        db.captures().upsertOne(entity("local-one"))
        db.captures().upsertOne(entity("local-two"))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(json("{}", 503))
        assertFalse(engine.linkAndMerge(server.url("/").toString(), "audit").ok)
        server.takeRequest()
        val failedId = Json.migration.fromJson(server.takeRequest().body.readUtf8())!!.captures.single().id
        assertEquals(2, db.captures().getAll().size)
        assertEquals(1, repo.outboxPeek(20).size)
        assertTrue(engine.push().ok) // LOCAL no reintenta automáticamente
        assertEquals(2, server.requestCount)
        repo.renameCapture(failedId, "Edited after failure")
        repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
        assertTrue(engine.linkAndMerge(server.url("/").toString(), "audit").ok)
        val retried = (1..2).map { Json.migration.fromJson(server.takeRequest().body.readUtf8())!!.captures.single() }
        assertEquals(setOf("local-one", "local-two"), retried.map { it.id }.toSet())
        assertEquals("Edited after failure", retried.single { it.id == failedId }.name)
        assertEquals(2, db.captures().getAll().size)
    }

    @Test fun oldServerCannotSilentlyDiscardMigrationMetadata() = runBlocking {
        db.captures().upsertOne(entity("local-cap"))
        server.enqueue(json("{}", 404))
        assertFalse(engine.linkAndMerge(server.url("/").toString(), "audit").ok)
        assertNotNull(repo.capture("local-cap"))
        assertEquals(1, repo.outboxPeek(20).size)
    }

    @Test fun concurrentPushesDoNotUploadTheSameOperationTwice() = runBlocking {
        db.captures().upsertOne(entity())
        repo.renameCapture("server-cap", "Pending")
        server.enqueue(json(capture(name = "Pending")).setBodyDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        kotlinx.coroutines.coroutineScope {
            val first = async { engine.push() }
            val second = async { engine.push() }
            assertTrue(first.await().ok)
            assertTrue(second.await().ok)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun reorderPayloadUsesRealCaptureIdAfterCreate() = runBlocking {
        repo.enqueueCreateCapture(input(), entity("local-cap"))
        repo.reorderNotebook("server-nb", listOf("local-cap"))
        server.enqueue(json(capture()))
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(engine.push().ok)
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/notebooks/server-nb/order", request.path)
        assertEquals("{\"order\":[\"server-cap\"]}", request.body.readUtf8())
    }

    @Test fun unsignedWebColorDoesNotBlockHttpDeltaCursor() = runBlocking {
        server.enqueue(json("""{"cursor":7,"captures":[${capture(color = "4294901760")}]}"""))
        server.enqueue(json(notebook()))
        server.enqueue(json("[]"))
        server.enqueue(json("{}"))
        val result = engine.pull()
        assertTrue(result.toString(), result.ok)
        assertEquals(7L, repo.cursor())
        assertEquals(-65536, repo.capture("server-cap")!!.strokes.single().color)
    }

    @Test fun remoteResetCannotEraseAnEditQueuedDuringPull() = runBlocking {
        db.captures().upsertOne(entity())
        repo.renameCapture("server-cap", "Pending")
        assertTrue(runCatching {
            repo.applyChanges(ChangesResponseDto(cursor = 0, reset = true), System.currentTimeMillis())
        }.isFailure)
        assertEquals("Pending", repo.capture("server-cap")!!.name)
        assertEquals(1, repo.outboxPeek(20).size)
        assertEquals(0L, repo.cursor())
    }
}
