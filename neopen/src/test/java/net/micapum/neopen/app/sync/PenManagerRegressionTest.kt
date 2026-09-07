package net.micapum.neopen.app.sync

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import net.micapum.neopen.core.pen.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Ejercita el gestor real y sus temporizadores, sin afirmar que simula el radio BLE. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PenManagerRegressionTest {
    private lateinit var pen: PenManager
    private var completed = 0

    @Before fun setup() {
        pen = PenManager(ApplicationProvider.getApplicationContext()) { }
        setState(PenState(status = PenStatus.AUTHORIZED, settings = PenSettings(beep = false)))
        pen.onOfflineImportComplete = { completed++ }
    }

    @After fun cleanup() { pen.disconnect() }

    @Suppress("UNCHECKED_CAST")
    private fun setState(value: PenState) {
        val field = PenManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (field.get(pen) as MutableStateFlow<PenState>).value = value
    }

    private fun frame(cmd: Int, data: ByteArray, result: Int = 0) {
        PenManager::class.java.getDeclaredMethod("onFrame", Protocol.Frame::class.java)
            .apply { isAccessible = true }.invoke(pen, Protocol.Frame(cmd, result, data))
    }

    @Test fun importingBeforeTheListArrivesDoesNotReportEmptySuccess() {
        pen.listOffline()
        pen.importAllOffline()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OfflinePhase.LISTING, pen.state.value.offlinePhase)
        assertEquals(0, completed)
    }

    @Test fun anotherImportCannotReplaceANoteAlreadyBeingTransferred() {
        val first = OfflineNote(3, 27, 1)
        pen.importOffline(first)
        pen.importOffline(OfflineNote(3, 27, 2))
        val field = PenManager::class.java.getDeclaredField("currentNote").apply { isAccessible = true }
        assertEquals(first, field.get(pen))
        assertEquals(OfflinePhase.TRANSFERRING, pen.state.value.offlinePhase)
        assertEquals(0, completed)
    }

    @Test fun workerJoinsAListAndWaitsForItsResponse() = runBlocking {
        pen.listOffline()
        val result = async(start = CoroutineStart.UNDISPATCHED) { pen.importOfflineMemory() }
        assertFalse(result.isCompleted)
        frame(Protocol.OFFLINE_NOTE_LIST_RESPONSE, byteArrayOf(0, 0))
        assertTrue(result.await())
        assertEquals(1, completed)
    }

    @Test fun nonEmptyListNeverCompletesWorkerBeforeTheTransfer() = runBlocking {
        val result = async(start = CoroutineStart.UNDISPATCHED) { pen.importOfflineMemory() }
        frame(Protocol.OFFLINE_NOTE_LIST_RESPONSE, byteArrayOf(1, 0, 27, 0, 0, 3, 1, 0, 0, 0))
        yield()
        assertEquals(OfflinePhase.TRANSFERRING, pen.state.value.offlinePhase)
        assertFalse(result.isCompleted)
        pen.disconnect()
        assertFalse(result.await())
    }

    @Test fun listTimeoutDoesNotReportSuccessOrAcceptALateResponse() {
        pen.listOffline()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertFalse(pen.state.value.connected)
        assertEquals("offline-list-timeout", pen.state.value.error)
        frame(Protocol.OFFLINE_NOTE_LIST_RESPONSE, byteArrayOf(0, 0))
        assertNull(pen.state.value.notes)
        assertEquals(0, completed)
    }

    @Test fun noteTimeoutDoesNotMarkIncompleteImportAsSuccessful() {
        pen.importOffline(OfflineNote(3, 27, 1))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals("offline-data-timeout", pen.state.value.error)
        assertEquals(0, completed)
    }

    @Test fun settingTimeoutKeepsConfirmedValueDespiteALateAck() {
        pen.setBeep(true)
        assertEquals(false, pen.state.value.settings.beep)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        pen.setBeep(false)
        frame(Protocol.SETTING_CHANGE_RESPONSE, byteArrayOf(Protocol.SET_BEEP.toByte(), 0))
        assertEquals(false, pen.state.value.settings.beep)
        assertFalse(pen.state.value.connected)
        assertTrue(pen.state.value.pendingSettings.contains(PenSettingKey.BEEP))
    }

    @Test fun switchingPensDiscardsRecoveryIntentForThePreviousDevice() {
        pen.setBeep(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        pen.setBeep(false)
        assertTrue(pen.state.value.pendingSettings.isNotEmpty())
        val next = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:FF")
        PenManager::class.java.getDeclaredMethod("connect", BluetoothDevice::class.java)
            .apply { isAccessible = true }.invoke(pen, next)
        assertTrue(pen.state.value.pendingSettings.isEmpty())
        assertNull(pen.state.value.settings.beep)
    }

    @Test fun rejectsFailedAndTruncatedSettingInfoWithoutAuthorizing() {
        setState(PenState(status = PenStatus.CONNECTING))
        frame(Protocol.SETTING_INFO_RESPONSE, ByteArray(0))
        assertFalse(pen.state.value.connected)
        frame(Protocol.SETTING_INFO_RESPONSE, ByteArray(23), result = 1)
        assertFalse(pen.state.value.connected)
    }

    @Test fun unsolicitedOfflineDataCannotStartAnImport() {
        frame(Protocol.OFFLINE_DATA_RESPONSE, byteArrayOf(1, 0, 0, 0))
        frame(Protocol.OFFLINE_PACKET_REQUEST, byteArrayOf())
        assertEquals(OfflinePhase.IDLE, pen.state.value.offlinePhase)
        assertEquals(0, completed)
    }
}
