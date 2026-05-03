package com.euc.ble.protocols

import com.euc.ble.SlowTest
import com.euc.ble.core.ByteUtils
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

@SlowTest
class WheelLogNinebotTest {

    private val resourceDir = "/ble_frames/ninebot/RAW_WHEELLOG/"

    @Test
    fun decodeRealNinebotWheelLogFrames() {
        val protocol = NinebotProtocol()
        val frames = loadFrames("${resourceDir}RAW_2023_09_09_11_02_51.csv", maxFrames = 5000)
        assertTrue("Expected Ninebot WheelLog frames", frames.isNotEmpty())

        val decoded = frames.mapNotNull { protocol.decode(it) }
        assertTrue("Expected decoded Ninebot telemetry from WheelLog data", decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.equals("Ninebot", ignoreCase = true) })
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
        assertTrue(decoded.all { it.voltage in 20.0..150.0 })
        assertTrue(decoded.all { it.speed in -120.0..120.0 })
        assertTrue(decoded.any { it.model.contains("Ninebot", ignoreCase = true) })
        protocol.close()
    }

    @Test
    fun decodeAllNinebotWheelLogFilesWithoutDroppingToZero() {
        val files = listOf(
            "RAW_2023_08_21_11_24_37.csv",
            "RAW_2023_09_07_11_18_45.csv",
            "RAW_2023_09_07_11_29_37.csv",
            "RAW_2023_09_09_11_02_51.csv"
        )

        files.forEach { fileName ->
            val protocol = NinebotProtocol()
            val frames = loadFrames("$resourceDir$fileName", maxFrames = 2000)
            assertTrue("Expected frames in $fileName", frames.isNotEmpty())

            val decodedCount = frames.count { protocol.decode(it) != null }
            assertTrue("Expected decoded telemetry in $fileName", decodedCount > 0)
            protocol.close()
        }
    }

    @Test
    fun decodedNinebotTelemetryIsReasonablyConsistent() {
        val protocol = NinebotProtocol()
        val frames = loadFrames("${resourceDir}RAW_2023_09_07_11_29_37.csv", maxFrames = 1200)
        assertTrue("Expected Ninebot WheelLog frames", frames.isNotEmpty())

        val decoded = frames.mapNotNull { protocol.decode(it) }
        assertTrue("Need enough decoded frames for consistency checks", decoded.size >= 20)

        for (i in 1 until decoded.size) {
            val previous = decoded[i - 1]
            val current = decoded[i]
            assertTrue("Voltage jump too large", abs(current.voltage - previous.voltage) < 10.0)
            assertTrue("Speed jump too large", abs(current.speed - previous.speed) < 25.0)
            assertTrue("Battery jump too large", abs(current.batteryLevel - previous.batteryLevel) <= 5)
        }
        protocol.close()
    }

    private fun loadFrames(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): List<ByteArray> {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        val frames = mutableListOf<ByteArray>()
        var malformedRows = 0
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            reader.lineSequence().forEach { rawLine ->
                if (frames.size >= maxFrames) return@forEach
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach

                val splitIndex = line.indexOf(',')
                if (splitIndex <= 0 || splitIndex >= line.length - 1) return@forEach

                val hex = line.substring(splitIndex + 1).trim().removeSurrounding("\"")
                try {
                    frames.add(ByteUtils.hexToBytes(hex))
                } catch (_: IllegalArgumentException) {
                    malformedRows++
                }
            }
        }
        val totalRows = frames.size + malformedRows
        assertTrue("No parsable rows found in $resourcePath", totalRows > 0)
        assertTrue("Too many malformed rows in $resourcePath", malformedRows <= totalRows / 5)
        return frames
    }
}
