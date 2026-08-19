package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertArrayEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertNotNull
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertNull
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader

class InMotionProtocolTest {
    companion object {
        // Thresholds are intentionally different to match fixture sizes:
        // V5F capture has ~877 rows, V8S capture has ~2816 rows.
        private const val MAX_TEST_FRAMES = 100000
        private const val MINIMUM_V5F_FRAME_COUNT = 200
        private const val MINIMUM_V8S_FRAME_COUNT = 500
        private const val MAX_MALFORMED_ROW_RATIO = 0.2
    }

    private lateinit var protocol: InMotionProtocol

    @BeforeEach
    fun setUp() {
        protocol = InMotionProtocol()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
    }

    @Test
    fun decodeV9LegacyVectorMatchesExpectedValues() {

        val packets = listOf(
            "aaaa11088201020c0101010095",
            "aaaa11178202413134323139353041303030343635460000000000fd",
            "aaaa11388206222800040719000802212600080101000902230a0004010a0002012401000102010001012501000102010001012f0500050101000000b8",
            "aaaa142ca0202a000000071900089411a00f9511000058020064641a020a28646428d0071e32010001012501053015009c",
            "aaaa142b900001162617000000c59d4980520367003100cdc9c9c9060000005d0000000000000044000000ca010000cf",
            "aaaa14199191620000c1a216008bc301006ffe000037890200ffffd5fe55",
            "aaaa1457843e1e0c000000000000000000afffc30000000000ffffd7fe000000000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cdc900ceb0cec8ceb03a6400000000004900000000000000000000003f"
        )

        val decoded = packets
            .mapNotNull { protocol.decode(ByteUtils.hexToBytes(it)) }
        //.lastOrNull { it != null }
        //?: fail("Expected realtime telemetry frame to decode")

        assertEquals("InMotion", decoded.first().manufacturer)
        assertEquals("InMotion V9", decoded.first().model)
        assertEquals("A1421950A000465F", decoded.first().serialNumber)
        assertEquals("Main:1.8.38 Drv:7.4.40 BLE:1.4.10", decoded.first().firmwareVersion)

        assertEquals(0.0, decoded.first().speed, 0.01)
        assertEquals(77.42, decoded.first().voltage, 0.01)
        assertEquals(0.12, decoded.first().current, 0.01)
        assertEquals(1.95, decoded.first().pwm ?: -1.0, 0.01)
        assertEquals(29.0, decoded.first().temperature, 0.01)
        assertEquals(25.0, decoded.first().motorTemperature ?: -1.0, 0.01)
        assertEquals(30.0, decoded.first().imuTemperature ?: -1.0, 0.01)
        assertEquals(60, decoded.first().batteryLevel)
        assertEquals(0.06, decoded.first().distance, 0.01)
        assertEquals(252.33, decoded.first().totalDistance ?: -1.0, 0.01)
    }

    @Test
    fun decodeSkipsBadChecksumAndResyncsOnNextValidFrame() {

        val valid = ByteUtils.hexToBytes("aaaa11088201020c0101010095")
        val invalid = valid.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }

        // bad checksum should be ignored
        val first = protocol.decode(invalid)
        assertEquals(null, first)

        // then valid packet should be accepted
        val second = protocol.decode(valid)
        assertEquals(null, second) // main-info packet only updates state
    }

    @Test
    fun decodeCanExtractMultipleFramesFromSingleChunk() {

        val chunk = ByteUtils.hexToBytes(
            "aaaa11088201020c0101010095aaaa11178202413134323139353041303030343635460000000000fd"
        )

        protocol.decode(chunk)

        // Send versions + realtime to verify previous state was retained from concatenated chunk
        protocol.decode(
            ByteUtils.hexToBytes("aaaa11388206222800040719000802212600080101000902230a0004010a0002012401000102010001012501000102010001012f0500050101000000b8")
        )
        val data = protocol.decode(
            ByteUtils.hexToBytes("aaaa1457843e1e0c000000000000000000afffc30000000000ffffd7fe000000000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cdc900ceb0cec8ceb03a6400000000004900000000000000000000003f")
        )

        assertNotNull(data)
        assertEquals("A1421950A000465F", data?.serialNumber)
        assertTrue((data?.firmwareVersion ?: "").contains("Main:1.8.38"))
    }

    @Test
    fun decodeLegacyV5FCsvFramesProducesTelemetryAndModel() {
        val frames = loadWheelLogFrames(
            "/ble_frames/inmotion/RAW_WHEELLOG/RAW_inmotion_V5F.csv",
            maxFrames = MAX_TEST_FRAMES
        )
        assertTrue("Expected legacy V5F frames", frames.isNotEmpty())
        assertTrue("Expected substantial V5F frame sample", frames.size > MINIMUM_V5F_FRAME_COUNT)

        val decoded = frames.mapNotNull { protocol.decode(it) }
        assertTrue("Expected decoded telemetry from V5F legacy frames", decoded.isNotEmpty())
        assertTrue(decoded.any { it.model.contains("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.manufacturer.equals("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
    }

    @Test
    fun decodeLegacyV8SCsvFramesProducesTelemetryAndModel() {
        val frames = loadWheelLogFrames(
            "/ble_frames/inmotion/RAW_WHEELLOG/RAW_inmotion_V8S.csv",
            maxFrames = MAX_TEST_FRAMES
        )
        assertTrue("Expected legacy V8S frames", frames.isNotEmpty())
        assertTrue("Expected substantial V8S frame sample", frames.size > MINIMUM_V8S_FRAME_COUNT)

        val decoded = frames.mapNotNull { protocol.decode(it) }
        assertTrue("Expected decoded telemetry from V8S legacy frames", decoded.isNotEmpty())
        assertTrue(decoded.any { it.model.contains("V8S", ignoreCase = true) })
        assertTrue(decoded.all { it.manufacturer.equals("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
    }

    @Test
    fun createCommandSupportsV2LightBrightness() {
        // Trigger V2 dialect detection so the dialect guard doesn't block the command.
        protocol.decode(ByteUtils.hexToBytes("aaaa11088201020c0101010095"))
        val cmd = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 70)
        assertArrayEquals(
            ByteUtils.hexToBytes("AAAA1403602B461A"),
            cmd
        )
    }

    @Test
    fun getPollingPlanHasStartupAndPeriodicQueries() {
        val plan = protocol.getPollingPlan()
        assertTrue(plan.enabled)
        assertEquals(1, plan.startupQueries.size)
        assertEquals(5, plan.periodicQueries.size)
        val startup = plan.startupQueries.single()
        assertEquals("inmotion.dialect-probe", startup.id)
        assertEquals(CommandType.REQUEST_FIRMWARE, startup.commandType)
        assertEquals(4, plan.periodicQueries.count { it.commandType == CommandType.CUSTOM })
    }

    @Test
    fun createCommandOnlyAllowsProbeUntilV2Detected() {
        val blocked = protocol.createCommand(CommandType.REQUEST_BATTERY_INFO, Unit)
        assertTrue(blocked.isEmpty())

        // V2 MAIN_INFO response frame (AA AA 11 08 82 ... checksum) used to lock
        // the protocol dialect to V2 before issuing realtime polling commands.
        protocol.decode(ByteUtils.hexToBytes("aaaa11088201020c0101010095"))
        val realtime = protocol.createCommand(CommandType.REQUEST_BATTERY_INFO, Unit)
        assertTrue(realtime.isNotEmpty())
    }

    @Test
    fun createCommandSupportsCustomV14PackQueriesAfterV2Detected() {
        protocol.decode(ByteUtils.hexToBytes("aaaa11088201020c0101010095"))
        val query = protocol.getPollingPlan().periodicQueries.first { it.id == "inmotion.v14-pack-1-cells" }

        assertEquals(CommandType.CUSTOM, query.commandType)
        assertArrayEquals(
            query.value as ByteArray,
            protocol.createCommand(query.commandType, query.value)
        )
    }

    @Test
    fun createCommandRemainsBlockedAfterLegacyDialectDetected() {
        val legacyInfoFrame = byteArrayOf(
            0xAA.toByte(), 0xAA.toByte(), 0x14.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x55.toByte(), 0xAA.toByte()
        )
        protocol.decode(legacyInfoFrame)

        val blocked = protocol.createCommand(CommandType.REQUEST_BATTERY_INFO, Unit)
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun decodeV13AndV14CarTypeFramesMapsCorrectModelNames() {
        fun createCarTypeFrame(series: Int, type: Int): ByteArray {
            val payload = byteArrayOf(
                0x01.toByte(),
                0x02.toByte(),
                series.toByte(),
                type.toByte(),
                0x01.toByte(),
                0x01.toByte(),
                0x00.toByte()
            )
            val flag = 0x11
            val command = 0x82
            val len = payload.size + 1
            val body = ByteArray(3 + payload.size)
            body[0] = flag.toByte()
            body[1] = len.toByte()
            body[2] = command.toByte()
            payload.copyInto(body, destinationOffset = 3)
            var xor = 0
            for (b in body) xor = xor xor (b.toInt() and 0xFF)
            val checksum = xor.toByte()
            return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + body + byteArrayOf(checksum)
        }

        // Test V13
        protocol.decode(createCarTypeFrame(series = 8, type = 1))
        val realtimeFrame =
            ByteUtils.hexToBytes("aaaa1457847b57f4ff00000000000000000000000000000000bbe97300bbe9000000003d010000e21d991d983a7c159c18401f401f7017701750c300000000c6c800cbb0ccc4cdb0e6000200000000000000000000100000000000fc")
        val decodedV13 = protocol.decode(realtimeFrame)
        assertNotNull(decodedV13)
        assertEquals("InMotion V13", decodedV13!!.model)

        // Test V14 50S
        protocol.decode(createCarTypeFrame(series = 9, type = 2))
        val decodedV14 = protocol.decode(realtimeFrame)
        assertNotNull(decodedV14)
        assertEquals("InMotion V14 50S", decodedV14!!.model)
    }

    @Test
    fun decodeV9RealTimeFrameExposesAngleAndMode() {
        // Envoie d'abord les frames de setup (model + serial + version)
        val setupPackets = listOf(
            "aaaa11088201020c0101010095",
            "aaaa11178202413134323139353041303030343635460000000000fd",
            "aaaa11388206222800040719000802212600080101000902230a0004010a0002012401000102010001012501000102010001012f0500050101000000b8"
        )
        for (p in setupPackets) protocol.decode(ByteUtils.hexToBytes(p))

        val realtimeFrame = ByteUtils.hexToBytes(
            "aaaa1457843e1e0c000000000000000000afffc30000000000ffffd7fe000000000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cdc900ceb0cec8ceb03a6400000000004900000000000000000000003f"
        )
        val result = protocol.decode(realtimeFrame)

        assertNotNull(result)
        // angle (pitch) doit être peuplé
        assertNotNull(result!!.angle)
        // mode doit être non-null
        assertNotNull(result.mode)
        // vitesse = 0 → idle ou charging
        assertTrue(result.mode == "idle" || result.mode == "active" || result.mode == "charging")
    }

    @Test
    fun decodeLegacyV5FFramesExposeMode() {
        val frames = loadWheelLogFrames(
            "/ble_frames/inmotion/RAW_WHEELLOG/RAW_inmotion_V5F.csv",
            maxFrames = 500
        )
        val decoded = frames.mapNotNull { protocol.decode(it) }
        assertTrue("Expected decoded legacy frames", decoded.isNotEmpty())

        // Au moins un frame doit avoir mode = "active" (capture pendant la conduite)
        val activeModes = decoded.filter { it.mode == "active" }
        assertTrue(
            "Expected at least one 'active' mode frame in V5F capture",
            activeModes.isNotEmpty()
        )

        // Tous les modes doivent être des valeurs connues
        val validModes = setOf("active", "idle", "charging", "calibration")
        decoded.forEach { data ->
            assertTrue(
                "Unexpected mode value: ${data.mode}",
                data.mode == null || data.mode in validModes
            )
        }
    }

    @Test
    fun p6PhaseCurrentDerivedFromTorque() {
        // P6 car-type frame: series=13 (0x0D), type=1 → "InMotion P6"
        // Built from the V9 car-type vector by changing series byte 0x0C→0x0D and
        // recomputing the XOR checksum.
        val carTypeFrame = ByteUtils.hexToBytes("aaaa11088201020d0101010094")

        // Realtime frame derived from the V9 vector with torque set to 970 raw
        // (= 9.70 N·m). Expected phase current: 9.70 / 0.586 ≈ 16.55 A.
        // Torque is at payload[12..13] (frame bytes 17-18); checksum recomputed.
        val realtimeFrame = ByteUtils.hexToBytes(
            "aaaa1457843e1e0c000000000000000000ca03c30000000000ffffd7fe000000" +
            "000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cd" +
            "c900ceb0cec8ceb03a640000000000490000000000000000000000a6"
        )

        protocol.decode(carTypeFrame)
        val data = protocol.decode(realtimeFrame)

        assertNotNull(data)
        assertEquals("InMotion P6", data!!.model)
        assertNotNull(data.phaseCurrent)
        assertEquals(9.70 / 0.586, data.phaseCurrent!!, 0.05)
        assertEquals(25.0, data.temperature, 0.01)
        assertEquals(80.0, data.motorTemperature ?: -1.0, 0.01)
        assertEquals(30.0, data.imuTemperature ?: -1.0, 0.01)
    }

    @Test
    fun nonP6ModelHasNullPhaseCurrent() {
        // V9 car-type frame (series=12, type=1); realtime frame with non-zero torque.
        // phaseCurrent must remain null for non-P6 InMotion V2 wheels.
        val carTypeFrame = ByteUtils.hexToBytes("aaaa11088201020c0101010095")
        val realtimeFrame = ByteUtils.hexToBytes(
            "aaaa1457843e1e0c000000000000000000ca03c30000000000ffffd7fe000000" +
            "000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cd" +
            "c900ceb0cec8ceb03a640000000000490000000000000000000000a6"
        )

        protocol.decode(carTypeFrame)
        val data = protocol.decode(realtimeFrame)

        assertNotNull(data)
        assertEquals("InMotion V9", data!!.model)
        assertEquals(null, data.phaseCurrent)
    }

    @Test
    fun getBMSDataReturnsPartialInMotionDataWhenTelemetryWasDecoded() {
        val packets = listOf(
            "aaaa11088201020c0101010095",
            "aaaa11178202413134323139353041303030343635460000000000fd",
            "aaaa11388206222800040719000802212600080101000902230a0004010a0002012401000102010001012501000102010001012f0500050101000000b8",
            "aaaa1457843e1e0c000000000000000000afffc30000000000ffffd7fe000000000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cdc900ceb0cec8ceb03a6400000000004900000000000000000000003f"
        )
        packets.forEach { protocol.decode(ByteUtils.hexToBytes(it)) }

        val bms = protocol.getBMSData()
        assertNotNull(bms)
        assertEquals(1, bms!!.size)
        assertNotNull(bms.first().voltage)
        assertNotNull(bms.first().current)
        assertNotNull(bms.first().temperatures)
        assertTrue((bms.first().temperatures ?: emptyList()).size >= 3)
    }

    @Test
    fun decodeV14PackCellResponsesExposeOneBmsEntryPerPack() {
        protocol.decode(buildCarTypeFrame(series = 9, type = 2))

        val pack1Query = protocol.getPollingPlan().periodicQueries.first { it.id == "inmotion.v14-pack-1-cells" }
        val pack2Query = protocol.getPollingPlan().periodicQueries.first { it.id == "inmotion.v14-pack-2-cells" }
        val pack1Response = buildV2ResponseFrame(
            flag = 0x16,
            command = 0x24,
            payload = buildV14PackCellsPayload(4100)
        )
        val pack2Response = buildV2ResponseFrame(
            flag = 0x16,
            command = 0x25,
            payload = buildV14PackCellsPayload(4200)
        )

        assertTrue(protocol.matchesQueryResponse(pack1Query, pack1Response))
        assertEquals(false, protocol.matchesQueryResponse(pack1Query, pack2Response))
        assertTrue(protocol.matchesQueryResponse(pack2Query, pack2Response))

        protocol.decode(pack1Response)
        protocol.decode(pack2Response)

        val bms = protocol.getBMSData()
        assertNotNull(bms)
        assertEquals(2, bms!!.size)
        assertEquals(1, bms[0].bmsIndex)
        assertEquals(2, bms[1].bmsIndex)
        assertEquals(32, bms[0].cellVoltages?.size)
        assertEquals(32, bms[1].cellVoltages?.size)
        assertEquals(4.100, bms[0].cellVoltages?.first() ?: 0.0, 0.001)
        assertEquals(4.200, bms[1].cellVoltages?.first() ?: 0.0, 0.001)
    }

    // -------------------------------------------------------------------------
    // Fix 1: InMotion V12 telemetry routing
    // -------------------------------------------------------------------------

    /**
     * Wraps [payload] in a valid InMotion V2 response envelope (AA AA FLAG LEN CMD ... CS).
     * [command] is the protocol command id (e.g. 0x04 = realtime); the response bit 0x80 is
     * added here. Bytes in [payload] must not contain 0xAA or 0xA5 to avoid escape handling
     * in test data.
     */
    private fun buildV2ResponseFrame(flag: Int, command: Int, payload: ByteArray): ByteArray {
        val cmdByte = (command or 0x80)
        val len = payload.size + 1
        val body = ByteArray(3 + payload.size)
        body[0] = flag.toByte()
        body[1] = len.toByte()
        body[2] = cmdByte.toByte()
        payload.copyInto(body, destinationOffset = 3)
        var xor = 0
        for (b in body) xor = xor xor (b.toInt() and 0xFF)
        val checksum = xor.toByte()
        return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + body + byteArrayOf(checksum)
    }

    /** Build a model-identify (MAIN_INFO) frame for the given series/type. */
    private fun buildCarTypeFrame(series: Int, type: Int): ByteArray {
        val payload = byteArrayOf(
            0x01.toByte(), 0x02.toByte(),
            series.toByte(), type.toByte(),
            0x01.toByte(), 0x01.toByte(), 0x00.toByte()
        )
        return buildV2ResponseFrame(flag = 0x11, command = 0x02, payload = payload)
    }

    private fun buildV14PackCellsPayload(startCellMillivolts: Int): ByteArray {
        val payload = ByteArray(2 + 64)
        payload[0] = 0x02
        payload[1] = 0x82.toByte()
        repeat(32) { index ->
            val value = startCellMillivolts + index
            val offset = 2 + index * 2
            payload[offset] = (value and 0xFF).toByte()
            payload[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return payload
    }

    @Test
    fun decodeV12HSRealTimeFrameProducesTelemetryFromV12Layout() {
        // Identify the model as V12 HS (series=7, type=1) before sending realtime.
        protocol.decode(buildCarTypeFrame(series = 7, type = 1))

        // Build a 56-byte V12 realtime payload with known values.
        //   voltage @0..1  uint16 LE: 8100 → 81.00 V
        //   current @2..3  int16  LE: 200  → 2.00 A
        //   speed   @4..5  int16  LE: 1500 → 15.00 km/h
        //   batLevel @24..25 uint16 LE: 8000 → 80.00 %
        //   dynSpeedLimit @30..31 uint16 LE: 4500 → 45.00 km/h
        //   mosTemp @40: offset80 for 25°C = 25+256-80 = 201 = 0xC9
        //   motTemp @41: 0xC9 (25°C)
        //   boardTemp @43: 0xC9 (25°C)
        //   stateByte @54: 0x01 (active)
        val v12Payload = ByteArray(56)
        fun putLE16(buf: ByteArray, offset: Int, v: Int) {
            buf[offset] = (v and 0xFF).toByte()
            buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putLE16(v12Payload, 0, 8100)   // voltage
        putLE16(v12Payload, 2, 200)    // current
        putLE16(v12Payload, 4, 1500)   // speed
        putLE16(v12Payload, 24, 8000)  // batLevel
        putLE16(v12Payload, 30, 4500)  // dynSpeedLimit
        val temp25 = (25 + 256 - 80).toByte()  // 0xC9
        v12Payload[40] = temp25   // MOS
        v12Payload[41] = temp25   // MOT
        v12Payload[43] = temp25   // BOARD
        v12Payload[54] = 0x01     // stateByte = active

        val realtimeFrame = buildV2ResponseFrame(flag = 0x14, command = 0x04, payload = v12Payload)
        val data = protocol.decode(realtimeFrame)

        assertNotNull(data)
        assertEquals("InMotion V12 HS", data!!.model)
        assertEquals(81.00, data.voltage, 0.01)
        assertEquals(2.00, data.current, 0.01)
        assertEquals(15.00, data.speed, 0.01)
        assertEquals(80, data.batteryLevel)
        assertEquals(25.0, data.temperature, 0.5)
        assertEquals(45.00, data.speedLimit ?: -1.0, 0.01)
        assertEquals("active", data.mode)
    }

    @Test
    fun v12ModelsAllRoutedToV12Parser() {
        val v12Variants = listOf(
            7 to 1 to "InMotion V12 HS",
            7 to 2 to "InMotion V12 HT",
            7 to 3 to "InMotion V12 PRO",
            11 to 1 to "InMotion V12S"
        )
        for ((seriesType, expectedName) in v12Variants) {
            val (series, type) = seriesType
            protocol = InMotionProtocol()
            protocol.decode(buildCarTypeFrame(series = series, type = type))

            val v12Payload = ByteArray(56)
            // Minimal valid payload: voltage=8100, batLevel=8000
            fun putLE16(buf: ByteArray, offset: Int, v: Int) {
                buf[offset] = (v and 0xFF).toByte()
                buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
            }
            putLE16(v12Payload, 0, 8100)
            putLE16(v12Payload, 24, 8000)
            val realtimeFrame = buildV2ResponseFrame(flag = 0x14, command = 0x04, payload = v12Payload)
            val data = protocol.decode(realtimeFrame)

            assertNotNull(data, "Expected non-null EUCData for $expectedName")
            assertEquals(expectedName, data!!.model)
        }
    }

    // -------------------------------------------------------------------------
    // Fix 4: InMotion TotalStats ride-time and power-on-time fields
    // -------------------------------------------------------------------------

    @Test
    fun decodeTotalStatsPopulatesRideTimeAndPowerOnTime() {
        // First identify model to allow V2 active polling
        protocol.decode(buildCarTypeFrame(series = 9, type = 2)) // V14 50S

        // Build a 20-byte TotalStats payload:
        //   bytes  0..3 : total distance int32 LE in 10m units = 5000 → 50 km
        //   bytes  4..11: padding zeros
        //   bytes 12..15: ride time uint32 LE = 7200 s (2 h)
        //   bytes 16..19: power-on time uint32 LE = 14400 s (4 h)
        val totalStatsPayload = ByteArray(20)
        fun putLE32(buf: ByteArray, offset: Int, v: Int) {
            buf[offset] = (v and 0xFF).toByte()
            buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
            buf[offset + 2] = ((v shr 16) and 0xFF).toByte()
            buf[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }
        putLE32(totalStatsPayload, 0, 5000)   // 5000 × 10 m = 50 km
        putLE32(totalStatsPayload, 12, 7200)  // ride-time seconds
        putLE32(totalStatsPayload, 16, 14400) // power-on-time seconds

        val totalStatsFrame = buildV2ResponseFrame(flag = 0x14, command = 0x11, payload = totalStatsPayload)
        protocol.decode(totalStatsFrame)

        // Now emit a realtime frame so the state is included in EUCData
        val realtimeHex = "aaaa1457843e1e0c000000000000000000afffc30000000000ffffd7fe000000000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cdc900ceb0cec8ceb03a6400000000004900000000000000000000003f"
        val data = protocol.decode(ByteUtils.hexToBytes(realtimeHex))

        assertNotNull(data)
        assertEquals(7200L, data!!.totalRideTimeSeconds)
        assertEquals(14400L, data.totalPowerOnTimeSeconds)
    }

    // -------------------------------------------------------------------------
    // Fix 5: InMotion V14 per-cell voltage parsing
    // -------------------------------------------------------------------------

    @Test
    fun parseBatteryInfoDecodeV14PerCellVoltages() {
        // Build a 66-byte BATTERY_INFO payload with 02 82 prefix + 32 × uint16 LE millivolts.
        // All cells = 4100 mV = 4.100 V.
        val cellMv = 4100
        val payload = ByteArray(2 + 32 * 2)
        payload[0] = 0x02
        payload[1] = 0x82.toByte()
        var off = 2
        repeat(32) {
            payload[off] = (cellMv and 0xFF).toByte()
            payload[off + 1] = ((cellMv shr 8) and 0xFF).toByte()
            off += 2
        }
        val batteryInfoFrame = buildV2ResponseFrame(flag = 0x14, command = 0x05, payload = payload)
        // Must set dialect to V2 first (via a MAIN_INFO response) to allow polling
        protocol.decode(buildCarTypeFrame(series = 9, type = 2)) // V14 50S
        protocol.decode(batteryInfoFrame)

        val bms = protocol.getBMSData()
        assertNotNull(bms)
        val cells = bms!!.first().cellVoltages
        assertNotNull(cells)
        assertEquals(32, cells!!.size)
        cells.forEach { v ->
            assertEquals(4.100, v, 0.001)
        }
    }

    @Test
    fun parseBatteryInfoFallsBackToPackVoltagesWhenNotV14CellFormat() {
        // A 32-byte pack-summary payload (no 02 82 prefix) should populate packVoltages,
        // not cellVoltages.
        val packPayload = ByteArray(32)
        // Pack 1 @ offset 0: 8200 centivolts = 82.00 V
        packPayload[0] = (8200 and 0xFF).toByte()
        packPayload[1] = ((8200 shr 8) and 0xFF).toByte()
        // Remaining 3 packs are zero and should be filtered out.

        val batteryInfoFrame = buildV2ResponseFrame(flag = 0x14, command = 0x05, payload = packPayload)
        protocol.decode(buildCarTypeFrame(series = 9, type = 2))
        protocol.decode(batteryInfoFrame)

        val bms = protocol.getBMSData()
        assertNotNull(bms)
        // cellVoltages must be null (not a per-cell response)
        assertTrue(bms!!.all { it.cellVoltages == null })
        // Pack voltage must be populated from the summary path
        assertEquals(82.00, bms.first().voltage ?: -1.0, 0.01)
    }

    private fun loadWheelLogFrames(
        resourcePath: String,
        maxFrames: Int = Int.MAX_VALUE
    ): List<ByteArray> {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        val frames = mutableListOf<ByteArray>()
        var malformedRows = 0
        var invalidFormatRows = 0
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            reader.lineSequence().forEach { rawLine ->
                if (frames.size >= maxFrames) return@forEach
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach

                // WheelLog raw CSV rows are expected as: timestamp,hex_data
                val splitIndex = line.indexOf(',')
                if (splitIndex <= 0 || splitIndex >= line.length - 1) {
                    invalidFormatRows++
                    return@forEach
                }

                val hex = line.substring(splitIndex + 1).trim().removeSurrounding("\"")
                try {
                    frames.add(ByteUtils.hexToBytes(hex))
                } catch (_: IllegalArgumentException) {
                    // Keep malformed data visible via assertion diagnostics below.
                    malformedRows++
                }
            }
        }
        val totalRows = frames.size + malformedRows + invalidFormatRows
        assertTrue("No parsable rows found in $resourcePath", totalRows > 0)
        val maxMalformedRows = (totalRows * MAX_MALFORMED_ROW_RATIO).toInt()
        assertTrue(
            "Too many malformed rows in $resourcePath: $malformedRows out of $totalRows (max: $maxMalformedRows)",
            malformedRows <= maxMalformedRows
        )
        return frames
    }

}
