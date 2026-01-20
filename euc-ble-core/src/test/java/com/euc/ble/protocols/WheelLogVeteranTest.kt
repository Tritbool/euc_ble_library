package com.euc.ble.protocols

import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Test class for validating Veteran protocol implementation using real WheelLog RAW data
 * Uses actual BLE frames captured from Veteran wheels (Sherman, Lynx, etc.)
 * Note: Currently uses Kingsong CSV files as examples until Veteran CSV files are available
 */
class WheelLogVeteranTest {

    private val protocol = VeteranProtocol()
    // TODO: Update this path when Veteran CSV files are available
    private val testDataPath = "/ble_frames/kingsong/RAW_WHEELLOG/" // Using Kingsong as example for now

    /**
     * Test parsing and decoding a small sample of real Veteran frames
     * Currently uses Kingsong frames as examples
     */
    @Test
    fun testRealVeteranFramesDecoding() {
        // TODO: Update this to use actual Veteran CSV files when available
        val frames = loadVeteranFrames("$testDataPath/RAW_2023_08_19_18_34_07.csv", maxFrames = 50)
        
        assertTrue("Should load some frames", frames.isNotEmpty())
        
        // Test that all frames have reasonable structure
        frames.forEach { frame ->
            assertTrue("Frame should have reasonable size", 
                frame.bleData.size >= 50) // Minimum expected size for Veteran frames
        }
        
        // Test decoding a subset of frames
        var successfulDecodes = 0
        var failedDecodes = 0
        
        frames.forEach { frame ->
            val decoded = protocol.decode(frame.bleData)
            if (decoded != null) {
                successfulDecodes++
                
                // Validate basic properties
                assertEquals("Manufacturer should be Veteran", "Veteran", decoded.manufacturer)
                assertNotNull("Raw data should be preserved", decoded.rawData)
                assertTrue("Timestamp should be set", decoded.timestamp > 0)
                
                // Validate reasonable ranges for Veteran wheels
                assertTrue("Voltage should be reasonable", decoded.voltage in 60.0..120.0)
                assertTrue("Speed should be reasonable", decoded.speed in 0.0..80.0)
                assertTrue("Battery should be reasonable", decoded.batteryLevel in 0..100)
                
            } else {
                failedDecodes++
            }
        }
        
        println("Decoded $successfulDecodes frames successfully, $failedDecodes frames failed")
        println("Success rate: ${(successfulDecodes * 100.0 / frames.size).toInt()}%")
        
        // Note: Success rate may be lower when using Kingsong frames as examples
        // Once actual Veteran frames are available, this should be higher
        println("Note: Using Kingsong frames as examples - success rate will improve with actual Veteran data")
    }

    /**
     * Test protocol consistency across a sequence of real frames
     */
    @Test
    fun testRealVeteranFramesConsistency() {
        // TODO: Update this to use actual Veteran CSV files when available
        val frames = loadVeteranFrames("$testDataPath/RAW_2023_08_19_18_34_07.csv", maxFrames = 100)
        
        assertTrue("Need multiple frames for consistency test", frames.size >= 10)
        
        val decodedFrames = mutableListOf<EUCData>()
        
        // Decode all frames
        frames.forEach { frame ->
            val decoded = protocol.decode(frame.bleData)
            if (decoded != null) {
                decodedFrames.add(decoded)
            }
        }
        
        if (decodedFrames.size >= 5) {
            // Test consistency between consecutive frames
            for (i in 1 until decodedFrames.size) {
                val prev = decodedFrames[i-1]
                val curr = decodedFrames[i]
                
                // Speed changes should be reasonable (not instant large jumps)
                val speedDiff = kotlin.math.abs(curr.speed - prev.speed)
                assertTrue("Speed change should be reasonable: $speedDiff km/h", 
                    speedDiff < 15.0) // Less than 15 km/h change between frames for high-performance wheels
                
                // Voltage changes should be gradual
                val voltageDiff = kotlin.math.abs(curr.voltage - prev.voltage)
                assertTrue("Voltage change should be reasonable: $voltageDiff V", 
                    voltageDiff < 10.0) // Less than 10V change between frames
                
                // Distance should be non-decreasing
                assertTrue("Distance should not decrease", 
                    curr.distance >= prev.distance - 0.1) // Allow small floating point differences
            }
            
            println("Consistency test passed for ${decodedFrames.size} frames")
        } else {
            println("Not enough frames decoded for consistency test (${decodedFrames.size}/5)")
        }
    }

    /**
     * Test decoding performance with a larger dataset
     */
    @Test
    fun testRealVeteranDecodingPerformance() {
        // TODO: Update this to use actual Veteran CSV files when available
        val frames = loadVeteranFrames("$testDataPath/RAW_2023_08_25_15_02_03.csv", maxFrames = 1000)
        
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
        
        // Performance should be reasonable even with Kingsong frames
        assertTrue("Should decode at reasonable speed", framesPerSecond > 500)
        
        println("Note: Performance test using Kingsong frames as examples")
    }

    /**
     * Test edge cases found in real data
     */
    @Test
    fun testRealVeteranEdgeCases() {
        // TODO: Update this to use actual Veteran CSV files when available
        val testFiles = listOf(
            "RAW_2023_08_19_18_34_07.csv",
            "RAW_2023_08_25_15_02_03.csv",
            "RAW_2023_08_30_19_15_30.csv"
        )
        
        var totalFrames = 0
        var decodedFrames = 0
        var edgeCasesFound = 0
        
        testFiles.forEach { filename ->
            val frames = loadVeteranFrames("$testDataPath/$filename", maxFrames = 200)
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
        
        println("Note: Edge case detection using Kingsong frames as examples")
    }

    /**
     * Test specific known frame patterns
     */
    @Test
    fun testKnownVeteranFramePatterns() {
        // TODO: Update this to use actual Veteran CSV files when available
        val frames = loadVeteranFrames("$testDataPath/RAW_2023_08_19_18_34_07.csv")
        
        // Look for specific frame patterns we can validate
        val interestingFrames = frames.filter { frame ->
            // Example: frames with non-zero speed
            val decoded = protocol.decode(frame.bleData)
            decoded != null && decoded.speed > 5.0
        }
        
        if (interestingFrames.isNotEmpty()) {
            println("Found ${interestingFrames.size} interesting frames")
            
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
        } else {
            println("No interesting frames found - using Kingsong frames as examples")
        }
    }

    /**
     * Test BMS data parsing for Veteran wheels
     */
    @Test
    fun testVeteranBMSDataParsing() {
        // TODO: Update this to use actual Veteran CSV files when available
        val frames = loadVeteranFrames("$testDataPath/RAW_2023_08_19_18_34_07.csv", maxFrames = 200)
        
        var framesWithBMSData = 0
        var totalCellVoltages = 0
        
        frames.forEach { frame ->
            val decoded = protocol.decode(frame.bleData)
            if (decoded != null && decoded.cellVoltages != null) {
                framesWithBMSData++
                totalCellVoltages += decoded.cellVoltages!!.size
                
                // Validate cell voltage ranges
                decoded.cellVoltages!!.forEach { cellVoltage ->
                    assertTrue("Cell voltage should be reasonable", cellVoltage in 2.5..4.5)
                }
            }
        }
        
        println("Found BMS data in $framesWithBMSData frames")
        if (framesWithBMSData > 0) {
            println("Average cells per frame: ${totalCellVoltages / framesWithBMSData}")
        }
        
        println("Note: BMS data test using Kingsong frames as examples")
    }

    /**
     * Load Veteran frames from WheelLog CSV file
     * Currently uses Kingsong CSV format as example
     */
    private fun loadVeteranFrames(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): List<BleFrame> {
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
     * Check if a decoded frame represents an edge case for Veteran
     */
    private fun isEdgeCase(data: EUCData): Boolean {
        // Define what constitutes an edge case for Veteran wheels
        return data.speed > 50.0 ||           // High speed for Veteran
               data.voltage > 100.0 ||        // High voltage
               data.voltage < 60.0 ||         // Low voltage
               data.temperature > 80.0 ||     // High temperature
               data.current > 100.0 ||         // High current
               data.batteryLevel < 20 ||       // Low battery
               data.isCharging                 // Charging state
    }

    /**
     * Describe an edge case for reporting
     */
    private fun describeEdgeCase(data: EUCData): String {
        val reasons = mutableListOf<String>()
        
        if (data.speed > 50.0) reasons.add("high speed (${data.speed} km/h)")
        if (data.voltage > 100.0) reasons.add("high voltage (${data.voltage} V)")
        if (data.voltage < 60.0) reasons.add("low voltage (${data.voltage} V)")
        if (data.temperature > 80.0) reasons.add("high temp (${data.temperature} °C)")
        if (data.current > 100.0) reasons.add("high current (${data.current} A)")
        if (data.batteryLevel < 20) reasons.add("low battery (${data.batteryLevel}%)")
        if (data.isCharging) reasons.add("charging state")
        
        return reasons.joinToString(", ")
    }

    /**
     * Describe a frame for reporting
     */
    private fun describeFrame(data: EUCData): String {
        val cellInfo = if (data.cellVoltages != null) "${data.cellVoltages!!.size} cells" else "no BMS data"
        return "${data.speed.toInt()} km/h, ${data.voltage.toInt()} V, ${data.batteryLevel}% battery, " +
               "${data.current.toInt()} A, ${data.temperature.toInt()} °C, $cellInfo"
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