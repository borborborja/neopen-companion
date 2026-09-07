package net.micapum.neopen.core.pen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PenSettingQueueTest {

    @Test
    fun serializesChangesAndAdvancesOnlyAfterConfirmation() {
        val queue = PenSettingQueue()
        val offline = PenSettingChange(PenSettingKey.OFFLINE_DATA, 1)
        val beep = PenSettingChange(PenSettingKey.BEEP, 0)

        assertEquals(offline, queue.enqueue(offline))
        assertNull(queue.enqueue(beep))
        assertEquals(setOf(PenSettingKey.OFFLINE_DATA, PenSettingKey.BEEP), queue.pendingKeys)

        val outcome = queue.onResponse(PenSettingKey.OFFLINE_DATA.protocolType, successful = true)
            as PenSettingQueueOutcome.Confirmed
        assertEquals(offline, outcome.completed)
        assertEquals(beep, outcome.next)
        assertEquals(setOf(PenSettingKey.BEEP), queue.pendingKeys)
    }

    @Test
    fun coalescesQueuedValuesForTheSameSetting() {
        val queue = PenSettingQueue()
        val beep = PenSettingChange(PenSettingKey.BEEP, 1)
        queue.enqueue(beep)
        queue.enqueue(PenSettingChange(PenSettingKey.AUTO_SHUTDOWN, 20))
        queue.enqueue(PenSettingChange(PenSettingKey.AUTO_SHUTDOWN, 90))

        val outcome = queue.onResponse(PenSettingKey.BEEP.protocolType, successful = true)
            as PenSettingQueueOutcome.Confirmed
        assertEquals(PenSettingChange(PenSettingKey.AUTO_SHUTDOWN, 90), outcome.next)
    }

    @Test
    fun rejectionPreservesUnsentChangesButWaitsForANewSession() {
        val queue = PenSettingQueue()
        queue.enqueue(PenSettingChange(PenSettingKey.OFFLINE_DATA, 1))
        queue.enqueue(PenSettingChange(PenSettingKey.BEEP, 0))

        val outcome = queue.onResponse(PenSettingKey.OFFLINE_DATA.protocolType, successful = false)
            as PenSettingQueueOutcome.Failed
        assertEquals(PenSettingFailure.REJECTED, outcome.reason)
        assertEquals(setOf(PenSettingKey.BEEP), queue.pendingKeys)
        assertSame(
            PenSettingQueueOutcome.Unexpected,
            queue.onResponse(PenSettingKey.BEEP.protocolType, successful = true),
        )
        assertEquals(PenSettingChange(PenSettingKey.BEEP, 0), queue.resumeAfterReconnect())
    }

    @Test
    fun mismatchedResponseIsAProtocolFailure() {
        val queue = PenSettingQueue()
        queue.enqueue(PenSettingChange(PenSettingKey.OFFLINE_DATA, 1))

        val outcome = queue.onResponse(PenSettingKey.BEEP.protocolType, successful = true)
            as PenSettingQueueOutcome.Failed
        assertEquals(PenSettingFailure.PROTOCOL, outcome.reason)
        assertTrue(queue.pendingKeys.isEmpty())
    }

    @Test
    fun timeoutDoesNotConfirmANewValueWithAnOldAck() {
        val queue = PenSettingQueue()
        queue.enqueue(PenSettingChange(PenSettingKey.SENSITIVITY, 3))

        val outcome = queue.fail(PenSettingFailure.TIMEOUT) as PenSettingQueueOutcome.Failed
        assertEquals(PenSettingFailure.TIMEOUT, outcome.reason)
        assertTrue(queue.pendingKeys.isEmpty())
        val newer = PenSettingChange(PenSettingKey.SENSITIVITY, 1)
        assertNull(queue.enqueue(newer))
        assertSame(PenSettingQueueOutcome.Unexpected,
            queue.onResponse(PenSettingKey.SENSITIVITY.protocolType, true))
        assertEquals(newer, queue.resumeAfterReconnect())
        assertEquals(newer, (queue.onResponse(PenSettingKey.SENSITIVITY.protocolType, true)
            as PenSettingQueueOutcome.Confirmed).completed)
    }

    @Test
    fun liveModeRestorationSurvivesDisableTimeout() {
        val queue = PenSettingQueue()
        queue.enqueue(PenSettingChange(PenSettingKey.OFFLINE_DATA, 0))
        val restore = PenSettingChange(PenSettingKey.OFFLINE_DATA, 1)
        queue.enqueue(restore)
        queue.fail(PenSettingFailure.TIMEOUT)
        assertEquals(setOf(PenSettingKey.OFFLINE_DATA), queue.pendingKeys)
        assertEquals(restore, queue.resumeAfterReconnect())
    }

    @Test
    fun explicitDisconnectClearsRecoveryAndPendingIntent() {
        val queue = PenSettingQueue()
        val change = PenSettingChange(PenSettingKey.BEEP, 1)
        queue.enqueue(change)
        queue.fail(PenSettingFailure.TIMEOUT)
        queue.enqueue(change.copy(value = 0))
        queue.clear()
        assertNull(queue.resumeAfterReconnect())
        assertTrue(queue.pendingKeys.isEmpty())
        assertEquals(change, queue.enqueue(change))
    }

    @Test
    fun appliesOnlyTheConfirmedValue() {
        val initial = PenSettings(
            autoPowerOn = false,
            beep = true,
            offlineData = false,
            autoShutdownTime = 20,
            sensitivity = 2,
        )

        assertEquals(true, initial.withConfirmed(PenSettingChange(PenSettingKey.AUTO_POWER_ON, 1)).autoPowerOn)
        assertEquals(false, initial.withConfirmed(PenSettingChange(PenSettingKey.BEEP, 0)).beep)
        assertEquals(true, initial.withConfirmed(PenSettingChange(PenSettingKey.OFFLINE_DATA, 1)).offlineData)
        assertEquals(90, initial.withConfirmed(PenSettingChange(PenSettingKey.AUTO_SHUTDOWN, 90)).autoShutdownTime)
        assertEquals(4, initial.withConfirmed(PenSettingChange(PenSettingKey.SENSITIVITY, 4)).sensitivity)
    }
}
