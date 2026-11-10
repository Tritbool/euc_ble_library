package io.github.tritbool.euc.ble.test

import io.github.tritbool.euc.ble.core.ByteUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.*

/** Reassembles H4 ACL continuations by connection/direction before extracting ATT values. */
object BtsnoopCapture {
    data class Notification(val bytes: ByteArray, val aclFragments: Int, val record: Int)

    fun notifications(file: File): List<Notification> = file.reader().use(::read)

    fun notifications(resource: String): List<Notification> =
        requireNotNull(javaClass.getResourceAsStream(resource)) { "Missing capture: $resource" }
            .reader().use(::read)

    private fun read(reader: Reader): List<Notification> {
        val bytes = reader.buffered().useLines { lines ->
            lines.flatMap { line ->
                ByteUtils.hexToBytes(line.substringAfter(':').take(40)).asSequence()
            }.toList().toByteArray()
        }
        val records = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals("btsnoop\u0000", String(bytes.copyOfRange(0, 8), Charsets.US_ASCII))
        records.position(16)
        data class Partial(val data: ByteArrayOutputStream, var fragments: Int)
        val partials = mutableMapOf<Pair<Int, Int>, Partial>()
        val output = mutableListOf<Notification>()
        var index = 0
        while (records.remaining() >= 24) {
            index++
            records.int
            val included = records.int
            val direction = records.int and 1
            records.int
            records.long
            assertTrue(included in 0..records.remaining())
            val packet = ByteArray(included).also { records.get(it) }
            if (packet.size < 5 || packet[0] != 0x02.toByte()) continue
            val handleAndBoundary = ByteUtils.getUnsignedShortLE(packet, 1)
            val aclLength = ByteUtils.getUnsignedShortLE(packet, 3)
            assertEquals(aclLength + 5, packet.size)
            val key = (handleAndBoundary and 0x0FFF) to direction
            when ((handleAndBoundary shr 12) and 3) {
                0, 2 -> partials[key] = Partial(ByteArrayOutputStream().apply {
                    write(packet, 5, aclLength)
                }, 1)
                1 -> {
                    val partial = partials[key] ?: continue
                    partial.data.write(packet, 5, aclLength)
                    partial.fragments++
                }
                else -> continue
            }
            val partial = partials.getValue(key)
            val l2cap = partial.data.toByteArray()
            if (l2cap.size < 4) continue
            val length = ByteUtils.getUnsignedShortLE(l2cap, 0)
            if (l2cap.size < length + 4) continue
            assertEquals(length + 4, l2cap.size)
            partials.remove(key)
            if (ByteUtils.getUnsignedShortLE(l2cap, 2) != 0x0004 || length < 3) continue
            val opcode = l2cap[4].toInt() and 0xFF
            if (opcode == 0x1B || opcode == 0x1D) {
                output += Notification(l2cap.copyOfRange(7, l2cap.size), partial.fragments, index)
            }
        }
        return output
    }
}
