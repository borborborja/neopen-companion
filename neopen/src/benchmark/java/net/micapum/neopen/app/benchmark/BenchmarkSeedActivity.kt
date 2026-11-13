package net.micapum.neopen.app.benchmark

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.TextView
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.micapum.neopen.app.data.Json
import net.micapum.neopen.app.db.CaptureEntity
import net.micapum.neopen.app.db.NotebookEntity
import net.micapum.neopen.app.db.PageBackgroundEntity
import net.micapum.neopen.app.graph
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.DotDto
import net.micapum.neopen.core.data.OcrResultDto
import net.micapum.neopen.core.data.PaperDto
import net.micapum.neopen.core.data.StrokeDto
import net.micapum.neopen.core.data.SyncMode
import java.io.File
import java.time.Instant
import kotlin.math.sin

/** Datos deterministas únicamente para la variante .benchmark, con biblioteca independiente. */
class BenchmarkSeedActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "net.micapum.neopen.benchmark")
        val text = TextView(this).apply { setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE); text = "benchmark-seeding" }
        setContentView(text)
        val dataset = intent.getStringExtra("dataset") ?: "11"
        scope.launch {
            try {
                seed(dataset)
                withContext(Dispatchers.Main) { text.text = "benchmark-ready-$dataset" }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { text.text = "benchmark-failed-${error.javaClass.simpleName}" }
            }
        }
    }

    private suspend fun seed(dataset: String) {
        val count = when (dataset) { "11" -> 11; "512" -> 512; else -> error("dataset") }
        val appGraph = application.graph
        appGraph.repo.clearLocal()
        appGraph.settings.update(AppSettings(
            serverUrl = "https://127.0.0.1:1", syncServerUrl = "https://127.0.0.1:1", password = "benchmark-only",
            setupComplete = true, appMode = AppMode.FULL, syncMode = SyncMode.MANUAL,
            recentShowCount = 20, notebookView = "List", themeMode = "light", pressureWidthEnabled = true,
            serverPressureWidthEnabled = true, serverPressureWidthContrast = 2f,
        ))
        val now = "2026-11-12T12:00:00Z"
        val paper = Json.paperJson(PaperDto(0f, 0f, 62f, 88f, "benchmark"))
        val rows = List(count) { index ->
            val points = if (count == 512) 200 else when (index) { 10 -> 15_000; 8, 9 -> 6_000; else -> 1_000 }
            val imported = Instant.parse(now).plusMillis(index.toLong()).toString()
            val ink = Json.strokesJson(strokes(points))
            // Una fila mayor que CursorWindow impediría abrir la fixture en ambas versiones.
            check(ink.toByteArray(Charsets.UTF_8).size < 1_500_000) { "Fixture demasiado grande para CursorWindow" }
            CaptureEntity(
                id = "benchmark-page-$index", rev = 1, section = 3, owner = 27, book = 512, page = index,
                notebook = "Benchmark", source = "offline", capturedAt = now, importedAt = imported,
                updatedAt = imported, name = "Benchmark note ${index.toString().padStart(3, '0')}",
                tagsJson = Json.tagsJson(listOf("synthetic", "points-$points")),
                strokesJson = ink,
                transcriptionJson = Json.ocrJson(OcrResultDto(title = "Synthetic note $index", text = "Searchable benchmark text")),
                destination = "notebook", notebookId = "benchmark-notebook", position = index, paperJson = paper,
            )
        }
        val image = createBackground()
        appGraph.db.withTransaction {
            appGraph.db.notebooks().upsertOne(NotebookEntity(
                id = "benchmark-notebook", rev = 1, name = "Benchmark $count pages", color = "#64748b",
                createdAt = now, updatedAt = now, paperJson = paper, bookKey = "3.27.512",
            ))
            appGraph.db.captures().upsert(rows)
            appGraph.db.backgrounds().upsert(PageBackgroundEntity(
                id = "benchmark-background", bookKey = "3.27.512", scope = "book", bgPreset = "lined", createdAt = now,
            ))
            appGraph.db.backgrounds().upsert(PageBackgroundEntity(
                id = "benchmark-image", bookKey = "3.27.512", page = count - 1, scope = "page",
                bgImage = "file://${image.absolutePath}", createdAt = now,
            ))
        }
    }

    private fun strokes(points: Int): List<StrokeDto> = (0 until points step 50).map { start ->
        StrokeDto(start.toLong(), (start + 49).toLong(), List(minOf(50, points - start)) { offset ->
            val i = start + offset
            DotDto((i % 100) * .5f, ((i / 100) % 150) * .5f + sin(i * .15).toFloat() * .1f, (i % 97 + 1) / 98f, i.toLong())
        })
    }

    private fun createBackground(): File {
        val file = File(filesDir, "benchmark-background.png")
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.LTGRAY; strokeWidth = 2f }
            for (y in 30 until 800 step 30) canvas.drawLine(0f, y.toFloat(), 600f, y.toFloat(), paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        return file
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
