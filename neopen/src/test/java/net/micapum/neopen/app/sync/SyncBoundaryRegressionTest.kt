package net.micapum.neopen.app.sync

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.micapum.neopen.app.data.Json
import net.micapum.neopen.app.data.NeopenRepository
import net.micapum.neopen.app.data.OutboxKind
import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.db.NotebookEntity
import net.micapum.neopen.app.db.OfflineNoteStateEntity
import net.micapum.neopen.core.data.*
import net.micapum.neopen.core.pen.Dot
import net.micapum.neopen.core.pen.Sobp
import net.micapum.neopen.core.pen.Stroke
import net.micapum.neopen.core.pen.StrokePage
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncBoundaryRegressionTest {
    private lateinit var context: Context
    private lateinit var db: NeopenDb
    private lateinit var repo: NeopenRepository
    private lateinit var settings: SettingsStore
    private lateinit var server: MockWebServer
    private lateinit var engine: SyncEngine
    private val timestamp = "2026-11-12T10:00:00Z"

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.dataStore.edit { it.clear() }
        db = Room.inMemoryDatabaseBuilder(context, NeopenDb::class.java).allowMainThreadQueries().build()
        repo = NeopenRepository(db)
        settings = SettingsStore(context)
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCert = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager).build()
        server = MockWebServer().apply { useHttps(serverCert.sslSocketFactory(), false); start() }
        settings.update(AppSettings(serverUrl = server.url("/").toString(), password = "audit", setupComplete = true))
        engine = SyncEngine(repo, settings) { url -> object : NeopenApi by ApiFactory.create(url, client) {
            override suspend fun capabilitiesInfo(password: String) = CapabilitiesDto(StudioCapDto(true))
        } }
    }

    @After fun teardown() = runBlocking {
        db.close()
        server.shutdown()
        context.dataStore.edit { it.clear() }
        Unit
    }

    private fun entity() = CaptureEntity("local-cap", 0, 3, 27, 388, 1, "Local", "realtime", timestamp, timestamp, timestamp, "Original",
        strokesJson = Json.strokesJson(listOf(StrokeDto(1, 2, listOf(DotDto(1f, 1f, 0.5f))))), destination = "notebook", notebookId = "local-nb")
    private fun notebook() = NotebookDto("local-nb", name = "Local", color = "#123456", createdAt = timestamp, updatedAt = timestamp, bookKey = "3.27.388")
    private fun page(source: String = "realtime") = StrokePage(Sobp(3, 27, 388, 1), source, timestamp,
        listOf(Stroke(3, 4, listOf(Dot(2f, 2f, 0.5f, 3, 0)))))
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private suspend fun seedLocal() {
        db.notebooks().upsertOne(NotebookEntity("local-nb", 0, "Local", "#123456", createdAt = timestamp, updatedAt = timestamp, bookKey = "3.27.388"))
        db.captures().upsertOne(entity())
    }

    @Test fun migrationQueuesConcurrentInkUsingTheNewServerModeBeforeFirstPull() = runBlocking {
        seedLocal()
        settings.update(settings.flow.first().copy(appMode = AppMode.LOCAL))
        val migrationReached = CompletableDeferred<Unit>()
        val inkAttempted = CompletableDeferred<Unit>()
        var remote: CaptureDto? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/migration") {
                    val payload = requireNotNull(Json.migration.fromJson(request.body.readUtf8()))
                    if (payload.captures.isNotEmpty()) {
                        remote = payload.captures.single()
                        migrationReached.complete(Unit)
                        runBlocking { withTimeout(5000) { inkAttempted.await() } }
                    }
                    return MockResponse().setResponseCode(204)
                }
                if (request.path == "/api/captures") {
                    val input = requireNotNull(Json.createCapture.fromJson(request.body.readUtf8()))
                    remote = requireNotNull(remote).copy(strokes = requireNotNull(remote).strokes + input.strokes)
                    return json(Json.moshi.adapter(CaptureDto::class.java).toJson(remote))
                }
                return when {
                    request.path?.startsWith("/api/changes?") == true -> json(Json.moshi.adapter(ChangesResponseDto::class.java).toJson(
                        ChangesResponseDto(cursor = 42, captures = listOf(requireNotNull(remote)), notebooks = listOf(notebook()))))
                    request.path == "/api/notebooks/unclassified" -> json(Json.moshi.adapter(NotebookDto::class.java).toJson(notebook()))
                    request.path == "/api/anchors" -> json("[]")
                    request.path == "/api/render-settings" -> json("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val writing = launch {
            migrationReached.await()
            inkAttempted.complete(Unit)
            repo.persistPenPage(page(), OfflineReimportPolicy.ASK) { settings.flow.first().appMode == AppMode.LOCAL }
        }
        assertTrue(engine.linkAndMerge(server.url("/").toString(), "audit").ok)
        writing.join()
        assertEquals(AppMode.FULL, settings.flow.first().appMode)
        assertEquals(1, repo.outboxPeek(100).size)
        assertTrue(engine.sync().ok)
        assertEquals("Los trazos capturados durante la migración sobreviven al primer pull", 2, requireNotNull(repo.capture("local-cap")).strokes.size)
        assertTrue(repo.outboxPeek(100).isEmpty())
    }

    @Test fun migrationFailureAndCancellationReleaseTheInkGateWithoutLeavingLocalMode() = runBlocking {
        seedLocal()
        settings.update(settings.flow.first().copy(appMode = AppMode.LOCAL))
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(engine.linkAndMerge(server.url("/").toString(), "audit").ok)
        withTimeout(3000) { repo.ingestPenPageLocal(page()) }
        assertEquals(2, requireNotNull(repo.capture("local-cap")).strokes.size)
        assertEquals(AppMode.LOCAL, settings.flow.first().appMode)
        server.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(1, TimeUnit.SECONDS))
        val linking = launch { engine.linkAndMerge(server.url("/").toString(), "audit") }
        withTimeout(3000) { while (server.requestCount < 2) kotlinx.coroutines.yield() }
        linking.cancelAndJoin()
        withTimeout(3000) { repo.ingestPenPageLocal(page()) }
        assertEquals(AppMode.LOCAL, settings.flow.first().appMode)
    }

    @Test fun changingServerWithPendingWritesIsRejectedWithoutDiscardingAnything() = runBlocking {
        seedLocal()
        repo.renameCapture("local-cap", "Pendiente")
        val before = settings.flow.first()
        val result = engine.updateSettings(before.copy(serverUrl = "https://other.example/"))
        assertFalse(result.ok)
        assertEquals(SyncEngine.SERVER_CHANGE_BLOCKED, result.error)
        assertEquals(before.serverUrl, settings.flow.first().serverUrl)
        assertEquals("Pendiente", requireNotNull(repo.capture("local-cap")).name)
        assertEquals(1, repo.outboxPeek(100).size)
        assertEquals(0, server.requestCount)
    }

    @Test fun bypassingTheFormCannotSendOldOutboxToAnotherServerEvenAfterRestart() = runBlocking {
        seedLocal()
        settings.bindSyncServer(settings.flow.first().serverUrl)
        repo.renameCapture("local-cap", "Pendiente")
        repo.applyServerCapture(Json.moshi.adapter(CaptureDto::class.java).fromJson("""{"id":"other","sobp":{"section":1,"owner":1,"book":1,"page":1},"source":"realtime","capturedAt":"$timestamp","importedAt":"$timestamp","name":"Other"}""")!!)
        settings.update(settings.flow.first().copy(serverUrl = "https://other.example/"))
        assertFalse(engine.push().ok)
        assertFalse(engine.pull().ok)
        val restarted = SyncEngine(repo, settings) { error("No debe construir ningún cliente del destino incorrecto") }
        assertFalse(restarted.sync().ok)
        assertEquals(1, repo.outboxPeek(100).size)
        assertEquals(0, server.requestCount)
    }

    @Test fun emptyLibraryCanSwitchServerAndResetsCursorCountersAndRenderCache() = runBlocking {
        val before = settings.flow.first()
        settings.bindSyncServer(before.serverUrl)
        repo.applyChanges(ChangesResponseDto(cursor = 77), 1)
        db.offlineNoteState().upsert(OfflineNoteStateEntity("AA", 3, 27, 388, 42, 1))
        assertTrue(engine.updateSettings(before.copy(serverUrl = "https://other.example/", serverPressureWidthEnabled = true, serverPressureWidthContrast = 3f)).ok)
        assertEquals(0L, repo.cursor())
        assertTrue(db.offlineNoteState().forPen("AA").isEmpty())
        assertNull(settings.flow.first().serverPressureWidthEnabled)
        assertNull(settings.flow.first().serverPressureWidthContrast)
        assertEquals("https://other.example", settings.flow.first().syncServerUrl)
    }

    @Test fun equivalentUrlAndPasswordChangeKeepTheExistingLibraryAndBinding() = runBlocking {
        seedLocal()
        repo.renameCapture("local-cap", "Pendiente")
        val before = settings.flow.first()
        settings.bindSyncServer(before.serverUrl)
        assertTrue(engine.updateSettings(before.copy(serverUrl = before.serverUrl.replace("localhost", "LOCALHOST").trimEnd('/'), password = "new-password")).ok)
        assertEquals(1, repo.outboxPeek(100).size)
        assertNotNull(repo.capture("local-cap"))
        assertEquals(normalizedServerUrl(before.serverUrl), settings.flow.first().syncServerUrl)
        assertEquals("new-password", settings.flow.first().password)
    }

    @Test fun reimportNewAndMergeCarryExplicitServerSemanticsAndRepointTargets() = runBlocking {
        seedLocal()
        repo.moveCapture("local-cap", "local-nb", 0)
        repo.outboxPeek(100).forEach { repo.outboxDelete(it.localId) }
        repo.ingestPenPage(page("offline"), OfflineReimportPolicy.NEW_CAPTURE)
        val newPayload = Json.createCapture.fromJson(repo.outboxPeek(100).single().payloadJson)!!
        assertEquals("forceNew", newPayload.mergeMode)
        repo.outboxPeek(100).forEach { repo.outboxDelete(it.localId) }
        // Otro timestamp evita la huella del aviso offline ya resuelto.
        val another = page("offline").copy(strokes = listOf(Stroke(5, 6, listOf(Dot(3f, 3f, 0.5f, 5, 0)))))
        repo.ingestPenPage(another, OfflineReimportPolicy.MERGE)
        val merge = repo.outboxPeek(100).single()
        assertEquals(OutboxKind.CREATE_CAPTURE, merge.kind)
        assertEquals("mergeTarget", Json.createCapture.fromJson(merge.payloadJson)!!.mergeMode)
        assertEquals("local-cap", Json.createCapture.fromJson(merge.payloadJson)!!.targetCaptureId)
        repo.outboxRepoint("local-cap", "server-cap")
        assertEquals("server-cap", Json.createCapture.fromJson(repo.outboxPeek(100).single().payloadJson)!!.targetCaptureId)
    }

    @Test fun clearingLocalDataAlsoClearsPersistedAndInMemoryOfflineGate() = runBlocking {
        seedLocal()
        db.offlineNoteState().upsert(OfflineNoteStateEntity("AA", 3, 27, 388, 42, 1))
        var inMemoryCounts = mapOf("3.27.388" to 42)
        repo.onLocalCleared = { inMemoryCounts = emptyMap() }
        assertEquals(1, db.offlineNoteState().observeAll().first().size)
        repo.clearLocal()
        assertTrue(db.offlineNoteState().forPen("AA").isEmpty())
        assertTrue(db.offlineNoteState().observeAll().first().isEmpty())
        assertTrue(inMemoryCounts.isEmpty())
        assertNull(repo.capture("local-cap"))
    }
}
