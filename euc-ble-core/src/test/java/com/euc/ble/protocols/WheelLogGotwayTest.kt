package com.euc.ble.protocols

import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Test class for validating Gotway protocol implementation using real WheelLog RAW data
 * Uses actual BLE frames captured from Gotway/Begode wheels
 */
class WheelLogGotwayTest {

    private val protocol = GotwayProtocol()
    private val testDataPath = "/ble_frames/gotway/RAW_WHEELLOG/"

    /**
     * Test parsing and decoding real Gotway frames
     */
    @Test
    fun testRealGotwayFramesDecoding() {
        val frames = loadGotwayFrames("$testDataPath/RAW_2023_11_24_18_43_22.csv", maxFrames = 100)
        
        assertTrue("Should load some frames", frames.isNotEmpty())
        
        // Test decoding frames
        var successfulDecodes = 0
        var failedDecodes = 0
        var framesWithValidHeader = 0
        
        frames.forEach { frame ->
            // Check for Gotway header patterns
            if (frame.bleData.size >= 4) {
                // Gotway frames often start with 55 AA or similar patterns
                val firstByte = frame.bleData[0].toInt() and 0xFF
                val secondByte = frame.bleData[1].toInt() and 0xFF
                
                if (firstByte == 0x55 && secondByte == 0xAA) {
                    framesWithValidHeader++
                }
            }
            
            val decoded = protocol.decode(frame.bleData)
            if (decoded != null) {
                successfulDecodes++
                
                // Validate basic properties
                assertEquals("Manufacturer should be Gotway", "Gotway", decoded.manufacturer)
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
        
        println("Gotway Frame Decoding:")
        println("  Total frames: ${frames.size}")
        println("  Frames with 55 AA header: $framesWithValidHeader")
        println("  Decoded successfully: $successfulDecodes")
        println("  Failed to decode: $failedDecodes")
        println("  Success rate: ${(successfulDecodes * 100.0 / frames.size).toInt()}%")
        
        // We expect a reasonable success rate
        assertTrue("Should decode some frames successfully", successfulDecodes > frames.size * 0.3)
    }

    /**
     * Test Gotway frame consistency across sequences
     */
    @Test
    fun testRealGotwayFramesConsistency() {
        val frames = loadGotwayFrames("$testDataPath/RAW_2023_11_24_18_43_22.csv", maxFrames = 200)
        
        assertTrue("Need multiple frames for consistency test", frames.size >= 20)
        
        val decodedFrames = mutableListOf<EUCData>()
        
        // Decode all frames
        frames.forEach { frame ->
            val decoded = protocol.decode(frame.bleData)
            if (decoded != null) {
                decodedFrames.add(decoded)
            }
        }
        
        assertTrue("Should have some decoded frames", decodedFrames.size >= 10)
        
        // Test consistency between consecutive frames
        for (i in 1 until decodedFrames.size) {
            val prev = decodedFrames[i-1]
            val curr = decodedFrames[i]
            
            // Speed changes should be reasonable
            val speedDiff = kotlin.math.abs(curr.speed - prev.speed)
            assertTrue("Speed change should be reasonable: $speedDiff km/h", 
                speedDiff < 15.0) // Gotway can have faster changes
            
            // Voltage changes should be gradual
            val voltageDiff = kotlin.math.abs(curr.voltage - prev.voltage)
            assertTrue("Voltage change should be reasonable: $voltageDiff V", 
                voltageDiff < 6.0) // Allow slightly larger voltage changes
            
            // Distance should be non-decreasing
            assertTrue("Distance should not decrease", 
                curr.distance >= prev.distance - 0.1)
        }
        
        println("Gotway Consistency test passed for ${decodedFrames.size} frames")
    }

    /**
     * Test Gotway protocol with larger dataset for performance
     */
    @Test
    fun testRealGotwayDecodingPerformance() {
        val frames = loadGotwayFrames("$testDataPath/RAW_2023_11_25_15_11_39.csv", maxFrames = 2000)
        
        assertTrue("Should load many frames for performance test", frames.size >= 1000)
        
        val startTime = System.currentTimeMillis()
        var decodedCount = 0
        var motorTempFrames = 0
        
        frames.forEach { frame ->
            val decoded = protocol.decode(frame.bleData)
            if (decoded != null) {
                decodedCount++
                
                // Check for motor temperature data
                if (decoded.motorTemperature != null) {
                    motorTempFrames++
                }
            }
        }
        
        val endTime = System.currentTimeMillis()
        val durationMs = endTime - startTime
        val framesPerSecond = (frames.size * 1000.0 / durationMs).toInt()
        
        println("Gotway Performance Analysis:")
        println("  Frames processed: ${frames.size}")
        println("  Decoded successfully: $decodedCount")
        println("  Frames with motor temp: $motorTempFrames")
        println("  Success rate: ${(decodedCount * 100.0 / frames.size).toInt()}%")
        println("  Processing speed: $framesPerSecond frames/sec")
        println("  Time taken: ${durationMs}ms")
        
        // Performance should be reasonable
        assertTrue("Should decode at reasonable speed", framesPerSecond > 800)
    }

    /**
     * Test Gotway edge cases and special conditions
     */
    @Test
    fun testRealGotwayEdgeCases() {
        val testFiles = listOf(
            "RAW_2023_11_24_18_43_22.csv",
            "RAW_2023_11_25_15_11_39.csv",
            "RAW_2023_12_06_15_47_00.csv"
        )
        
        var totalFrames = 0
        var decodedFrames = 0
        var edgeCasesFound = 0
        var highSpeedFrames = 0
        var chargingFrames = 0
        
        testFiles.forEach { filename ->
            val frames = loadGotwayFrames("$testDataPath/$filename", maxFrames = 300)
            totalFrames += frames.size
            
            frames.forEach { frame ->
                val decoded = protocol.decode(frame.bleData)
                if (decoded != null) {
                    decodedFrames++
                    
                    // Look for edge cases
                    if (isGotwayEdgeCase(decoded)) {
                        edgeCasesFound++
                        println("Edge case found: ${describeGotwayEdgeCase(decoded)}")
                    }
                    
                    if (decoded.speed > 35.0) {
                        highSpeedFrames++
                    }
                    
                    if (decoded.isCharging) {
                        chargingFrames++
                    }
                }
            }
        }
        
        println("Gotway Edge Case Analysis:")
        println("  Total frames analyzed: $totalFrames")
        println("  Decoded frames: $decodedFrames (${(decodedFrames * 100.0 / totalFrames).toInt()}%)")
        println("  Edge cases found: $edgeCasesFound")
        println("  High speed frames (>35 km/h): $highSpeedFrames")
        println("  Charging frames: $chargingFrames")
        
        assertTrue("Should decode a reasonable number of frames", decodedFrames > totalFrames * 0.25)
    }

    /**
     * Test Gotway frame patterns and packet types
     */
    @Test
    fun testRealGotwayFramePatterns() {
        val frames = loadGotwayFrames("$testDataPath/RAW_2023_11_24_18_43_22.csv", maxFrames = 500)
        
        assertTrue("Need frames for pattern analysis", frames.isNotEmpty())
        
        // Analyze frame lengths and patterns
        val lengthDistribution = mutableMapOf<Int, Int>()
        val packetTypeDistribution = mutableMapOf<Byte, Int>()
        
        frames.forEach { frame ->
            // Track frame lengths
            val length = frame.bleData.size
            lengthDistribution[length] = lengthDistribution.getOrDefault(length, 0) + 1
            
            // Check for packet type if frame is long enough
            if (frame.bleData.size >= 1) {
                val packetType = frame.bleData[0]
                packetTypeDistribution[packetType] = packetTypeDistribution.getOrDefault(packetType, 0) + 1
            }
        }
        
        println("Gotway Frame Pattern Analysis:")
        println("  Frame length distribution:")
        lengthDistribution.toList()
            .sortedBy { it.first }
            .forEach { (length, count) ->
                println("    $length bytes: $count frames")
            }
        
        println("  Packet type distribution (first byte):")
        packetTypeDistribution.toList()
            .sortedByDescending { it.second }
            .take(5)
            .forEach { (packetType, count) ->
                val hexType = String.format("%02X", packetType)
                println("    0x$hexType: $count frames")
            }
        
        // Most frames should have reasonable lengths
        val reasonableLengths = lengthDistribution.filterKeys { it in 10..30 }
        assertTrue("Most frames should have reasonable lengths", 
            reasonableLengths.values.sum() > frames.size * 0.6)
    }

    /**
     * Load Gotway frames from WheelLog CSV file
     */
    private fun loadGotwayFrames(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): List<BleFrame> {
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
     * Check if a decoded Gotway frame represents an edge case
     */
    private fun isGotwayEdgeCase(data: EUCData): Boolean {
        return data.speed > 35.0 ||           // High speed
               data.voltage > 85.0 ||         // High voltage
               data.voltage < 45.0 ||         // Low voltage (Gotway threshold)
               data.temperature > 70.0 ||     // High temperature
               data.current > 80.0 ||          // High current
               data.batteryLevel < 15 ||       // Low battery
               data.isCharging ||               // Charging state
               (data.motorTemperature != null && data.motorTemperature!! > 60.0) // Hot motor
    }

    /**
     * Describe a Gotway edge case for reporting
     */
    private fun describeGotwayEdgeCase(data: EUCData): String {
        val reasons = mutableListOf<String>()
        
        if (data.speed > 35.0) reasons.add("high speed (${data.speed} km/h)")
        if (data.voltage > 85.0) reasons.add("high voltage (${data.voltage} V)")
        if (data.voltage < 45.0) reasons.add("low voltage (${data.voltage} V)")
        if (data.temperature > 70.0) reasons.add("high temp (${data.temperature} °C)")
        if (data.current > 80.0) reasons.add("high current (${data.current} A)")
        if (data.batteryLevel < 15) reasons.add("low battery (${data.batteryLevel}%)")
        if (data.isCharging) reasons.add("charging state")
        if (data.motorTemperature != null && data.motorTemperature!! > 60.0) {
            reasons.add("hot motor (${data.motorTemperature}°C)")
        }
        
        return reasons.joinToString(", ")
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
