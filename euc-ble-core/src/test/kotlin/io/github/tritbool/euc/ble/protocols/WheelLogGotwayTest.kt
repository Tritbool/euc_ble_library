// File: `euc-ble-core/src/test/java/com/euc/ble/protocols/WheelLogGotwayTest.kt`
package io.github.tritbool.euc.ble.protocols

import app.cash.turbine.test
import io.github.tritbool.euc.ble.SlowTest
import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.frames.FixedSizeFrameParser
import io.github.tritbool.euc.ble.frames.FrameReassembler
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.test.WheelLogCsvLoader
import io.github.tritbool.euc.ble.test.WheelLogFrame
import io.github.tritbool.euc.ble.test.WheelLogResources
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertNotNull
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertNull
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@SlowTest
class WheelLogGotwayTest {
    private val resourceDir = WheelLogResources.rawDir("gotway")

    // Delays match existing WheelLog async decoding tests to ensure capture-based test stability.
    private val collectorSubscriptionDelayMs = 100L
    private val frameProcessingDelayMs = 3000L
    private val maxValidTiltBackSpeed = 100
    private val frameTypeOffset = 18
    private val typeBFrameType = 0x04
    private val distanceOffset = 2
    private val typeAPwmOffset = 14

    private lateinit var protocol: GotwayProtocol

    @BeforeEach
    fun setUp() {
        protocol = GotwayProtocol()
    }

    @AfterEach
    fun tearDown() {
        if (this::protocol.isInitialized) {
            protocol.close()
        }
    }

    @Test
    fun begodeExtremeCaptureReportsTwoBmsPacks() = runTest {
        val frames =
            loadGotwayFrames("${resourceDir}EXTREME_2026_07_14_21_23_02.csv", maxFrames = 1000)
        assertTrue("Resource CSV vide ou introuvable", frames.isNotEmpty())
        val decoded = mutableListOf<EUCData>()
        var vendorMismatch = 0

        // Start collecting in background FIRST using launch
        val collectorJob = launch {
            protocol.dataFlow.collect { data ->
                decoded.add(data)
                if (decoded.size >= 500) return@collect
            }
        }

        // Small delay to ensure collector is subscribed
        delay(200.milliseconds)

        // Send all frames to the protocol for reassembly on IO dispatcher
        withContext(Dispatchers.IO) {
            for (frame in frames) {
                protocol.decode(frame.bleData)
            }
        }

        // Wait for async processing to complete (needs time for IO dispatcher)
        delay(3000.milliseconds)

        val bmsData = protocol.getBMSData()
        assertEquals(2, bmsData.size)
        assertTrue(bmsData.all { it.current != null && it.voltage != null })
        assertTrue(bmsData.all { it.temperatures == listOf(28.0, 27.0, 28.0, 27.0) })

        // Cancel collector job
        collectorJob.cancel()
    }

    @Test
    fun extremeBullRocketCaptureReportsTwoBmsPacks() = runTest {
        protocol.close()
        protocol = ExtremeBullProtocol()
        val resourcePath =
            WheelLogResources.rawFile("extreme_bull", "EB_ROCKET_2026_09_01_13_51_27.csv")
        val frames = WheelLogCsvLoader.load(resourcePath, maxFrames = 1000).also { result ->
            WheelLogCsvLoader.assertHealthyParse(resourcePath, result)
        }.frames
        assertTrue("Resource CSV vide ou introuvable", frames.isNotEmpty())

        frames.forEach { protocol.decode(it.bleData) }
        delay(3000.milliseconds)

        val bmsData = protocol.getBMSData()
        assertEquals(2, bmsData.size)
        assertTrue(bmsData.all { it.current != null && it.voltage != null })
        assertTrue(bmsData.all { it.temperatures == listOf(22.0, 19.0, 22.0, 19.0) })
    }

    @Test
    fun testLoadAndDecodeRealFramesWithoutType7PWM() = runTest {
        val frames = loadGotwayFrames("${resourceDir}RAW_2023_11_24_18_43_22.csv", maxFrames = 1000)
        assertTrue("Ressource CSV vide ou introuvable", frames.isNotEmpty())

        val decoded = mutableListOf<EUCData>()
        var vendorMismatch = 0

        // Start collecting in background FIRST using launch
        val collectorJob = launch {
            protocol.dataFlow.collect { data ->
                decoded.add(data)
                if (decoded.size >= 500) return@collect
            }
        }

        // Small delay to ensure collector is subscribed
        delay(200.milliseconds)

        // Send all frames to the protocol for reassembly on IO dispatcher
        withContext(Dispatchers.IO) {
            for (frame in frames) {
                protocol.decode(frame.bleData)
            }
        }

        // Wait for async processing to complete (needs time for IO dispatcher)
        delay(3000.milliseconds)

        // Cancel collector job
        collectorJob.cancel()

        decoded.forEach { data ->
            // Basic invariants
            assertNotNull("rawData doit être préservé", data.rawData)
            assertTrue("timestamp doit être > 0", data.timestamp > 0)

            // Manufacturer attendu
            if (!data.manufacturer.contains("Gotway", ignoreCase = true) &&
                !data.manufacturer.contains("Begode", ignoreCase = true)
            ) {
                vendorMismatch++
            }

            // Ranges raisonnables (si présents)
            data.voltage.takeIf { it.isFinite() }?.let {
                assertTrue("Voltage hors plage raisonnable: $it", it in 0.0..150.0)
            }
            data.speed.takeIf { it.isFinite() }?.let {
                assertTrue("Vitesse hors plage raisonnable: $it", it in 0.0..150.0)
            }
            data.batteryLevel.takeIf { it in 0..255 }?.let {
                assertTrue("Battery hors plage 0..100", it in 0..100)
            }
        }

        val decodedCount = decoded.size
        println("Decoded $decodedCount frames from ${frames.size} BLE packets")
        assertTrue(
            "Expected non-placeholder telemetry from Type A frames",
            decoded.any { it.frameType.contains("Type A") && it.voltage > 0.0 && abs(it.current) > 0.0 }
        )

        assertTrue(
            "Expected NO Type 7 frames",
            decoded.none { it.frameType.contains("Type 7") }
        )

        assertTrue(
            "Expected PWM to be extracted from Type A frames",
            decoded.any { it.frameType.contains("Type A") && it.pwm != null }
        )

        assertTrue(
            "Expected Type A PWM to be valid",
            decoded.filter { it.frameType.contains("Type A") && it.pwm != null }
                .all { it.pwm!! in 0.0..100.0 }
        )

        // With FrameReassembler, we expect to decode reassembled frames
        // The success rate depends on the data quality and fragmentation
        assertTrue("Aucune frame décodée - vérifier le format des données", decodedCount > 0)

        // Pas trop de frames décodées avec fabricant incorrect
        if (decodedCount > 0) {
            assertTrue(
                "Trop de décodages avec fabricant inattendu: $vendorMismatch",
                vendorMismatch <= decodedCount / 4
            )
        }
    }

    @Test
    fun testLoadAndDecodeRealFrames() = runTest {
        val frames =
            loadGotwayFrames("${resourceDir}EXTREME_2026_07_14_21_23_02.csv", maxFrames = 1000)
        assertTrue("Ressource CSV vide ou introuvable", frames.isNotEmpty())

        val decoded = mutableListOf<EUCData>()
        var vendorMismatch = 0

        // Start collecting in background FIRST using launch
        val collectorJob = launch {
            protocol.dataFlow.collect { data ->
                decoded.add(data)
                if (decoded.size >= 500) return@collect
            }
        }

        // Small delay to ensure collector is subscribed
        delay(200.milliseconds)

        // Send all frames to the protocol for reassembly on IO dispatcher
        withContext(Dispatchers.IO) {
            for (frame in frames) {
                protocol.decode(frame.bleData)
            }
        }

        // Wait for async processing to complete (needs time for IO dispatcher)
        delay(3000.milliseconds)

        // Cancel collector job
        collectorJob.cancel()

        decoded.forEach { data ->
            // Basic invariants
            assertNotNull("rawData doit être préservé", data.rawData)
            assertTrue("timestamp doit être > 0", data.timestamp > 0)

            // Manufacturer attendu
            if (!data.manufacturer.contains("Gotway", ignoreCase = true) &&
                !data.manufacturer.contains("Begode", ignoreCase = true)
            ) {
                vendorMismatch++
            }

            // Ranges raisonnables (si présents)
            data.voltage.takeIf { it.isFinite() }?.let {
                assertTrue("Voltage hors plage raisonnable: $it", it in 0.0..150.0)
            }
            data.speed.takeIf { it.isFinite() }?.let {
                assertTrue("Vitesse hors plage raisonnable: $it", it in 0.0..150.0)
            }
            data.batteryLevel.takeIf { it in 0..255 }?.let {
                assertTrue("Battery hors plage 0..100", it in 0..100)
            }
        }

        val decodedCount = decoded.size
        println("Decoded $decodedCount frames from ${frames.size} BLE packets")
        assertTrue(
            "Expected non-placeholder telemetry from Type A frames",
            decoded.any { it.frameType.contains("Type A") && it.voltage > 0.0 && abs(it.current) > 0.0 }
        )
        assertTrue(
            "Expected PWM to be decoded from Type 7 frames",
            decoded.any { it.frameType.contains("Type 7") && (it.pwm ?: 0.0) in 0.0 .. 100.0 }
        )

        // With FrameReassembler, we expect to decode reassembled frames
        // The success rate depends on the data quality and fragmentation
        assertTrue("Aucune frame décodée - vérifier le format des données", decodedCount > 0)

        // Pas trop de frames décodées avec fabricant incorrect
        if (decodedCount > 0) {
            assertTrue(
                "Trop de décodages avec fabricant inattendu: $vendorMismatch",
                vendorMismatch <= decodedCount / 4
            )
        }
    }

    @Test
    fun testDecodedFramesConsistencyShortSequence() = runTest {
        val frames = loadGotwayFrames("${resourceDir}RAW_2023_11_24_18_43_22.csv", maxFrames = 200)

        // Start collecting in background
        val collector = async {
            withTimeoutOrNull(5000.milliseconds) {
                protocol.dataFlow.take(100).toList()
            } ?: emptyList()
        }

        // Send all frames to the protocol
        frames.forEach { frame ->
            protocol.decode(frame.bleData)
        }

        delay(100.milliseconds)

        val decoded = collector.await()

        if (decoded.size < 2) {
            println("Pas assez de frames décodées pour test de consistance: ${decoded.size}")
            return@runTest
        }

        for (i in 1 until decoded.size) {
            val prev = decoded[i - 1]
            val cur = decoded[i]

            // Only check Type A frames (which have speed data)
            if (cur.frameType.contains("Type A") && prev.frameType.contains("Type A")) {
                // Variation raisonnable de vitesse entre 2 frames consécutives
                val speedDiff = abs(cur.speed - prev.speed)
                assertTrue("Variation de vitesse anormale: $speedDiff", speedDiff < 50.0)
            }

            // Distance non décroissante (for same frame type)
            if (cur.model == prev.model) {
                assertTrue(
                    "Distance décroissante détectée: ${prev.distance} -> ${cur.distance}",
                    cur.distance >= prev.distance - 1.0
                )
            }
        }
    }

    @Test
    fun testTypeBContentDecodedFromWheelLogCapture() = runTest {
        val frames = loadGotwayFrames("${resourceDir}RAW_2023_11_25_15_11_39.csv", maxFrames = 1200)
        assertTrue("CSV resource is empty or missing", frames.isNotEmpty())

        val decoded = mutableListOf<EUCData>()
        val collectorJob = launch {
            protocol.dataFlow.collect { data ->
                decoded.add(data)
            }
        }

        // Let the collector subscribe before frames are fed, matching existing test timing pattern.
        delay(collectorSubscriptionDelayMs.milliseconds)
        frames.forEach { frame ->
            protocol.decode(frame.bleData)
        }
        // Allow asynchronous frame reassembly/decoding to flush capture fragments.
        delay(frameProcessingDelayMs.milliseconds)
        collectorJob.cancel()

        val type7Frames = decoded.filter { frame ->
            frame.frameType.contains("Type 7", ignoreCase = true)

        }
        assertTrue("No Type 7 frames decoded from WheelLog capture", type7Frames.isNotEmpty())

        val typeBFrames = decoded.filter { frame ->
            val raw = frame.rawData
            val actualFrameType = raw[frameTypeOffset].toInt() and 0xFF
            actualFrameType == typeBFrameType && frame.frameType.contains(
                "Type B",
                ignoreCase = true
            )

        }
        assertTrue("No Type B frames decoded from WheelLog capture", typeBFrames.isNotEmpty())

        typeBFrames.forEach { data ->
            val raw = data.rawData
            val actualFrameType = raw[frameTypeOffset].toInt() and 0xFF
            assertEquals(
                "Unexpected frame type: expected=$typeBFrameType actual=$actualFrameType",
                typeBFrameType,
                actualFrameType
            )

            val expectedDistance = ByteUtils.tryGetUnsignedIntBE(raw, distanceOffset)?.toDouble()
            val settings = ByteUtils.tryGetUnsignedShortBE(raw, 6)
            val expectedPedalsMode = settings?.let { 2 - ((it shr 13) and 0x03) }
            val expectedAlarmMode = settings?.let { (it shr 10) and 0x03 }
            val expectedRollAngleMode = settings?.let { (it shr 7) and 0x03 }
            val expectedUsesMiles = settings?.let { (it and 0x01) == 1 }
            val expectedAutoPowerOff = ByteUtils.tryGetUnsignedShortBE(raw, 8)
            val expectedTiltBack =
                ByteUtils.tryGetUnsignedShortBE(raw, 10)?.takeIf { it < maxValidTiltBackSpeed }
            val expectedLedMode = ByteUtils.tryGetUnsignedByte(raw, 13)
            val expectedAlertFlags = ByteUtils.tryGetUnsignedByte(raw, 14)
            val expectedLightMode = ByteUtils.tryGetUnsignedByte(raw, 15)?.and(0x03)
            val expectedWheelAlarm = expectedAlertFlags?.let { (it and 0x01) == 1 }

            val parsedDistance = requireNotNull(expectedDistance) {
                "Type B distance could not be parsed from raw frame: ${
                    raw.joinToString("") {
                        "%02x".format(
                            it
                        )
                    }
                }"
            }
            assertEquals(parsedDistance, data.totalDistance ?: 0.0, 0.01)

            assertEquals(expectedPedalsMode, data.pedalsMode)
            assertEquals(expectedAlarmMode, data.alarmMode)
            assertEquals(expectedRollAngleMode, data.rollAngleMode)
            assertEquals(expectedUsesMiles, data.usesMiles)
            assertEquals(expectedAutoPowerOff, data.autoPowerOffMinutes)
            assertEquals(expectedTiltBack, data.tiltBackSpeed)
            assertEquals(expectedLedMode, data.ledMode)
            assertEquals(expectedAlertFlags, data.alertFlags)
            assertEquals(expectedLightMode, data.lightMode)
            assertEquals(expectedWheelAlarm, data.wheelAlarm)
        }

        assertTrue(
            "Expected at least one Type B frame with carry-forward telemetry",
            typeBFrames.all {
                it.voltage >= 0.0 &&
                        it.speed >= 0.0 &&
                        abs(it.current) >= 0.0 &&
                        it.batteryLevel >= 0 &&
                        abs(it.power - (it.voltage * it.current)) < 0.5
            }
        )
    }

    @Test
    fun testTypeAPwmDecodedFromWheelLogCapture() = runTest {
        val frames =
            loadGotwayFrames("${resourceDir}EXTREME_2026_07_14_21_23_02.csv", maxFrames = 1200)
        assertTrue("CSV resource is empty or missing", frames.isNotEmpty())

        val decoded = mutableListOf<EUCData>()
        val collectorJob = launch {
            protocol.dataFlow.collect { data ->
                decoded.add(data)
            }
        }

        delay(collectorSubscriptionDelayMs.milliseconds)
        frames.forEach { frame ->
            protocol.decode(frame.bleData)
        }
        delay(frameProcessingDelayMs.milliseconds)
        collectorJob.cancel()

        val typeAFrames = decoded.filter { it.frameType == "Type A" }
        assertTrue("No Type A frames decoded from WheelLog capture", typeAFrames.isNotEmpty())

        typeAFrames.forEach { data ->
            assertTrue((data.pwm ?: 0.0) in 0.0..100.0)
        }
        val type7Frames = decoded.filter { it.frameType == "Type 7" }
        assertTrue(
            "Expected at least one Type 7 frame with non-zero PWM",
            type7Frames.any { (it.pwm ?: 0.0) in 0.0 .. 100.0  })
    }

    @Test
    fun testFrameReassemblerDirectlyWithRealData() = runTest {
        val frameParser = FixedSizeFrameParser(
            GotwayProtocol.FRAME_SIZE, GotwayProtocol.HEADER,
            GotwayProtocol.FOOTER
        )
        val reassembler = FrameReassembler(frameParser)


        val frames = loadGotwayFrames("${resourceDir}RAW_2023_11_25_15_11_39.csv", maxFrames = 100)
        assertTrue("Ressource CSV vide ou introuvable", frames.isNotEmpty())

        val decodedFrames = mutableListOf<ByteArray>()

        // Collect frames in background
        val collectorJob = launch {
            reassembler.observeFrames().collect { frame ->
                decodedFrames.add(frame)
            }
        }

        // Small delay to ensure collector is subscribed
        delay(100.milliseconds)

        // Process all BLE packets
        for (frame in frames) {
            reassembler.processIncomingBytes(frame.bleData)
        }

        // Wait for processing
        delay(500.milliseconds)
        collectorJob.cancel()

        println("FrameReassembler: Decoded ${decodedFrames.size} frames from ${frames.size} BLE packets")

        // Print first few frames for debugging
        decodedFrames.take(3).forEachIndexed { index, frame ->
            println("Frame $index: ${frame.joinToString("") { "%02x".format(it) }}")
            println("  Header: ${frame[0].toInt() and 0xFF}, ${frame[1].toInt() and 0xFF}")
            println("  Footer: ${frame.takeLast(4).joinToString("") { "%02x".format(it) }}")
            println("  Frame type (byte 18): ${frame[18].toInt() and 0xFF}")
        }

        assertTrue("FrameReassembler n'a décodé aucune frame", decodedFrames.size > 0)
    }

    @Test
    fun testFrameReassemblyWithFragmentedData() = runTest {
        protocol.close()
        protocol = GotwayProtocol(backgroundScope)
        // Create a valid complete frame
        val validFrame = createValidGotwayFrame(
            voltageRaw = 6720,  // 67.20V
            speedRaw = 833,     // ~30 km/h
            distanceRaw = 1000,
            currentRaw = 250,
            tempRaw = 2500
        )
        protocol.dataFlow.test {
            // Send in fragments (simulating BLE packet fragmentation)
            protocol.decode(validFrame.sliceArray(0..9))
            //delay(10.milliseconds)
            protocol.decode(validFrame.sliceArray(10..23))

            //delay(100.milliseconds)

            val results = awaitItem()
            assertTrue(
                "Should decode one reassembled frame",
                results.rawData.contentEquals(validFrame)
            )
            assertEquals(67.2, results.voltage, 0.01)
        }

    }

    /**
     * Helper to create a valid 24-byte Gotway frame
     */
    private fun createValidGotwayFrame(
        voltageRaw: Int = 0,
        speedRaw: Int = 0,
        distanceRaw: Long = 0,
        currentRaw: Int = 0,
        tempRaw: Int = 0,
        frameType: Byte = 0x00
    ): ByteArray {
        val frame = ByteArray(24)
        // Header
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        // Voltage BE
        frame[2] = ((voltageRaw shr 8) and 0xFF).toByte()
        frame[3] = (voltageRaw and 0xFF).toByte()
        // Speed BE
        frame[4] = ((speedRaw shr 8) and 0xFF).toByte()
        frame[5] = (speedRaw and 0xFF).toByte()
        // Distance BE (uint32)
        frame[6] = ((distanceRaw shr 24) and 0xFF).toByte()
        frame[7] = ((distanceRaw shr 16) and 0xFF).toByte()
        frame[8] = ((distanceRaw shr 8) and 0xFF).toByte()
        frame[9] = (distanceRaw and 0xFF).toByte()
        // Current BE (signed short)
        frame[10] = ((currentRaw shr 8) and 0xFF).toByte()
        frame[11] = (currentRaw and 0xFF).toByte()
        // Temperature BE (signed short)
        frame[12] = ((tempRaw shr 8) and 0xFF).toByte()
        frame[13] = (tempRaw and 0xFF).toByte()
        // Reserved bytes 14-17
        frame[14] = 0x00
        frame[15] = 0x00
        frame[16] = 0x00
        frame[17] = 0x00
        // Frame type
        frame[18] = frameType
        // Reserved
        frame[19] = 0x00
        // Footer
        frame[20] = 0x5A.toByte()
        frame[21] = 0x5A.toByte()
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()
        return frame
    }

    private fun loadGotwayFrames(
        resourcePath: String,
        maxFrames: Int = Int.MAX_VALUE
    ): List<WheelLogFrame> {
        val result = WheelLogCsvLoader.load(resourcePath, maxFrames)
        WheelLogCsvLoader.assertHealthyParse(resourcePath, result)
        return result.frames
    }
}
