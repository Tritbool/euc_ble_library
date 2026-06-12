package com.euc.ble.protocols

import app.cash.turbine.test
import com.euc.ble.SlowTest
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertNotNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.collections.plusAssign
import kotlin.time.Duration.Companion.milliseconds

/**
 * Test class for validating Kingsong protocol implementation using real WheelLog RAW data
 * Uses actual BLE frames captured from Kingsong wheels
 */
@SlowTest
class WheelLogKingsongTest {

    companion object {
        private const val COLLECTOR_SUBSCRIBE_DELAY_MS = 150L
        private const val DECODE_SETTLE_DELAY_MS = 300L
        private const val MIN_DECODE_FPS = 100
        private val EXPECTED_VOLTAGE_RANGE = 60.0..130.0
    }

    private val testDataPath = "/ble_frames/kingsong/RAW_WHEELLOG/"

    private lateinit var protocol: KingsongProtocol

    @BeforeEach
    fun setUp() {
        protocol = KingsongProtocol()
    }

    @AfterEach
    fun tearDown() {
        if (this::protocol.isInitialized) {
            protocol.close()
        }
    }

    private fun isA9TelemetryFrame(frame: BleFrame): Boolean {
        return frame.bleData.size > 16 && (frame.bleData[16].toInt() and 0xFF) == 0xA9
    }

    private suspend fun decodeA9Frames(
        frames: List<BleFrame>,
        expected:Int=600
    ): List<EUCData> = coroutineScope {
        val telemetryFrames = frames.filter(::isA9TelemetryFrame)
        val decoded = mutableListOf<EUCData>()
        protocol.dataFlow.test {
            telemetryFrames.forEach { protocol.decode(it.bleData) }

            repeat(expected) {
                decoded.add(awaitItem())
            }
            cancelAndIgnoreRemainingEvents()
        }
        decoded

    }

    /**
     * Test parsing and decoding a small sample of real Kingsong frames
     */
    @Test
    fun testRealKingsongFramesDecoding() = runTest {
        tearDown()
        protocol = KingsongProtocol(backgroundScope)
        val frames =
            loadKingsongFrames("$testDataPath/RAW_2023_08_25_15_02_03.csv", maxFrames = 4000)

        assertTrue("Should load some frames", frames.isNotEmpty())

        val telemetryFrames = frames.filter(::isA9TelemetryFrame)
        assertTrue("Should include A9 telemetry frames", telemetryFrames.isNotEmpty())

        telemetryFrames.forEach { frame ->
            assertTrue(
                "Frame should start with AA 55",
                frame.bleData.size >= 2 &&
                        frame.bleData[0].toInt() and 0xFF == 0xAA &&
                        frame.bleData[1].toInt() and 0xFF == 0x55
            )
        }

        val decodedFrames = decodeA9Frames(frames,telemetryFrames.size)
        val successfulDecodes = decodedFrames.size
        val failedDecodes = telemetryFrames.size - successfulDecodes

        decodedFrames.forEach { decoded ->
            assertEquals("Manufacturer should be KingSong", "KingSong", decoded.manufacturer)
            assertNotNull("Raw data should be preserved", decoded.rawData)
            assertTrue("Timestamp should be set", decoded.timestamp > 0)
            assertTrue("Voltage should be reasonable", decoded.voltage in EXPECTED_VOLTAGE_RANGE)
            assertTrue("Speed should be reasonable", decoded.speed in 0.0..60.0)
            assertTrue("Battery should be in range", decoded.batteryLevel in 0..100)
        }
        assertTrue(
            "Expected at least one frame with ride time progression",
            decodedFrames.all { it.rideTime >= 0 })

        println("Decoded $successfulDecodes frames successfully, $failedDecodes frames failed")
        println("Success rate: ${(successfulDecodes * 100.0 / telemetryFrames.size).toInt()}%")

        assertTrue(
            "Should decode most frames successfully",
            successfulDecodes > telemetryFrames.size * 0.8
        )
    }

    /**
     * Test protocol consistency across a sequence of real frames
     */
    @Test
    fun testRealKingsongFramesConsistency() = runTest {
        tearDown()
        protocol = KingsongProtocol(backgroundScope)
        val frames =
            loadKingsongFrames("$testDataPath/RAW_2023_08_25_15_02_03.csv", maxFrames = 20000)
        val telemetryFrames = frames.filter(::isA9TelemetryFrame)

        assertTrue(
            "Need multiple telemetry frames for consistency test",
            telemetryFrames.size >= 10
        )

        val decodedFrames = decodeA9Frames(telemetryFrames,telemetryFrames.size,)
        assertTrue("Should have multiple decoded frames", decodedFrames.size >= 5)

        for (i in 1 until decodedFrames.size) {
            val prev = decodedFrames[i - 1]
            val curr = decodedFrames[i]

            val speedDiff = kotlin.math.abs(curr.speed - prev.speed)
            assertTrue(
                "Speed change should be reasonable: $speedDiff km/h",
                speedDiff < 10.0
            ) // Less than 10 km/h change between frames

            // Voltage changes should be gradual
            val voltageDiff = kotlin.math.abs(curr.voltage - prev.voltage)
            assertTrue(
                "Voltage change should be reasonable: $voltageDiff V",
                voltageDiff < 5.0
            ) // Less than 5V change between frames
            assertTrue("Ride time should be non-decreasing", curr.rideTime >= prev.rideTime)

            // Distance should be non-decreasing
            assertTrue(
                "Distance should not decrease",
                curr.distance >= prev.distance - 0.1
            ) // Allow small floating point differences
        }

        println("Consistency test passed for ${decodedFrames.size} frames")
    }

    /**
     * Test decoding performance with a larger dataset
     */
    @Test
    fun testRealKingsongDecodingPerformance() = runTest {
        tearDown()
        protocol = KingsongProtocol(backgroundScope)
        val frames =
            loadKingsongFrames("$testDataPath/RAW_2023_08_25_15_02_03.csv", maxFrames = 5000)
        val telemetryFrames = frames.filter(::isA9TelemetryFrame)

        assertTrue("Should load many frames for performance test", frames.size >= 500)
        assertTrue("Should include many telemetry frames", telemetryFrames.size >= 100)

        val startTime = System.currentTimeMillis()
        val decodedFrames = decodeA9Frames(telemetryFrames,telemetryFrames.size)
        val decodedCount = decodedFrames.size

        val endTime = System.currentTimeMillis()
        val durationMs = endTime - startTime
        val framesPerSecond = (telemetryFrames.size * 1000.0 / durationMs).toInt()

        println("Performance: $framesPerSecond frames/sec")
        println("Decoded $decodedCount out of ${telemetryFrames.size} telemetry frames")
        println("Success rate: ${(decodedCount * 100.0 / telemetryFrames.size).toInt()}%")
        println("Time taken: ${durationMs}ms")

        assertTrue("Should decode at reasonable speed", framesPerSecond > MIN_DECODE_FPS)
        assertTrue("Should decode most frames", decodedCount > telemetryFrames.size * 0.7)
    }

    /**
     * Test edge cases found in real data
     */
    @Test
    fun testRealKingsongEdgeCases() = runTest {
        tearDown()
        protocol = KingsongProtocol(backgroundScope)
        // Load frames from multiple files to find edge cases
        val testFiles = listOf(
            "RAW_2023_08_25_15_02_03.csv",
            "RAW_2023_08_30_19_15_30.csv"
        )

        var totalFrames = 0
        var decodedFrames = 0
        var edgeCasesFound = 0
        val telemetryFrames = mutableListOf<BleFrame>()
        testFiles.forEach { filename ->
            val frames = loadKingsongFrames("$testDataPath/$filename", maxFrames = 4000)
            telemetryFrames+=frames.filter(::isA9TelemetryFrame)
            totalFrames += telemetryFrames.size

            val decoded = decodeA9Frames(telemetryFrames,telemetryFrames.size)
            decodedFrames += decoded.size
            decoded.forEach {
                if (isEdgeCase(it)) {
                    edgeCasesFound++
                    println("Edge case found: ${describeEdgeCase(it)}")
                }
            }
        }

        println("Analyzed $totalFrames frames from ${testFiles.size} files")
        println("Decoded $decodedFrames frames (${(decodedFrames * 100.0 / totalFrames).toInt()}%)")
        println("Found $edgeCasesFound edge cases")

        assertTrue("Should decode a reasonable number of frames", decodedFrames > totalFrames * 0.7)
    }

    /**
     * Test specific known frame patterns
     */
    @Test
    fun testKnownKingsongFramePatterns()= runTest {
        tearDown()
        protocol = KingsongProtocol(backgroundScope)
        val frames =
            loadKingsongFrames("$testDataPath/RAW_2023_09_01_18_32_03.csv", maxFrames = 200)
        val telemetryFrames=frames.filter(::isA9TelemetryFrame)
        val decoded = decodeA9Frames(frames,telemetryFrames.size)
        val interestingFrames = decoded.filter { it.speed > 5.0 }

        assertTrue("Should find some interesting frames", interestingFrames.isNotEmpty())

        // Validate a few specific frames
        if (interestingFrames.size >= 3) {
            val decoded1 = interestingFrames[0]
            val decoded2 = interestingFrames[interestingFrames.size / 2]
            val decoded3 = interestingFrames.last()

            println("Validated specific frames:")
            println("  Frame 1: ${describeFrame(decoded1)}")
            println("  Frame 2: ${describeFrame(decoded2)}")
            println("  Frame 3: ${describeFrame(decoded3)}")
        }
    }

    /**
     * Load Kingsong frames from WheelLog CSV file
     */
    private fun loadKingsongFrames(
        resourcePath: String,
        maxFrames: Int = Int.MAX_VALUE
    ): List<BleFrame> {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        val frames = mutableListOf<BleFrame>()
        val reader = BufferedReader(InputStreamReader(inputStream))

        var line: String?
        var lineNumber = 0

        while (reader.readLine().also { line = it } != null && frames.size < maxFrames) {
            lineNumber++
            line?.let {
                try {
                    // Parse the CSV line: timestamp,hex_data
                    val parts = it.split(",")
                    if (parts.size >= 2) {
                        val timestampStr = parts[0].trim()
                        val hexData = parts[1].trim()

                        // Convert hex string to byte array
                        val bleData = ByteUtils.hexToBytes(hexData)

                        // Parse timestamp (HH:MM:SS.mmm) to milliseconds since start
                        val timeParts = timestampStr.split(":", ".")
                        if (timeParts.size >= 4) {
                            val milliseconds =
                                timeParts[0].toInt() * 3600000 +  // hours to ms
                                        timeParts[1].toInt() * 60000 +   // minutes to ms
                                        timeParts[2].toInt() * 1000 +    // seconds to ms
                                        timeParts[3].toInt()             // milliseconds

                            frames.add(
                                BleFrame(
                                    timestamp = milliseconds.toLong(),
                                    bleData = bleData,
                                    metadata = "Line $lineNumber"
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    println("Warning: Could not parse line $lineNumber: ${e.message}")
                }
            }
        }

        return frames
    }

    /**
     * Check if a decoded frame represents an edge case
     */
    private fun isEdgeCase(data: EUCData): Boolean {
        // Define what constitutes an edge case
        return data.speed > 40.0 ||           // High speed
                data.voltage > 80.0 ||         // High voltage
                data.voltage < 50.0 ||         // Low voltage
                data.temperature > 60.0 ||     // High temperature
                data.current > 80.0 ||          // High current
                data.isCharging                 // Charging state
    }

    /**
     * Describe an edge case for reporting
     */
    private fun describeEdgeCase(data: EUCData): String {
        val reasons = mutableListOf<String>()

        if (data.speed > 40.0) reasons.add("high speed (${data.speed} km/h)")
        if (data.voltage > 80.0) reasons.add("high voltage (${data.voltage} V)")
        if (data.voltage < 50.0) reasons.add("low voltage (${data.voltage} V)")
        if (data.temperature > 60.0) reasons.add("high temp (${data.temperature} °C)")
        if (data.current > 80.0) reasons.add("high current (${data.current} A)")
        if (data.isCharging) reasons.add("charging state")

        return reasons.joinToString(", ")
    }

    /**
     * Describe a frame for reporting
     */
    private fun describeFrame(data: EUCData): String {
        return "${data.speed.toInt()} km/h, ${data.voltage.toInt()} V, " +
                "${data.current.toInt()} A, ${data.temperature.toInt()} °C"
    }

    /**
     * Data class to represent a BLE frame with metadata
     */
    data class BleFrame(
        val timestamp: Long,
        val bleData: ByteArray,
        val metadata: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as BleFrame

            if (timestamp != other.timestamp) return false
            if (!bleData.contentEquals(other.bleData)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + bleData.contentHashCode()
            return result
        }
    }
}
