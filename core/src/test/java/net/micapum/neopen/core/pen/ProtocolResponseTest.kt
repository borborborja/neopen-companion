package net.micapum.neopen.core.pen

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolResponseTest {

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedOfflineChunkPayload() {
        val bytes = ByteArray(18)
        bytes[3] = 10 // beforeSize=10, pero no hay datos
        Protocol.parseOfflineChunk(bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun neverReturnsPartialSuccessForATruncatedStrokeHeader() {
        Protocol.parseOfflineStrokes(ByteArray(26), 3, 27, 1, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun neverReturnsPartialSuccessForATruncatedDot() {
        val bytes = ByteArray(27)
        bytes[25] = 1 // dotCount=1 sin sus 16 bytes
        Protocol.parseOfflineStrokes(bytes, 3, 27, 1, 1)
    }

    @Test
    fun rejectsFramesWithTruncatedOrTrailingPayloadAndRecovers() {
        val received = mutableListOf<Protocol.Frame>()
        val parser = Protocol.FrameReassembler { received.add(it) }
        for (length in listOf(1, 3)) {
            parser.feed(byteArrayOf(0xc0.toByte(), 0x85.toByte(), 0, length.toByte(), 0, 7, 0, 0xc1.toByte()))
        }
        assertTrue(received.isEmpty())
        parser.feed(byteArrayOf(0xc0.toByte(), 0x85.toByte(), 0, 2, 0, 7, 0, 0xc1.toByte()))
        assertEquals(1, received.size)
    }

    @Test
    fun rejectsUnterminatedEscapeAndOversizedNoise() {
        val received = mutableListOf<Protocol.Frame>()
        val parser = Protocol.FrameReassembler { received.add(it) }
        parser.feed(byteArrayOf(0xc0.toByte(), 0x85.toByte(), 0, 2, 0, 7, 0, 0x7d, 0xc1.toByte()))
        parser.feed(byteArrayOf(0xc0.toByte()) + ByteArray(70_000) + byteArrayOf(0xc1.toByte()))
        assertTrue(received.isEmpty())
        parser.feed(byteArrayOf(0xc0.toByte(), 0x85.toByte(), 0, 2, 0, 7, 0, 0xc1.toByte()))
        assertEquals(1, received.size)
    }

    @Test
    fun reassemblesEscapedBytesSplitAcrossNotifications() {
        val received = mutableListOf<Protocol.Frame>()
        val parser = Protocol.FrameReassembler { received.add(it) }
        parser.feed(byteArrayOf(0xc0.toByte(), 0x85.toByte(), 0, 2, 0, 0x7d))
        parser.feed(byteArrayOf(0xe0.toByte(), 0, 0xc1.toByte()))
        assertArrayEquals(byteArrayOf(0xc0.toByte(), 0), received.single().data)
    }

    @Test
    fun settingResponseKeepsOuterAndPayloadResultsSeparate() {
        var received: Protocol.Frame? = null
        val reassembler = Protocol.FrameReassembler { received = it }

        reassembler.feed(
            byteArrayOf(
                Protocol.STX.toByte(),
                Protocol.SETTING_CHANGE_RESPONSE.toByte(),
                0, // result exterior del frame
                2, 0, // longitud LE
                Protocol.SET_OFFLINE_DATA.toByte(),
                0, // resultado del cambio dentro del payload
                Protocol.ETX.toByte(),
            ),
        )

        assertEquals(Protocol.SETTING_CHANGE_RESPONSE, received?.cmd)
        assertEquals(0, received?.result)
        assertArrayEquals(byteArrayOf(Protocol.SET_OFFLINE_DATA.toByte(), 0), received?.data)
    }

    @Test
    fun parsesSuccessfulAndRejectedSettingChanges() {
        val success = Protocol.parseSettingChange(byteArrayOf(Protocol.SET_OFFLINE_DATA.toByte(), 0))
        val rejected = Protocol.parseSettingChange(byteArrayOf(Protocol.SET_BEEP.toByte(), 1))

        assertEquals(Protocol.SET_OFFLINE_DATA, success.settingType)
        assertTrue(success.successful)
        assertEquals(Protocol.SET_BEEP, rejected.settingType)
        assertFalse(rejected.successful)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedSettingChange() {
        Protocol.parseSettingChange(byteArrayOf(Protocol.SET_OFFLINE_DATA.toByte()))
    }

    @Test
    fun parsesACompleteOfflineNoteList() {
        val data = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1)
            .put(byteArrayOf(0x56, 0x34, 0x12, 0x03))
            .putInt(42)
            .array()

        assertEquals(listOf(OfflineNote(section = 3, owner = 0x123456, note = 42)), Protocol.parseNoteList(data))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPartialOfflineNoteList() {
        Protocol.parseNoteList(byteArrayOf(1, 0, 0x56, 0x34))
    }
}
