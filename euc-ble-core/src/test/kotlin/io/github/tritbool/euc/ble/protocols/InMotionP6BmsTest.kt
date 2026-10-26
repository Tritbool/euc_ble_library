package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.models.BMSData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InMotionP6BmsTest {
    private data class Notification(val bytes: ByteArray, val aclFragments: Int)
    private data class Response(val pack: Int, val payload: ByteArray, val wire: ByteArray)

    @Test
    fun bothCapturesExposeTwoIndependent56CellPacksAndIdentity() {
        for (capture in listOf("btsnoop-p6_hci.hex", "btsnoop-p6-charge_hci.hex")) {
            val protocol = p6()
            val queries = packQueries(protocol)
            val acknowledged = mutableSetOf<String>()
            val notifications = notifications(capture)
            assertTrue(notifications.any { it.aclFragments > 1 }, capture)
            for (notification in notifications) {
                // Exactly the manager's order: several matchers, then one decoder.
                queries.filter { protocol.matchesQueryResponse(it, notification.bytes) }
                    .forEach { acknowledged += it.id }
                protocol.decode(notification.bytes)
            }
            assertEquals(queries.map { it.id }.toSet(), acknowledged, capture)
            val packs = protocol.getBMSData()!!
            assertEquals(listOf(1, 2), packs.map { it.bmsIndex })
            assertEquals(listOf("Z100E5A00319", "Z100E5A00092"), packs.map { it.serialNumber })
            for (pack in packs) {
                assertEquals("2.2.76", pack.firmwareVersion)
                assertEquals(56, pack.cellVoltages!!.size)
                assertTrue(pack.cellVoltages.all { it in 3.7..4.1 })
                assertEquals(false, pack.isCharging) // Both captures end with zero charge.
                assertEquals(0.0, pack.chargeCurrent)
                assertEquals(0.0, pack.dischargeCurrent)
                assertEquals(0.0, pack.current)
                assertUnknownFields(pack)
            }
            val expectedVolts = if (capture.contains("charge")) listOf(217.36, 217.34)
                else listOf(215.81, 215.86)
            assertEquals(expectedVolts, packs.map { it.voltage })
        }
    }

    @Test
    fun chargingCaptureAgreesWithPackCurrentsAndScreenshotWithoutWheelFallback() {
        val protocol = p6()
        val responses = responses("btsnoop-p6-charge_hci.hex")
        for ((pack, expectedCharge) in listOf(0x24 to 4.90, 0x25 to 4.68)) {
            val summary = responses.first {
                it.pack == pack && subtype(it) == 1 &&
                    ByteUtils.getUnsignedShortLE(it.payload, 10) == (expectedCharge * 100).toInt()
            }
            protocol.decode(summary.wire)
        }
        val packs = protocol.getBMSData()!!
        assertEquals(listOf(4.90, 4.68), packs.map { it.chargeCurrent })
        assertEquals(listOf(-4.90, -4.68), packs.map { it.current })
        assertTrue(packs.all { it.isCharging == true && it.dischargeCurrent == 0.0 })
        assertTrue(packs.all { it.voltage!! in 219.0..221.0 })
        packs.forEach(::assertUnknownFields)
    }

    @Test
    fun separatePositiveChannelsPublishDischargeMinusChargeAsNetCurrent() {
        val protocol = p6()
        val captured = responses("btsnoop-p6_hci.hex").first { subtype(it) == 1 }
        val payload = captured.payload.copyOf()
        payload[10] = 250.toByte()
        payload[11] = 0
        payload[12] = 0x26
        payload[13] = 0x02 // 550 centiamps.
        protocol.decode(frame(0x24, payload))
        val pack = protocol.getBMSData()!!.single()
        assertEquals(2.5, pack.chargeCurrent)
        assertEquals(5.5, pack.dischargeCurrent)
        assertEquals(3.0, pack.current)
        assertTrue(pack.isCharging == true)
    }

    @Test
    fun negativeIdleDischargeSensorNoiseInChargingCaptureDoesNotBecome655Amps() {
        val captured = responses("btsnoop-p6-charge_hci.hex").first {
            subtype(it) == 1 && ByteUtils.getUnsignedShortLE(it.payload, 12) == 0xFFEB
        }
        val protocol = p6()
        protocol.decode(captured.wire)
        val pack = protocol.getBMSData()!!.single()
        assertEquals(0.0, pack.dischargeCurrent)
        assertEquals(0.0, pack.chargeCurrent)
        assertEquals(0.0, pack.current)
        assertEquals(false, pack.isCharging)
    }

    @Test
    fun wheelChargingCurrentAndTemperaturesNeverFillMissingPackFields() {
        val protocol = p6()
        val realtime = ByteArray(78).also {
            it[2] = 0x0C
            it[3] = 0xFE.toByte() // Wheel current -5 A.
            it[58] = 200.toByte()
            it[59] = 201.toByte()
            it[74] = 0x80.toByte() // Wheel charging flag.
        }
        val wheel = protocol.decode(frame(0x84, realtime, flag = 0x14))
        assertNotNull(wheel)
        assertEquals(-5.0, wheel!!.current)
        assertNull(protocol.getBMSData())
        val captured = responses("btsnoop-p6_hci.hex")
        protocol.decode(captured.first { it.pack == 0x24 && subtype(it) == 4 }.wire)
        val identity = protocol.getBMSData()!!.single()
        assertNull(identity.current)
        assertNull(identity.temperatures)
        assertNull(identity.isCharging)
        protocol.decode(captured.first { it.pack == 0x24 && subtype(it) == 1 }.wire)
        val idle = protocol.getBMSData()!!.single()
        assertEquals(0.0, idle.current)
        assertEquals(false, idle.isCharging)
        assertNull(idle.temperatures)
    }

    @Test
    fun idlePack2CellResponseIsReassembledAtHciAclBeforeAttExtraction() {
        val notification = notifications("btsnoop-p6_hci.hex").first {
            it.aclFragments > 1 && response(it.bytes)?.let { r ->
                r.pack == 0x25 && subtype(r) == 2 && r.payload.size == 114
            } == true
        }
        val protocol = p6()
        protocol.decode(notification.bytes)
        val pack = protocol.getBMSData()!!.single()
        assertEquals(2, pack.bmsIndex)
        assertEquals(56, pack.cellVoltages!!.size)
        assertEquals(3.858, pack.cellVoltages.first())
        assertEquals(3.853, pack.cellVoltages.last())
        assertNull(pack.current)
        assertNull(pack.voltage)
        assertNull(pack.isCharging)
    }

    @Test
    fun allSummaryCellsIdentityArrivalOrdersMergeWithoutLosingOtherPagesOrPacks() {
        val captured = responses("btsnoop-p6_hci.hex")
        val pages = listOf(1, 2, 4).map { type ->
            captured.first { it.pack == 0x24 && subtype(it) == type }
        }
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (order in orders) {
            val protocol = p6()
            val pack2 = captured.first { it.pack == 0x25 && subtype(it) == 4 }
            protocol.decode(pack2.wire)
            for (i in order) protocol.decode(pages[i].wire)
            val packs = protocol.getBMSData()!!
            assertEquals(56, packs[0].cellVoltages!!.size)
            assertEquals(215.81, packs[0].voltage)
            assertEquals(0.0, packs[0].current)
            assertEquals(false, packs[0].isCharging)
            assertEquals("Z100E5A00319", packs[0].serialNumber)
            assertEquals("2.2.76", packs[0].firmwareVersion)
            assertEquals("Z100E5A00092", packs[1].serialNumber)
            assertNull(packs[1].current)
            assertNull(packs[1].cellVoltages)
        }
    }

    @Test
    fun modelUnknownPlanActivatesExactP6CommandsOnlyAfterIdentification() {
        val protocol = InMotionProtocol()
        assertTrue(packQueries(protocol).isEmpty())
        identify(protocol)
        val expected = listOf(
            "aaaa160302240132", "aaaa160302240231", "aaaa160302240437",
            "aaaa160302250133", "aaaa160302250230", "aaaa160302250436"
        )
        assertEquals(expected, packQueries(protocol).map {
            ByteUtils.bytesToHex(protocol.createCommand(it.commandType, it.value), "").lowercase()
        })
        assertEquals(listOf(0L, 0L), packQueries(protocol).filter {
            (it.value as ByteArray)[6] == 4.toByte()
        }.map { it.intervalMs })
    }

    @Test
    fun exactPackAndSubtypeOnlyAcknowledgeCompleteValidatedResponses() {
        val protocol = p6()
        val captured = responses("btsnoop-p6_hci.hex")
        val queries = packQueries(protocol)
        for (r in captured.filter { subtype(it) in listOf(1, 2, 4) }.distinctBy {
            it.pack to subtype(it)
        }) {
            val matches = queries.filter { protocol.matchesQueryResponse(it, r.wire) }
            assertEquals(1, matches.size)
            val request = matches.single().value as ByteArray
            assertEquals(r.pack, request[5].toInt() and 0xFF)
            assertEquals(subtype(r), request[6].toInt() and 0xFF)
        }
        val cells = captured.first { it.pack == 0x24 && subtype(it) == 2 }
        val invalids = listOf(
            cells.wire.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            frame(0x26, cells.payload),
            frame(0x24, cells.payload.copyOf(66)), // V14-sized page is not P6.
            frame(0x24, cells.payload.copyOf(113)),
            frame(0x24, cells.payload.copyOf(115)),
            frame(0x24, cells.payload.copyOf().also { it[1] = 0x83.toByte() }),
            frame(0x24, cells.payload, flag = 0x14)
        )
        for (wire in invalids) {
            val isolated = p6()
            assertFalse(packQueries(isolated).any { isolated.matchesQueryResponse(it, wire) })
            isolated.decode(wire)
            assertNull(isolated.getBMSData())
        }
        val partial = cells.wire.copyOfRange(0, cells.wire.size - 1)
        assertFalse(queries.any { protocol.matchesQueryResponse(it, partial) })
    }

    @Test
    fun arbitraryFragmentBoundariesIncludingHeaderEscapesAndChecksumAreConsumedOnce() {
        val captured = responses("btsnoop-p6_hci.hex")
        val original = captured.first { it.pack == 0x25 && subtype(it) == 2 }
        // Exercise escaped response address A5 and cell values containing AA and A5.
        val payload = original.payload.copyOf().also {
            it[2] = 0xAA.toByte()
            it[4] = 0xA5.toByte()
        }
        val wire = frame(0xA5, payload)
        for (split in 1 until wire.size) {
            val protocol = p6()
            val queries = packQueries(protocol)
            val first = wire.copyOfRange(0, split)
            assertFalse(queries.any { protocol.matchesQueryResponse(it, first) }, "split=$split")
            protocol.decode(first)
            assertNull(protocol.getBMSData())
            val second = wire.copyOfRange(split, wire.size)
            assertEquals(1, queries.count { protocol.matchesQueryResponse(it, second) })
            protocol.decode(second)
            val pack = protocol.getBMSData()!!.single()
            assertEquals(2, pack.bmsIndex)
            assertEquals(56, pack.cellVoltages!!.size)
            assertEquals(4.010, pack.cellVoltages[0])
            assertEquals(4.005, pack.cellVoltages[1])
        }
        val protocol = p6()
        val queries = packQueries(protocol)
        for ((index, byte) in wire.withIndex()) {
            val chunk = byteArrayOf(byte)
            assertEquals(if (index == wire.lastIndex) 1 else 0,
                queries.count { protocol.matchesQueryResponse(it, chunk) })
            protocol.decode(chunk)
        }
        assertEquals(56, protocol.getBMSData()!!.single().cellVoltages!!.size)
    }

    @Test
    fun consecutiveDecodeCallsConsumeReusedIdenticalAndMutableArraysAsNewNotifications() {
        val wire = responses("btsnoop-p6_hci.hex").first { it.pack == 0x25 && subtype(it) == 2 }.wire
        val protocol = p6()
        val chunk = byteArrayOf(0xAA.toByte())
        protocol.decode(chunk)
        protocol.decode(chunk) // Identical object and content, but the second header byte.
        for (byte in wire.drop(2)) {
            chunk[0] = byte
            protocol.decode(chunk)
        }
        assertEquals(56, protocol.getBMSData()!!.single().cellVoltages!!.size)

        protocol.resetSession()
        identify(protocol)
        val queries = packQueries(protocol)
        for ((index, byte) in wire.withIndex()) {
            chunk[0] = byte
            assertEquals(if (index == wire.lastIndex) 1 else 0,
                queries.count { protocol.matchesQueryResponse(it, chunk) })
            protocol.decode(chunk)
        }
        assertEquals(56, protocol.getBMSData()!!.single().cellVoltages!!.size)
    }

    @Test
    fun malformedEscapeTruncatedPacketAndNoiseResynchronizeWithoutAcknowledging() {
        val valid = responses("btsnoop-p6_hci.hex").first { it.pack == 0x25 && subtype(it) == 2 }
        val malformed = frame(0xA5, valid.payload).copyOf().also { it[5] = 0x01 }
        val protocol = p6()
        assertFalse(packQueries(protocol).any { protocol.matchesQueryResponse(it, malformed) })
        protocol.decode(malformed)
        val truncated = valid.wire.copyOfRange(0, 20)
        protocol.decode(truncated)
        val complete = byteArrayOf(0x00, 0x55, 0x01) + valid.wire
        assertEquals(1, packQueries(protocol).count { protocol.matchesQueryResponse(it, complete) })
        protocol.decode(complete)
        assertEquals(56, protocol.getBMSData()!!.single().cellVoltages!!.size)
    }

    @Test
    fun sessionResetClearsPartialFramesIdentityDialectAndWheelDerivedState() {
        val protocol = p6()
        notifications("btsnoop-p6-charge_hci.hex").forEach { protocol.decode(it.bytes) }
        assertNotNull(protocol.getBMSData())
        val cells = responses("btsnoop-p6_hci.hex").first { subtype(it) == 2 }
        protocol.decode(cells.wire.copyOfRange(0, 30))
        protocol.resetSession()
        assertNull(protocol.getBMSData())
        assertTrue(packQueries(protocol).isEmpty())
        protocol.decode(cells.wire.copyOfRange(30, cells.wire.size))
        assertNull(protocol.getBMSData())
        identify(protocol)
        assertNull(protocol.getBMSData())
        protocol.decode(cells.wire)
        val pack = protocol.getBMSData()!!.single()
        assertNull(pack.serialNumber)
        assertNull(pack.firmwareVersion)
        assertNull(pack.voltage)
        assertNull(pack.current)
        assertNull(pack.temperatures)
        assertNull(pack.isCharging)
    }

    @Test
    fun resetAlsoClearsV14CellsAndModelDependentPolling() {
        val protocol = InMotionProtocol()
        protocol.decode(frame(0x82, byteArrayOf(1, 2, 9, 2), flag = 0x11))
        protocol.decode(frame(0x82, byteArrayOf(2) + "OLD-WHEEL-SERIAL!".toByteArray(), flag = 0x11))
        protocol.decode(frame(0x24, byteArrayOf(2, 0x82.toByte()) +
            ByteArray(64) { if (it % 2 == 0) 0 else 0x10 }))
        assertEquals(32, protocol.getBMSData()!!.single().cellVoltages!!.size)
        assertEquals(4, packQueries(protocol).size)
        protocol.resetSession()
        assertNull(protocol.getBMSData())
        assertTrue(packQueries(protocol).isEmpty())
        protocol.decode(frame(0x82, byteArrayOf(1, 2, 9, 2), flag = 0x11))
        assertNull(protocol.getBMSData())
    }

    private fun assertUnknownFields(pack: BMSData) {
        assertNull(pack.remainingCapacity)
        assertNull(pack.factoryCapacity)
        assertNull(pack.cycles)
        assertNull(pack.temperatures)
    }

    private fun p6() = InMotionProtocol().also(::identify)

    private fun identify(protocol: InMotionProtocol) {
        protocol.decode(frame(0x82, byteArrayOf(1, 2, 13, 1), flag = 0x11))
    }

    private fun packQueries(protocol: InMotionProtocol) =
        protocol.getPollingPlan().periodicQueries.filter {
            it.commandType == CommandType.CUSTOM && !it.id.startsWith("inmotion.extended-")
        }

    private fun subtype(response: Response) = response.payload[1].toInt() and 0x7F

    private fun responses(capture: String) = notifications(capture).mapNotNull { response(it.bytes) }
        .filter { it.pack in 0x24..0x25 && it.payload.size > 2 }

    private fun response(wire: ByteArray): Response? {
        if (wire.size < 8 || wire[0] != 0xAA.toByte() || wire[1] != 0xAA.toByte() ||
            wire[2] != 0x16.toByte()) return null
        val unescaped = ArrayList<Byte>()
        var offset = 2
        while (offset < wire.lastIndex) {
            val byte = wire[offset++]
            if (byte == 0xA5.toByte()) {
                if (offset >= wire.lastIndex) return null
                unescaped += wire[offset++]
            } else unescaped += byte
        }
        if (unescaped.size < 5 || unescaped.size != (unescaped[1].toInt() and 0xFF) + 2) return null
        if (unescaped.fold(0) { acc, byte -> acc xor (byte.toInt() and 0xFF) } !=
            wire.last().toInt() and 0xFF) return null
        return Response(unescaped[2].toInt() and 0x7F,
            unescaped.drop(3).toByteArray(), wire)
    }

    private fun frame(command: Int, payload: ByteArray, flag: Int = 0x16): ByteArray {
        val body = byteArrayOf(flag.toByte(), (payload.size + 1).toByte(), command.toByte()) + payload
        return ByteArrayOutputStream().apply {
            write(0xAA)
            write(0xAA)
            body.forEach {
                val value = it.toInt() and 0xFF
                if (value == 0xAA || value == 0xA5) write(0xA5)
                write(value)
            }
            write(body.fold(0) { acc, byte -> acc xor (byte.toInt() and 0xFF) })
        }.toByteArray()
    }

    /**
     * btsnoop records contain H4 packets, not standalone ATT values. Reassemble each
     * connection/direction's L2CAP SDU across ACL continuation packets BEFORE reading
     * the ATT notification handle. In particular idle pack 2 cells span ACL packets.
     */
    private fun notifications(capture: String): List<Notification> {
        val bytes = javaClass.getResourceAsStream("/ble_frames/$capture")!!.bufferedReader().useLines { lines ->
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
        while (records.remaining() >= 24) {
            records.int // Original packet length.
            val included = records.int
            val direction = records.int and 1
            records.int // Drops.
            records.long // Timestamp.
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
                output += Notification(l2cap.copyOfRange(7, l2cap.size), partial.fragments)
            }
        }
        return output
    }
}
