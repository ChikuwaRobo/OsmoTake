package jp.hiroyuki.osmonanogc

import org.junit.Assert.*
import org.junit.Test

class DumlProtocolTest {
    @Test fun goldenFrameMatchesUpstream() {
        val actual = DumlProtocol.frame(0x1234, 0x1C, 0x53, 0x10, ByteArray(4))
        assertArrayEquals(hex("55 11 04 92 02 1c 12 34 40 53 10 00 00 00 00 88 2e"), actual)
        assertEquals(0x1234, DumlProtocol.parse(actual).sequence)
    }

    @Test fun parserReassemblesAndRejectsCorruption() {
        val first = DumlProtocol.frame(1, 1, 2, 0x70)
        val second = DumlProtocol.frame(2, 7, 7, 0x45, byteArrayOf(0, 2), flags = 0xC0)
        val parser = DumlProtocol.FrameParser()
        assertTrue(parser.feed(first.copyOfRange(0, 7)).isEmpty())
        assertEquals(listOf(1, 2), parser.feed(first.copyOfRange(7, first.size) + second).map { it.sequence })
        val corrupt = DumlProtocol.frame(3, 1, 2, 3).also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val good = DumlProtocol.frame(4, 1, 2, 4)
        assertEquals(listOf(4), parser.feed(corrupt + good).map { it.sequence })
        assertTrue(parser.errors.any { it.contains("CRC") })
    }

    @Test fun compactDockBitIsNeverRecordingConfirmation() {
        val compact = ByteArray(34)
        compact[27] = 0x40
        compact[32] = 1
        compact[33] = 1
        val packet = DumlProtocol.Packet(1, 2, 1, 0, 2, 0x80, compact)
        assertNull(DumlProtocol.recordingState(packet))
    }

    @Test fun fullPushIsAuthoritativeButResponseIsNot() {
        val full = ByteArray(37)
        full[0] = 0x40
        full[4] = 1
        assertEquals(RecordingState.RECORDING,
            DumlProtocol.recordingState(DumlProtocol.Packet(1, 2, 1, 0, 2, 0x80, full)))
        assertNull(DumlProtocol.recordingState(DumlProtocol.Packet(1, 2, 1, 0xC0, 2, 0x80, full)))
        full[4] = 0x7F
        assertNull(DumlProtocol.recordingState(DumlProtocol.Packet(1, 2, 1, 0, 2, 0x80, full)))
    }

    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}

