package net.micapum.neopen.core.pen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineListCoordinatorTest {
    private val note = OfflineNote(section = 3, owner = 27, note = 12)

    @Test
    fun autoImportStartsOnlyWhenTheListCompletes() {
        val coordinator = OfflineListCoordinator()

        assertTrue(coordinator.start(OfflineListMode.AUTO_IMPORT))
        assertTrue(coordinator.active)
        val completion = coordinator.complete(listOf(note))

        assertEquals(listOf(note), completion?.notes)
        assertTrue(completion?.autoImport == true)
        assertFalse(coordinator.active)
    }

    @Test
    fun manualListNeverRequestsAnImport() {
        val coordinator = OfflineListCoordinator()
        coordinator.start(OfflineListMode.MANUAL)

        val completion = coordinator.complete(emptyList())

        assertEquals(emptyList<OfflineNote>(), completion?.notes)
        assertFalse(completion?.autoImport == true)
    }

    @Test
    fun duplicateListIsRejectedWhileOneIsActive() {
        val coordinator = OfflineListCoordinator()
        assertTrue(coordinator.start(OfflineListMode.AUTO_IMPORT))
        assertFalse(coordinator.start(OfflineListMode.MANUAL))
    }

    @Test
    fun workerJoinsManualListAndStartsImportOnlyAfterResponse() {
        val coordinator = OfflineListCoordinator()
        coordinator.start(OfflineListMode.MANUAL)
        coordinator.requestImportAfterList()
        assertTrue(coordinator.active)
        assertFalse(coordinator.start(OfflineListMode.AUTO_IMPORT))
        assertTrue(coordinator.complete(listOf(note))!!.autoImport)
        assertNull(coordinator.complete(listOf(note)))
    }

    @Test
    fun lateResponseAfterTimeoutOrDisconnectIsIgnored() {
        val coordinator = OfflineListCoordinator()
        coordinator.start(OfflineListMode.AUTO_IMPORT)

        assertTrue(coordinator.cancel())
        assertNull(coordinator.complete(listOf(note)))
        assertFalse(coordinator.cancel())
    }
}
