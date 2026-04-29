package com.euc.ble.protocols

import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

class WheelLogInMotionTest {

    private val resourceDir = "/ble_frames/inmotion/RAW_WHEELLOG/"

    @Test
    fun decodeRealV9WheelLogFrames() {
        val protocol = InMotionProtocol()
        val frames = loadFrames("${resourceDir}RAW_2026_03_11_08_20_23.csv", maxFrames = 2000)
        assertTrue("Expected WheelLog frames", frames.isNotEmpty())

        val decoded = mutableListOf<EUCData>()
        for (frame in frames) {
            protocol.decode(frame.bleData)?.let(decoded::add)
        }

        assertTrue("Expected decoded telemetry from real WheelLog data", decoded.isNotEmpty())
        assertTrue(decoded.any { it.model.contains("V9", ignoreCase = true) })
        assertTrue(decoded.all { it.manufacturer.equals("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.voltage in 60.0..100.0 })
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
    }

    @Test
    fun decodeShortWheelLogCaptureStillProducesTelemetry() {
        val protocol = InMotionProtocol()
        val frames = loadFrames("${resourceDir}RAW_2026_03_11_12_16_00.csv")
        assertTrue("Expected short WheelLog capture", frames.isNotEmpty())

        val decodedCount = frames.count { protocol.decode(it.bleData) != null }
        assertTrue("Expected at least one decoded realtime frame", decodedCount > 0)
    }

    @Test
    fun decodeLegacyV5FWheelLogFrames() {
        val protocol = InMotionProtocol()
        val frames = loadFrames("${resourceDir}RAW_inmotion_V5F.csv")
        assertTrue("Expected legacy V5F WheelLog frames", frames.isNotEmpty())

        val decoded = mutableListOf<EUCData>()
        for (frame in frames) {
            protocol.decode(frame.bleData)?.let(decoded::add)
        }

        assertTrue("Expected decoded telemetry from legacy V5F frames", decoded.isNotEmpty())
        assertTrue(decoded.any { it.model.contains("V5F", ignoreCase = true) })
        assertTrue(decoded.all { it.manufacturer.equals("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.voltage in 40.0..100.0 })
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
    }

    @Test
    fun decodeLegacyV8SWheelLogFrames() {
        val protocol = InMotionProtocol()
        val frames = loadFrames("${resourceDir}RAW_inmotion_V8S.csv")
        assertTrue("Expected legacy V8S WheelLog frames", frames.isNotEmpty())

        val decoded = mutableListOf<com.euc.ble.models.EUCData>()
        for (frame in frames) {
            protocol.decode(frame.bleData)?.let(decoded::add)
        }

        assertTrue("Expected decoded telemetry from legacy V8S frames", decoded.isNotEmpty())
        assertTrue(decoded.any { it.model.contains("V8S", ignoreCase = true) })
        assertTrue(decoded.all { it.manufacturer.equals("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.voltage in 40.0..100.0 })
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
    }

    @Test
    fun decodeLegacyAlertCaptureWithoutCrashing() {
        val protocol = InMotionProtocol()
        val frames = loadFrames("${resourceDir}RAW_inmotion_alerts.csv")
        assertTrue("Expected legacy alert WheelLog frames", frames.isNotEmpty())

        var decodedCount = 0
        for (frame in frames) {
            if (protocol.decode(frame.bleData) != null) decodedCount++
        }

        assertTrue("Expected at least one decoded realtime packet from alert capture", decodedCount > 0)
    }

    private fun loadFrames(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): List<BleFrame> {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        val frames = mutableListOf<BleFrame>()
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            var lineNumber = 0
            reader.lineSequence().forEach { rawLine ->
                if (frames.size >= maxFrames) return@forEach
                lineNumber++
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach

                val splitIndex = line.indexOf(',')
                if (splitIndex <= 0 || splitIndex >= line.length - 1) return@forEach

                val ts = line.substring(0, splitIndex).trim()
                val hex = line.substring(splitIndex + 1).trim().trim('"')

                try {
                    frames.add(BleFrame(parseTimestampToMs(ts), ByteUtils.hexToBytes(hex), "L$lineNumber"))
                } catch (_: Exception) {
                    // ignore malformed row
                }
            }
        }
        return frames
    }

    private fun parseTimestampToMs(ts: String): Long {
        val parts = ts.trim().split(':', '.')
        return try {
            when (parts.size) {
                4 -> {
                    val h = parts[0].toInt()
                    val m = parts[1].toInt()
                    val s = parts[2].toInt()
                    val ms = parts[3].toInt()
                    (h * 3600000L + m * 60000L + s * 1000L + ms)
                }
                3 -> {
                    val m = parts[0].toInt()
                    val s = parts[1].toInt()
                    val ms = parts[2].toInt()
                    (m * 60000L + s * 1000L + ms)
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    data class BleFrame(
        val timestamp: Long,
        val bleData: ByteArray,
        val metadata: String
    )
}
