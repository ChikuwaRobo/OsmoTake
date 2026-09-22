package jp.hiroyuki.osmonanogc

import java.io.ByteArrayOutputStream

object DumlProtocol {
    const val APP = 0x02
    const val CAMERA = 0x01
    const val WIFI = 0x07
    const val SESSION = 0xF0
    const val SESSION_INFO = 0x88

    data class Packet(
        val source: Int,
        val destination: Int,
        val sequence: Int,
        val flags: Int,
        val commandSet: Int,
        val commandId: Int,
        val payload: ByteArray,
    ) {
        val isResponse: Boolean get() = flags and 0x80 != 0
    }

    fun crc8(data: ByteArray): Int {
        var checksum = 0x77
        data.forEach { raw ->
            var value = checksum xor (raw.toInt() and 0xFF)
            repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor 0x8C else value ushr 1 }
            checksum = value
        }
        return checksum and 0xFF
    }

    fun crc16(data: ByteArray): Int {
        var checksum = 0x3692
        data.forEach { raw ->
            var value = checksum xor (raw.toInt() and 0xFF)
            repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor 0x8408 else value ushr 1 }
            checksum = value
        }
        return checksum and 0xFFFF
    }

    fun frame(
        sequence: Int,
        destination: Int,
        commandSet: Int,
        commandId: Int,
        payload: ByteArray = byteArrayOf(),
        source: Int = APP,
        flags: Int = 0x40,
    ): ByteArray {
        val length = 13 + payload.size
        require(length <= 0x3FF)
        val versionLength = length or (1 shl 10)
        val output = ByteArrayOutputStream(length)
        output.write(0x55)
        output.write(versionLength and 0xFF)
        output.write(versionLength ushr 8)
        output.write(crc8(output.toByteArray()))
        output.write(source)
        output.write(destination)
        output.write(sequence ushr 8)
        output.write(sequence and 0xFF)
        output.write(flags)
        output.write(commandSet)
        output.write(commandId)
        output.write(payload)
        val crc = crc16(output.toByteArray())
        output.write(crc and 0xFF)
        output.write(crc ushr 8)
        return output.toByteArray()
    }

    fun parse(data: ByteArray): Packet {
        require(data.size >= 13 && data[0].toInt() and 0xFF == 0x55) { "DUML frame incomplete" }
        val declared = ((data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)) and 0x3FF
        require(declared == data.size) { "DUML length mismatch" }
        require(data[3].toInt() and 0xFF == crc8(data.copyOfRange(0, 3))) { "DUML header CRC mismatch" }
        val expected = (data[data.lastIndex - 1].toInt() and 0xFF) or ((data.last().toInt() and 0xFF) shl 8)
        require(expected == crc16(data.copyOfRange(0, data.size - 2))) { "DUML packet CRC mismatch" }
        return Packet(
            data[4].toInt() and 0xFF,
            data[5].toInt() and 0xFF,
            ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF),
            data[8].toInt() and 0xFF,
            data[9].toInt() and 0xFF,
            data[10].toInt() and 0xFF,
            data.copyOfRange(11, data.size - 2),
        )
    }

    fun packedString(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 255)
        return byteArrayOf(bytes.size.toByte()) + bytes
    }

    fun pairingPayload(identifier: String, token: String = "OSMOCTRL") =
        packedString(identifier) + packedString(token)

    /** Only a full unsolicited state push is authoritative for recording. */
    fun recordingState(packet: Packet): RecordingState? {
        if (packet.isResponse || (packet.commandSet to packet.commandId) !in
            setOf(0x02 to 0x80, 0x0D to 0x02)) return null
        val payload = packet.payload
        if (payload.size < 37) return null
        val flags = (payload[0].toInt() and 0xFF) or
            ((payload[1].toInt() and 0xFF) shl 8) or
            ((payload[2].toInt() and 0xFF) shl 16) or
            ((payload[3].toInt() and 0xFF) shl 24)
        return when (val mode = payload[4].toInt() and 0xFF) {
            0x01 -> if (flags and 0x00C0 != 0) RecordingState.RECORDING else RecordingState.STOPPED
            in 0x00..0x0F -> RecordingState.STOPPED
            else -> null
        }
    }

    class FrameParser {
        private val buffer = ArrayList<Byte>()
        val errors = ArrayList<String>()

        fun clear() { buffer.clear(); errors.clear() }

        fun feed(chunk: ByteArray): List<Packet> {
            chunk.forEach(buffer::add)
            val packets = ArrayList<Packet>()
            while (buffer.isNotEmpty()) {
                val start = buffer.indexOfFirst { it.toInt() and 0xFF == 0x55 }
                if (start < 0) { buffer.clear(); break }
                repeat(start) { buffer.removeAt(0) }
                if (buffer.size < 4) break
                val length = ((buffer[1].toInt() and 0xFF) or ((buffer[2].toInt() and 0xFF) shl 8)) and 0x3FF
                if (length < 13) { errors += "invalid length"; buffer.removeAt(0); continue }
                if (buffer[3].toInt() and 0xFF != crc8(buffer.take(3).toByteArray())) {
                    errors += "header CRC mismatch"; buffer.removeAt(0); continue
                }
                if (buffer.size < length) break
                val raw = buffer.take(length).toByteArray()
                repeat(length) { buffer.removeAt(0) }
                try { packets += parse(raw) } catch (error: IllegalArgumentException) {
                    errors += error.message ?: "invalid DUML frame"
                }
            }
            return packets
        }
    }
}

enum class RecordingState { UNKNOWN, STOPPED, RECORDING }

