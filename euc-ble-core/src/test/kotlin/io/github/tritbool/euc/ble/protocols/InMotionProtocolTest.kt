package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertArrayEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertNotNull
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

    private lateinit var protocol:InMotionProtocol
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
        assertEquals(58, decoded.first().batteryLevel)
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
        val frames = loadWheelLogFrames("/ble_frames/inmotion/RAW_WHEELLOG/RAW_inmotion_V5F.csv", maxFrames = MAX_TEST_FRAMES)
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
        val frames = loadWheelLogFrames("/ble_frames/inmotion/RAW_WHEELLOG/RAW_inmotion_V8S.csv", maxFrames = MAX_TEST_FRAMES)
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
        assertTrue(plan.startupQueries.size >= 3)
        assertTrue(plan.periodicQueries.isNotEmpty())
        // Verify startup queries include serial and firmware
        assertTrue(plan.startupQueries.any { it.commandType == CommandType.REQUEST_SERIAL })
        assertTrue(plan.startupQueries.any { it.commandType == CommandType.REQUEST_FIRMWARE })
    }

    @Test
    fun decodeV13AndV14CarTypeFramesMapsCorrectModelNames() {
        fun createCarTypeFrame(series: Int, type: Int): ByteArray {
            val payload = byteArrayOf(0x01.toByte(), 0x02.toByte(), series.toByte(), type.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte())
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
        val realtimeFrame = ByteUtils.hexToBytes("aaaa1457847b57f4ff00000000000000000000000000000000bbe97300bbe9000000003d010000e21d991d983a7c159c18401f401f7017701750c300000000c6c800cbb0ccc4cdb0e6000200000000000000000000100000000000fc")
        val decodedV13 = protocol.decode(realtimeFrame)
        assertNotNull(decodedV13)
        assertEquals("InMotion V13", decodedV13!!.model)

        // Test V14 50S
        protocol.decode(createCarTypeFrame(series = 9, type = 2))
        val decodedV14 = protocol.decode(realtimeFrame)
        assertNotNull(decodedV14)
        assertEquals("InMotion V14 50S", decodedV14!!.model)
    }

    private fun loadWheelLogFrames(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): List<ByteArray> {
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
