package com.euc.ble.protocols

import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Test class for validating Kingsong protocol implementation using real WheelLog RAW data
 * Uses actual BLE frames captured from Kingsong wheels
 */
class WheelLogKingsongTest {

    private val protocol = KingsongProtocol()
    private val testDataPath = "/ble_frames/kingsong/RAW_WHEELLOG/"

    /**
     * Test parsing and decoding a small sample of real Kingsong frames
     */
    @Test
    fun testRealKingsongFramesDecoding() {
        val frames = loadKingsongFrames("$testDataPath/RAW_2023_08_19_18_34_07.csv", maxFrames = 50)
        
        assertTrue("Should load some frames", frames.isNotEmpty())
        
        // Test that all frames have the correct Kingsong header
        frames.forEach { frame ->
            assertTrue("Frame should start with AA 55", 
                frame.bleData.size >= 2 && 
                frame.bleData[0].toInt() and 0xFF == 0xAA &&
                frame.bleData[1].toInt() and 0xFF == 0x55)
        }
        
        // Test decoding a subset of frames
        var successfulDecodes = 0
        var failedDecodes = 0
        
        frames.forEach { frame ->
            val decoded = protocol.decode(frame.bleData)
            if (decoded != null) {
                successfulDecodes++
                
                // Validate basic properties
                assertEquals("Manufacturer should be KingSong", "KingSong", decoded.manufacturer)
                assertNotNull("Raw data should be preserved", decoded.rawData)
                assertTrue("Timestamp should be set", decoded.timestamp > 0)
                
                // Validate reasonable ranges
                assertTrue("Voltage should be reasonable", decoded.voltage in 40.0..100.0)
                assertTrue("Speed should be reasonable", decoded.speed in 0.0..60.0)
                assertTrue("Battery should be reasonable", decoded.batteryLevel in 0..100)
                
            } else {
                failedDecodes++
            }
        }
        
        println("Decoded $successfulDecodes frames successfully, $failedDecodes frames failed")
        println("Success rate: ${(successfulDecodes * 100.0 / frames.size).toInt()}%")
        
        // We expect most frames to decode successfully
        assertTrue("Should decode most frames successfully", successfulDecodes > frames.size * 0.8)
    }

    /**
     * Test protocol consistency across a sequence of real frames
     */
    @Test
    fun testRealKingsongFramesConsistency() {
        val frames = loadKingsongFrames("$testDataPath/RAW_2023_08_19_18_34_07.csv", maxFrames = 100)
        
        assertTrue("Need multiple frames for consistency test", frames.size >= 10)
        
        val decodedFrames = mutableListOf<EUCData>()
        
        // Decode all frames
        frames.forEach { frame ->
            val decoded = protocol.decode(frame.bleData)
            if (decoded != null) {
                decodedFrames.add(decoded)
            }
        }
        
        assertTrue("Should have multiple decoded frames", decodedFrames.size >= 5)
        
        // Test consistency between consecutive frames
        for (i in 1 until decodedFrames.size) {
            val prev = decodedFrames[i-1]
            val curr = decodedFrames[i]
            
            // Speed changes should be reasonable (not instant large jumps)
            val speedDiff = kotlin.math.abs(curr.speed - prev.speed)
            assertTrue("Speed change should be reasonable: $speedDiff km/h", 
                speedDiff < 10.0) // Less than 10 km/h change between frames
            
            // Voltage changes should be gradual
            val voltageDiff = kotlin.math.abs(curr.voltage - prev.voltage)
            assertTrue("Voltage change should be reasonable: $voltageDiff V", 
                voltageDiff < 5.0) // Less than 5V change between frames
            
            // Distance should be non-decreasing
            assertTrue("Distance should not decrease", 
                curr.distance >= prev.distance - 0.1) // Allow small floating point differences
        }
        
        println("Consistency test passed for ${decodedFrames.size} frames")
    }

    /**
     * Test decoding performance with a larger dataset
     */
    @Test
    fun testRealKingsongDecodingPerformance() {
        val frames = loadKingsongFrames("$testDataPath/RAW_2023_08_25_15_02_03.csv", maxFrames = 1000)
        
        assertTrue("Should load many frames for performance test", frames.size >= 500)
        
        val startTime = System.currentTimeMillis()
        var decodedCount = 0
        
        frames.forEach { frame ->
            val decoded = protocol.decode(frame.bleData)
            if (decoded != null) {
                decodedCount++
            }
        }
        
        val endTime = System.currentTimeMillis()
        val durationMs = endTime - startTime
        val framesPerSecond = (frames.size * 1000.0 / durationMs).toInt()
        
        println("Performance: $framesPerSecond frames/sec")
        println("Decoded $decodedCount out of ${frames.size} frames")
        println("Success rate: ${(decodedCount * 100.0 / frames.size).toInt()}%")
        println("Time taken: ${durationMs}ms")
        
        // Performance should be reasonable
        assertTrue("Should decode at reasonable speed", framesPerSecond > 1000)
        assertTrue("Should decode most frames", decodedCount > frames.size * 0.7)
    }

    /**
     * Test edge cases found in real data
     */
    @Test
    fun testRealKingsongEdgeCases() {
        // Load frames from multiple files to find edge cases
        val testFiles = listOf(
            "RAW_2023_08_19_18_34_07.csv",
            "RAW_2023_08_25_15_02_03.csv",
            "RAW_2023_08_30_19_15_30.csv"
        )
        
        var totalFrames = 0
        var decodedFrames = 0
        var edgeCasesFound = 0
        
        testFiles.forEach { filename ->
            val frames = loadKingsongFrames("$testDataPath/$filename", maxFrames = 200)
            totalFrames += frames.size
            
            frames.forEach { frame ->
                val decoded = protocol.decode(frame.bleData)
                if (decoded != null) {
                    decodedFrames++
                    
                    // Look for edge cases
                    if (isEdgeCase(decoded)) {
                        edgeCasesFound++
                        println("Edge case found: ${describeEdgeCase(decoded)}")
                    }
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
    fun testKnownKingsongFramePatterns() {
        val frames = loadKingsongFrames("$testDataPath/RAW_2023_08_19_18_34_07.csv")
        
        // Look for specific frame patterns we can validate
        val interestingFrames = frames.filter { frame ->
            // Example: frames with non-zero speed
            val decoded = protocol.decode(frame.bleData)
            decoded != null && decoded.speed > 5.0
        }
        
        assertTrue("Should find some interesting frames", interestingFrames.isNotEmpty())
        
        // Validate a few specific frames
        if (interestingFrames.size >= 3) {
            val frame1 = interestingFrames[0]
            val frame2 = interestingFrames[interestingFrames.size / 2]
            val frame3 = interestingFrames.last()
            
            val decoded1 = protocol.decode(frame1.bleData)
            val decoded2 = protocol.decode(frame2.bleData)
            val decoded3 = protocol.decode(frame3.bleData)
            
            assertNotNull("Frame 1 should decode", decoded1)
            assertNotNull("Frame 2 should decode", decoded2)
            assertNotNull("Frame 3 should decode", decoded3)
            
            println("Validated specific frames:")
            println("  Frame 1: ${describeFrame(decoded1!!)}")
            println("  Frame 2: ${describeFrame(decoded2!!)}")
            println("  Frame 3: ${describeFrame(decoded3!!)}")
        }
    }

    /**
     * Load Kingsong frames from WheelLog CSV file
     */
    private fun loadKingsongFrames(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): List<BleFrame> {
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
                            
                            frames.add(BleFrame(
                                timestamp = milliseconds.toLong(),
                                bleData = bleData,
                                metadata = "Line $lineNumber"
                            ))
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
               data.batteryLevel < 20 ||       // Low battery
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
        if (data.batteryLevel < 20) reasons.add("low battery (${data.batteryLevel}%)")
        if (data.isCharging) reasons.add("charging state")
        
        return reasons.joinToString(", ")
    }

    /**
     * Describe a frame for reporting
     */
    private fun describeFrame(data: EUCData): String {
        return "${data.speed.toInt()} km/h, ${data.voltage.toInt()} V, ${data.batteryLevel}% battery, " +
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
