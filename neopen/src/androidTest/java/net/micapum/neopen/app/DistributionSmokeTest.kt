package net.micapum.neopen.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.micapum.neopen.app.data.Json
import net.micapum.neopen.app.data.NeopenRepository
import net.micapum.neopen.app.data.StudioJson
import net.micapum.neopen.app.data.toDto
import net.micapum.neopen.app.data.toEntity
import net.micapum.neopen.app.db.NeopenDb
import net.micapum.neopen.app.render.PagePreviewCache
import net.micapum.neopen.app.render.PreviewAssetStore
import net.micapum.neopen.app.render.rasterizePng
import net.micapum.neopen.app.render.renderPagePdf
import net.micapum.neopen.app.render.renderSvg
import net.micapum.neopen.core.capability.WebdavConnector
import net.micapum.neopen.core.data.CaptureDto
import net.micapum.neopen.core.data.StudioBookDto
import net.micapum.neopen.core.data.WebdavLocalConfig
import net.micapum.neopen.core.data.apiMoshi
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Se ejecuta también sobre github: detecta pérdidas de reflexión/JSON en el APK con R8. */
@RunWith(AndroidJUnit4::class)
class DistributionSmokeTest {
    private val captureJson = """{
        "id":"distribution-capture","rev":9,"sobp":{"section":3,"owner":27,"book":512,"page":1},
        "source":"realtime","capturedAt":"2026-11-12T10:00:00Z","importedAt":"2026-11-12T10:00:01Z",
        "updatedAt":"2026-11-12T10:00:01Z","name":"Smoke page","tags":["preserved"],
        "strokes":[{"startTime":1,"endTime":2,"color":4294901760,"width":2.5,"penId":"pen-a",
        "dots":[{"x":1,"y":2,"f":0.2,"t":1},{"x":3,"y":4,"f":0.9,"t":2}]}],
        "paper":{"x0":0,"y0":0,"width":70,"height":100,"source":"a5"},
        "transcription":{"title":"OCR title","text":"Retained OCR text","language":"es","tags":["ocr"],"todos":[{"text":"Task","done":false}]},
        "penId":"pen-a","bookInstanceId":"epoch-a","assignmentId":"binding-a","assignmentRevision":7
    }""".trimIndent()

    @Test fun existingCaptureRoomAndExplicitNullOutboxSurviveTheMinifiedApk() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "distribution-smoke-${UUID.randomUUID()}.db"
        val dto = requireNotNull(apiMoshi().adapter(CaptureDto::class.java).fromJson(captureJson))
        var database = Room.databaseBuilder(context, NeopenDb::class.java, name).build()
        try {
            database.captures().upsertOne(dto.toEntity())
            database.close()
            database = Room.databaseBuilder(context, NeopenDb::class.java, name).build()
            val repo = NeopenRepository(database)
            val restored = requireNotNull(database.captures().getById(dto.id)).toDto()
            assertEquals(dto, restored)
            assertEquals(0xffff0000.toInt(), restored.strokes.single().color)
            repo.setCapturePaper(dto.id, null)
            val operation = repo.outboxPeek(1).single()
            assertTrue(operation.payloadJson, operation.payloadJson.contains("\"paper\":null"))
            val patch = requireNotNull(Json.capturePatch.fromJson(operation.payloadJson))
            assertTrue(patch.paperSpecified)
            assertNull(patch.paper)
            assertNull(patch.name)
            val capture = requireNotNull(repo.capture(dto.id))
            assertEquals(2, capture.strokes.single().dots.size)
            assertEquals("Retained OCR text", capture.transcription?.text)
            assertEquals("epoch-a", capture.bookInstanceId)
            assertEquals(7L, capture.assignmentRevision)
        } finally {
            database.close(); context.deleteDatabase(name)
        }
    }

    @Test fun mixedStudioRecipeAndPendingMutationRoundTripWithReflection() = runBlocking {
        val json = """{"id":"distribution-book","rev":3,"name":"Mixed book","profileId":"s3-o27-book-low6-v1",
            "section":3,"owner":27,"book":512,"format":"a5","recipe":{"version":1,"format":"a5",
            "sections":[{"id":"blank","kind":"template","config":{"template":"blank","pageCount":2}},
            {"id":"pdf","kind":"pdf","assetId":"original","fromPage":2,"toPage":3}],
            "borderSeed":4294967295},"pageCount":4,"status":"ready","createdAt":"now","updatedAt":"now",
            "instanceId":"epoch-a","assignmentId":"binding-a","assignmentRevision":7} """.trimIndent()
        val book = requireNotNull(StudioJson.book.fromJson(json))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeopenDb::class.java).build()
        try {
            val repo = NeopenRepository(database)
            repo.studio.apply(listOf(book), emptyList(), emptyList())
            val cached = repo.studio.books().first().single().book
            assertEquals(book, cached)
            assertEquals("blank", cached.recipe.sections.first().config?.get("template"))
            assertEquals(4294967295L, cached.recipe.borderSeed)
            repo.studio.patch(cached, name = "New label")
            val operation = repo.outboxPeek(1).single()
            assertEquals("New label", StudioJson.patch.fromJson(operation.payloadJson)?.name)
            assertEquals(book, requireNotNull(apiMoshi().adapter(StudioBookDto::class.java).fromJson(StudioJson.book.toJson(cached))))
        } finally { database.close() }
    }

    @Test fun fullResolutionExportsAndSizedPdfPreviewsRemainAvailable() = runBlocking {
        val dto = requireNotNull(apiMoshi().adapter(CaptureDto::class.java).fromJson(captureJson))
        val png = rasterizePng(dto.strokes, dto.paper, dpiScale = .25f, pressureWidthEnabled = true)
        assertEquals(listOf(137, 80, 78, 71), png.take(4).map { it.toInt() and 255 })
        assertTrue(renderSvg(dto.strokes, dto.paper, pressureWidthEnabled = true).contains("#FF0000"))
        val pdf = renderPagePdf(dto.strokes, dto.paper, pressureWidthEnabled = true)
        assertTrue(String(pdf.copyOfRange(0, 4), Charsets.US_ASCII) == "%PDF")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("distribution-export-", ".pdf", context.cacheDir)
        try {
            file.writeBytes(pdf)
            val store = PreviewAssetStore(context)
            val first = requireNotNull(store.render(file, 0, 96))
            assertEquals(96, first.width)
            assertSame(first, store.render(file, 0, 96))
            assertNull(store.render(file, 9, 96))
        } finally { file.delete(); PagePreviewCache.clear() }
    }

    @Test fun sftpAlgorithmsConfiguredByNameRemainInstantiable() {
        // JSch resuelve estos nombres por reflexión al abrir una sesión SFTP.
        val jsch = Class.forName("com.jcraft.jsch.JSch")
        val getConfig = jsch.getMethod("getConfig", String::class.java)
        for (algorithm in listOf("aes128-ctr", "hmac-sha2-256", "diffie-hellman-group14-sha256")) {
            val className = getConfig.invoke(null, algorithm) as String
            // Algunos algoritmos son package-private; JSch los construye desde su propio paquete.
            val constructor = Class.forName(className).getDeclaredConstructor().apply { isAccessible = true }
            assertNotNull(constructor.newInstance())
        }
    }

    @Test fun webdavUploadsAndDeletesAnExportOverHttps() = runBlocking<Unit> {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
            val connector = WebdavConnector(WebdavLocalConfig(server.url("/").toString(), "writer", "secret", "/notes"), client)
            repeat(4) { server.enqueue(MockResponse().setResponseCode(201)) }
            val dto = requireNotNull(apiMoshi().adapter(CaptureDto::class.java).fromJson(captureJson))
            val export = renderSvg(dto.strokes, dto.paper).toByteArray()
            val path = connector.putPath("book/page.svg", export, "image/svg+xml")
            assertEquals("/notes/book/page.svg", path)
            connector.delete(path)
            val requests = List(4) { requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            assertEquals(listOf("MKCOL", "MKCOL", "PUT", "DELETE"), requests.map { it.method })
            assertEquals("/notes/book/page.svg", requests[2].path)
            assertEquals(Credentials.basic("writer", "secret"), requests[2].getHeader("Authorization"))
            assertArrayEquals(export, requests[2].body.readByteArray())
        } finally { server.shutdown() }
    }
}
