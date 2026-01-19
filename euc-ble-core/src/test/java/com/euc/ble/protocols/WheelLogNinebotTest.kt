package com.euc.ble.protocols

import com.euc.ble.core.ByteUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Test class for analyzing Ninebot BLE frames from WheelLog RAW data
 * Note: Ninebot uses a different protocol, so we focus on frame analysis rather than decoding
 */
class WheelLogNinebotTest {

    private val testDataPath = "/ble_frames/ninebot/RAW_WHEELLOG/"

    /**
     * Test parsing and analyzing Ninebot frames
     */
    @Test
    fun testNinebotFrameAnalysis() {
        val frames = loadNinebotFrames("$testDataPath/RAW_2023_08_21_11_24_37.csv", maxFrames = 100)
        
        assertTrue("Should load some frames", frames.isNotEmpty())
        
        // Analyze frame patterns
        var shortFrames = 0
        var longFrames = 0
        var header5aA5Frames = 0
        var otherFrames = 0
        
        frames.forEach { frame ->
            if (frame.bleData.size <= 10) {
                shortFrames++
            } else {
                longFrames++
            }
            
            // Check for common Ninebot header pattern
            if (frame.bleData.size >= 2) {
                val firstByte = frame.bleData[0].toInt() and 0xFF
                val secondByte = frame.bleData[1].toInt() and 0xFF
                
                if (firstByte == 0x5A && secondByte == 0xA5) {
                    header5aA5Frames++
                } else {
                    otherFrames++
                }
            }
        }
        
        println("Ninebot Frame Analysis:")
        println("  Total frames: ${frames.size}")
        println("  Short frames (<=10 bytes): $shortFrames")
        println("  Long frames (>10 bytes): $longFrames")
        println("  Frames with 5A A5 header: $header5aA5Frames")
        println("  Other frames: $otherFrames")
        
        // Most Ninebot frames should have the 5A A5 header
        assertTrue("Most frames should have 5A A5 header", header5aA5Frames > frames.size * 0.5)
    }

    /**
     * Test Ninebot frame patterns and consistency
     */
    @Test
    fun testNinebotFramePatterns() {
        val frames = loadNinebotFrames("$testDataPath/RAW_2023_08_21_11_24_37.csv", maxFrames = 200)
        
        assertTrue("Need frames for pattern analysis", frames.isNotEmpty())
        
        // Look for common patterns
        val framePatterns = mutableMapOf<String, Int>()
        val uniqueFrames = mutableSetOf<String>()
        
        frames.forEach { frame ->
            val hexPattern = ByteUtils.bytesToHex(frame.bleData, "")
            framePatterns[hexPattern] = framePatterns.getOrDefault(hexPattern, 0) + 1
            uniqueFrames.add(hexPattern)
        }
        
        // Find most common patterns
        val commonPatterns = framePatterns.filter { it.value > 1 }
            .toList()
            .sortedByDescending { it.second }
            .take(5)
        
        println("Ninebot Frame Patterns:")
        println("  Total unique frames: ${uniqueFrames.size}")
        println("  Total frames analyzed: ${frames.size}")
        println("  Most common patterns:")
        
        commonPatterns.forEach { (pattern, count) ->
            println("    $pattern (appears $count times)")
        }
        
        // Analyze frame lengths
        val frameLengths = frames.map { it.bleData.size }
        val minLength = frameLengths.minOrNull() ?: 0
        val maxLength = frameLengths.maxOrNull() ?: 0
        val avgLength = frameLengths.average()
        
        println("  Frame length statistics:")
        println("    Min: $minLength bytes")
        println("    Max: $maxLength bytes")
        println("    Avg: ${avgLength.toInt()} bytes")
    }

    /**
     * Test Ninebot data consistency across frames
     */
    @Test
    fun testNinebotDataConsistency() {
        val frames = loadNinebotFrames("$testDataPath/RAW_2023_08_21_11_24_37.csv", maxFrames = 500)
        
        assertTrue("Need frames for consistency test", frames.size >= 50)
        
        // Check timestamp consistency
        for (i in 1 until frames.size) {
            val prev = frames[i-1]
            val curr = frames[i]
            
            // Timestamps should be non-decreasing
            assertTrue("Timestamps should be consistent", 
                curr.timestamp >= prev.timestamp)
        }
        
        // Look for repeated frames (could indicate keep-alive or status messages)
        val frameContents = frames.map { String(it.bleData) }
        val repeatedFrames = frameContents.groupBy { it }
            .filter { it.value.size > 1 }
            .mapValues { it.value.size }
        
        println("Ninebot Consistency Analysis:")
        println("  Total frames: ${frames.size}")
        println("  Frames with duplicates: ${repeatedFrames.size}")
        
        if (repeatedFrames.isNotEmpty()) {
            println("  Most repeated frames:")
            repeatedFrames.toList()
                .sortedByDescending { it.second }
                .take(3)
                .forEach { (content, count) ->
                    val hexContent = ByteUtils.bytesToHex(content.toByteArray(), "")
                    println("    $hexContent (repeated $count times)")
                }
        }
    }

    /**
     * Test performance with larger Ninebot dataset
     */
    @Test
    fun testNinebotPerformanceAnalysis() {
        val frames = loadNinebotFrames("$testDataPath/RAW_2023_09_07_11_18_45.csv", maxFrames = 2000)
        
        assertTrue("Should load many frames for performance test", frames.size >= 1000)
        
        val startTime = System.currentTimeMillis()
        
        // Process all frames
        var totalBytes = 0
        var framesWithHeader = 0
        
        frames.forEach { frame ->
            totalBytes += frame.bleData.size
            
            // Check for Ninebot header pattern
            if (frame.bleData.size >= 2) {
                val firstByte = frame.bleData[0].toInt() and 0xFF
                val secondByte = frame.bleData[1].toInt() and 0xFF
                
                if (firstByte == 0x5A && secondByte == 0xA5) {
                    framesWithHeader++
                }
            }
        }
        
        val endTime = System.currentTimeMillis()
        val durationMs = endTime - startTime
        val framesPerSecond = (frames.size * 1000.0 / durationMs).toInt()
        val bytesPerSecond = (totalBytes * 1000.0 / durationMs).toInt()
        
        println("Ninebot Performance Analysis:")
        println("  Frames processed: ${frames.size}")
        println("  Total bytes: $totalBytes")
        println("  Frames with 5A A5 header: $framesWithHeader (${(framesWithHeader * 100.0 / frames.size).toInt()}%)")
        println("  Processing speed: $framesPerSecond frames/sec")
        println("  Throughput: $bytesPerSecond bytes/sec")
        println("  Time taken: ${durationMs}ms")
        
        // Performance should be reasonable
        assertTrue("Should process at reasonable speed", framesPerSecond > 500)
    }

    /**
     * Compare patterns across multiple Ninebot files
     */
    @Test
    fun testNinebotCrossFileAnalysis() {
        val testFiles = listOf(
            "RAW_2023_08_21_11_24_37.csv",
            "RAW_2023_09_07_11_18_45.csv",
            "RAW_2023_09_07_11_29_37.csv"
        )
        
        val filePatterns = mutableMapOf<String, MutableSet<String>>()
        var totalFrames = 0
        
        testFiles.forEach { filename ->
            val frames = loadNinebotFrames("$testDataPath/$filename", maxFrames = 100)
            totalFrames += frames.size
            
            val patternsInFile = mutableSetOf<String>()
            frames.forEach { frame ->
                val hexPattern = ByteUtils.bytesToHex(frame.bleData, "")
                patternsInFile.add(hexPattern)
            }
            
            filePatterns[filename] = patternsInFile
        }
        
        // Find common patterns across files
        val allPatterns = filePatterns.values.flatten().toSet()
        val commonPatterns = allPatterns.filter { pattern ->
            filePatterns.values.all { it.contains(pattern) }
        }
        
        println("Ninebot Cross-File Analysis:")
        println("  Files analyzed: ${testFiles.size}")
        println("  Total frames: $totalFrames")
        println("  Unique patterns per file:")
        
        filePatterns.forEach { (filename, patterns) ->
            println("    $filename: ${patterns.size} unique patterns")
        }
        
        println("  Common patterns across all files: ${commonPatterns.size}")
        
        if (commonPatterns.isNotEmpty()) {
            println("  Most common patterns:")
            commonPatterns.take(3).forEach { pattern ->
                println("    $pattern")
            }
        }
    }

    /**
     * Load Ninebot frames from WheelLog CSV file
     */
    private fun loadNinebotFrames(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): List<BleFrame> {
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
