package net.micapum.neopen.app.sync

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import net.micapum.neopen.core.pen.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PenSessionPersistenceTest {
    private lateinit var pen: PenManager
    private val pages = mutableListOf<StrokePage>()
    private val firstMac = "AA:BB:CC:DD:EE:01"

    @Before fun setup() {
        pen = PenManager(ApplicationProvider.getApplicationContext()) { pages.add(it) }
        setState(PenState(status = PenStatus.AUTHORIZED, mac = firstMac))
    }

    @Suppress("UNCHECKED_CAST")
    private fun setState(value: PenState) {
        val field = PenManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (field.get(pen) as MutableStateFlow<PenState>).value = value
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeStroke(page: Int = 1, complete: Boolean = true) {
        PenManager::class.java.getDeclaredField("currentSobp").apply { isAccessible = true }
            .set(pen, Sobp(3, 27, 388, page))
        PenManager::class.java.getDeclaredMethod("beginStroke", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(pen, 1L)
        val dots = PenManager::class.java.getDeclaredField("strokeDots").apply { isAccessible = true }
            .get(pen) as MutableList<Dot>
        dots.add(Dot(1f, 2f, .5f, 2L, Protocol.DOT_MOVE))
        if (complete) PenManager::class.java.getDeclaredMethod("endStroke")
            .apply { isAccessible = true }.invoke(pen)
    }

    @Test fun disconnectDeliversPendingPageExactlyOnceBeforeIdleTimeout() {
        writeStroke()
        assertTrue(pages.isEmpty())
        pen.disconnect()
        assertEquals(1, pages.size)
        assertEquals(firstMac, pages.single().penId)
        assertEquals(1, pages.single().strokes.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        pen.disconnect()
        assertEquals(1, pages.size)
        assertEquals(0, pen.state.value.capturedPages)
    }

    @Test fun disconnectAlsoRetainsDotsWithoutTheFinalPenUpEvent() {
        writeStroke(complete = false)
        pen.disconnect()
        assertEquals(1, pages.single().strokes.size)
        assertEquals(1, pages.single().strokes.single().dots.size)
    }

    @Test fun switchingPensClosesTheOriginalPageWithItsOriginalMac() {
        writeStroke()
        val secondMac = "AA:BB:CC:DD:EE:02"
        val next = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(secondMac)
        PenManager::class.java.getDeclaredMethod("connect", BluetoothDevice::class.java)
            .apply { isAccessible = true }.invoke(pen, next)
        assertEquals(firstMac, pages.single().penId)
        setState(PenState(status = PenStatus.AUTHORIZED, mac = secondMac))
        writeStroke(page = 2)
        pen.disconnect()
        assertEquals(listOf(firstMac, secondMac), pages.map { it.penId })
        assertEquals(listOf(1, 2), pages.map { it.sobp.page })
        assertEquals(listOf(1, 1), pages.map { it.strokes.size })
    }

    @Test fun transportFailurePersistsInkBeforeRetrying() {
        writeStroke()
        PenManager::class.java.getDeclaredMethod("retryOrFail").apply { isAccessible = true }.invoke(pen)
        assertEquals(firstMac, pages.single().penId)
        pen.disconnect()
        assertEquals(1, pages.size)
    }
}
