package net.micapum.neopen.benchmarks

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cada escenario usa el APK aislado no depurable, sin conexión a un servidor real. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class LoadingBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val args get() = InstrumentationRegistry.getArguments()
    private val iterations get() = (args.getString("iterations")?.toIntOrNull() ?: 10).coerceIn(1, 50)
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun coldStart11Notes() = startup(StartupMode.COLD)
    @Test fun warmStart11Notes() = startup(StartupMode.WARM)

    private fun startup(mode: StartupMode) {
        seed("11")
        benchmark.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = CompilationMode.Partial(warmupIterations = 3),
            startupMode = mode,
            iterations = iterations,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait(mainIntent())
            list("inbox-list")
        }
    }

    @Test fun scroll11Notes() = scroll("11", false)
    @Test fun scroll512PageNotebook() = scroll("512", true)

    private fun scroll(dataset: String, notebook: Boolean) {
        seed(dataset)
        val tag = if (notebook) "notebook-pages" else "inbox-list"
        benchmark.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = CompilationMode.Partial(warmupIterations = 3),
            iterations = iterations,
            setupBlock = {
                startActivityAndWait(mainIntent(notebook))
                // Una pasada fuera de la medida prepara las miniaturas visibles y sus shaders.
                list(tag).scroll(Direction.DOWN, .8f)
                list(tag).scroll(Direction.UP, .8f)
                device.waitForIdle()
            },
        ) {
            repeat(3) { list(tag).scroll(Direction.DOWN, .8f) }
            repeat(3) { list(tag).scroll(Direction.UP, .8f) }
            device.waitForIdle()
        }
    }

    private fun seed(dataset: String) {
        for (permission in listOf("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "POST_NOTIFICATIONS")) {
            device.executeShellCommand("pm grant $PACKAGE android.permission.$permission")
        }
        device.executeShellCommand("am force-stop $PACKAGE")
        device.executeShellCommand("am start -W -n $PACKAGE/net.micapum.neopen.app.benchmark.BenchmarkSeedActivity --es dataset $dataset")
        check(device.wait(Until.hasObject(By.text("benchmark-ready-$dataset")), 60_000)) { "No se ha preparado el dataset $dataset" }
        device.pressHome()
    }

    private fun list(tag: String): UiObject2 {
        // El fallback es inmediato: esperar un tag ausente en la referencia sesgaría la medida.
        fun findList(current: UiDevice): UiObject2? {
            val fixtureNote = By.textStartsWith("Benchmark note ")
            current.findObject(By.res(tag))?.takeIf { it.hasObject(fixtureNote) }?.let { return it }
            // También evita elegir el scroll del wizard o del campo de búsqueda durante la carga.
            return current.findObjects(By.scrollable(true)).firstOrNull { it.hasObject(fixtureNote) }
        }
        findList(device)?.let { return it }
        return device.wait(Condition<UiDevice, UiObject2?> { findList(it) }, 10_000)
            ?: error("No se ha cargado la lista de fixtures $tag")
    }

    private fun mainIntent(notebook: Boolean = false) = Intent(Intent.ACTION_MAIN).apply {
        setClassName(PACKAGE, "net.micapum.neopen.app.MainActivity")
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (notebook) {
            putExtra("open_tab", "notebooks")
            putExtra("open_notebook", "benchmark-notebook")
        }
    }

    companion object { private const val PACKAGE = "net.micapum.neopen.benchmark" }
}
