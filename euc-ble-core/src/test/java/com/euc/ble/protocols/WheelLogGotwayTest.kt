// File: `euc-ble-core/src/test/java/com/euc/ble/protocols/WheelLogGotwayTest.kt`
package com.euc.ble.protocols

import com.euc.ble.SlowTest
import com.euc.ble.core.ByteUtils
import com.euc.ble.frames.FixedSizeFrameParser
import com.euc.ble.frames.FrameReassembler
import com.euc.ble.models.EUCData
import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertNotNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

@SlowTest
class WheelLogGotwayTest {

    private val resourceDir = "/ble_frames/gotway/RAW_WHEELLOG/"
    private val collectorSubscriptionDelayMs = 100L
    private val frameProcessingDelayMs = 3000L
    private val maxValidTiltBackSpeed = 100

    private lateinit var protocol: GotwayProtocol
    @BeforeEach
    fun setUp() {
        protocol = GotwayProtocol()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
    }

    @Test
    fun testLoadAndDecodeRealFrames() = runBlocking {
        val frames = loadGotwayFrames("${resourceDir}RAW_2023_11_25_15_11_39.csv", maxFrames = 1000)
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
        delay(200)

        // Send all frames to the protocol for reassembly on IO dispatcher
        withContext(Dispatchers.IO) {
            for (frame in frames) {
                protocol.decode(frame.bleData)
            }
        }

        // Wait for async processing to complete (needs time for IO dispatcher)
        delay(3000)

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
            decoded.any { it.model.contains("Type A") && it.voltage > 0.0 && abs(it.current) > 0.0 }
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
    fun testDecodedFramesConsistencyShortSequence() = runBlocking {
        val frames = loadGotwayFrames("${resourceDir}RAW_2023_11_24_18_43_22.csv", maxFrames = 200)

        // Start collecting in background
        val collector = async {
            withTimeoutOrNull(5000) {
                protocol.dataFlow.take(100).toList()
            } ?: emptyList()
        }

        // Send all frames to the protocol
        frames.forEach { frame ->
            protocol.decode(frame.bleData)
        }

        delay(100)

        val decoded = collector.await()

        if (decoded.size < 2) {
            println("Pas assez de frames décodées pour test de consistance: ${decoded.size}")
            return@runBlocking
        }

        for (i in 1 until decoded.size) {
            val prev = decoded[i - 1]
            val cur = decoded[i]

            // Only check Type A frames (which have speed data)
            if (cur.model.contains("Type A") && prev.model.contains("Type A")) {
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
    fun testTypeBContentDecodedFromWheelLogCapture() = runBlocking {
        val frames = loadGotwayFrames("${resourceDir}RAW_2023_11_25_15_11_39.csv", maxFrames = 1200)
        assertTrue("CSV resource is empty or missing", frames.isNotEmpty())

        val decoded = mutableListOf<EUCData>()
        val collectorJob = launch {
            protocol.dataFlow.collect { data ->
                decoded.add(data)
            }
        }

        // Let the collector subscribe before frames are fed, matching existing test timing pattern.
        delay(collectorSubscriptionDelayMs)
        frames.forEach { frame ->
            protocol.decode(frame.bleData)
        }
        // Allow asynchronous frame reassembly/decoding to flush capture fragments.
        delay(frameProcessingDelayMs)
        collectorJob.cancel()

        val typeBFrames = decoded.filter { it.model == "Gotway (Type B)" }
        assertTrue("No Type B frames decoded from WheelLog capture", typeBFrames.isNotEmpty())

        typeBFrames.forEach { data ->
            val raw = data.rawData
            assertEquals("Unexpected frame type", 0x04, raw[18].toInt() and 0xFF)

            val expectedDistance = ByteUtils.tryGetUnsignedIntBE(raw, 2)?.toDouble()
            val settings = ByteUtils.tryGetUnsignedShortBE(raw, 6)
            val expectedPedalsMode = settings?.let { 2 - ((it shr 13) and 0x03) }
            val expectedAlarmMode = settings?.let { (it shr 10) and 0x03 }
            val expectedRollAngleMode = settings?.let { (it shr 7) and 0x03 }
            val expectedUsesMiles = settings?.let { (it and 0x01) == 1 }
            val expectedAutoPowerOff = ByteUtils.tryGetUnsignedShortBE(raw, 8)
            val expectedTiltBack = ByteUtils.tryGetUnsignedShortBE(raw, 10)?.takeIf { it < maxValidTiltBackSpeed }
            val expectedLedMode = ByteUtils.tryGetUnsignedByte(raw, 13)
            val expectedAlertFlags = ByteUtils.tryGetUnsignedByte(raw, 14)
            val expectedLightMode = ByteUtils.tryGetUnsignedByte(raw, 15)?.and(0x03)
            val expectedWheelAlarm = expectedAlertFlags?.let { (it and 0x01) == 1 }

            val parsedDistance = expectedDistance ?: throw AssertionError(
                "Type B distance could not be parsed from raw frame: ${raw.joinToString("") { "%02x".format(it) }}"
            )
            assertEquals(parsedDistance, data.distance, 0.01)
            assertEquals(parsedDistance, data.totalDistance, 0.01)

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

            // Type B only carries distance/settings; telemetry fields are placeholders in the protocol output.
            assertEquals(0.0, data.speed, 0.01)
            assertEquals(0.0, data.voltage, 0.01)
            assertEquals(0.0, data.current, 0.01)
            assertEquals(0.0, data.temperature, 0.01)
            assertEquals(0, data.batteryLevel)
            assertEquals(0.0, data.power, 0.01)
        }
    }

    @Test
    fun testFrameReassemblerDirectlyWithRealData() = runBlocking {
        val frameParser= FixedSizeFrameParser(GotwayProtocol.FRAME_SIZE, GotwayProtocol.HEADER,
            GotwayProtocol.FOOTER)
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
        delay(100)

        // Process all BLE packets
        for (frame in frames) {
            reassembler.processIncomingBytes(frame.bleData)
        }

        // Wait for processing
        delay(500)
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
    fun testFrameReassemblyWithFragmentedData() = runBlocking {

        // Create a valid complete frame
        val validFrame = createValidGotwayFrame(
            voltageRaw = 6720,  // 67.20V
            speedRaw = 833,     // ~30 km/h
            distanceRaw = 1000,
            currentRaw = 250,
            tempRaw = 2500
        )

        // Start collecting
        val collector = async {
            withTimeoutOrNull(2000) {
                protocol.dataFlow.take(1).toList()
            } ?: emptyList()
        }

        // Send in fragments (simulating BLE packet fragmentation)
        protocol.decode(validFrame.sliceArray(0..9))
        delay(10)
        protocol.decode(validFrame.sliceArray(10..23))

        delay(100)

        val results = collector.await()
        assertEquals("Should decode one reassembled frame", 1, results.size)
        assertEquals(67.2, results[0].voltage, 0.01)
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

    private fun loadGotwayFrames(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): List<BleFrame> {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Ressource introuvable: $resourcePath")

        val frames = mutableListOf<BleFrame>()
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            var lineNumber = 0
            reader.lineSequence().forEach { rawLine ->
                if (frames.size >= maxFrames) return@forEach
                lineNumber++
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach

                // Split uniquement sur la première virgule (timestamp,hexdata)
                val idx = line.indexOf(',')
                if (idx <= 0 || idx >= line.length - 1) return@forEach

                val timestampStr = line.substring(0, idx).trim()
                var hexData = line.substring(idx + 1).trim()

                // Retirer éventuelles quotes autour de la colonne hex
                if (hexData.startsWith("\"") && hexData.endsWith("\"") && hexData.length >= 2) {
                    hexData = hexData.substring(1, hexData.length - 1)
                }

                try {
                    val bleData = ByteUtils.hexToBytes(hexData)
                    val timestampMs = parseTimestampToMs(timestampStr)
                    frames.add(BleFrame(timestamp = timestampMs, bleData = bleData, metadata = "L$lineNumber"))
                } catch (e: Exception) {
                    // ignorer ligne malformée
                }
            }
        }
        return frames
    }

    private fun parseTimestampToMs(ts: String): Long {
        // Supporte HH:MM:SS.mmm ou MM:SS.mmm ou SS.mmm
        val cleaned = ts.trim()
        val parts = cleaned.split(':', '.')
        return try {
            when (parts.size) {
                4 -> {
                    val h = parts[0].toInt()
                    val m = parts[1].toInt()
                    val s = parts[2].toInt()
                    val ms = parts[3].toInt()
                    (h * 3600000 + m * 60000 + s * 1000 + ms).toLong()
                }
                3 -> {
                    val m = parts[0].toInt()
                    val s = parts[1].toInt()
                    val ms = parts[2].toInt()
                    (m * 60000 + s * 1000 + ms).toLong()
                }
                2 -> {
                    val s = parts[0].toInt()
                    val ms = parts[1].toInt()
                    (s * 1000 + ms).toLong()
                }
                else -> cleaned.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    data class BleFrame(
        val timestamp: Long,
        val bleData: ByteArray,
        val metadata: String
    )
}
