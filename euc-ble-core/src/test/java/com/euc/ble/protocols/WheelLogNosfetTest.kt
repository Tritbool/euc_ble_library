package com.euc.ble.protocols

import com.euc.ble.SlowTest
import com.euc.ble.core.ByteUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

@SlowTest
class WheelLogNosfetTest {

    private val resourceDir = "/ble_frames/nosfet/RAW_WHEELLOG/"

    @Test
    fun decodeRealNosfetWheelLogFrames() = runBlocking {
        val protocol = NosfetProtocol()
        val frames = loadFrames("${resourceDir}RAW_2026_05_08_18_55_45.csv", maxFrames = 7000)
        assertTrue("Expected WheelLog frames", frames.isNotEmpty())

        val collector = async {
            withTimeoutOrNull(8_000) {
                protocol.dataFlow.take(1200).toList()
            } ?: emptyList()
        }

        delay(100)
        frames.forEach { protocol.decode(it.bleData) }
        delay(300)

        val decoded = collector.await()
        assertTrue("Expected decoded Nosfet telemetry", decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.equals("Nosfet", ignoreCase = true) })
        assertTrue(decoded.any { it.model.contains("Nosfet", ignoreCase = true) })
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
        assertTrue(decoded.all { it.rideTime >= 0 })
        assertTrue(decoded.all { abs(it.power - (it.voltage * it.current)) < 0.5 })
        protocol.close()
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
                    (h * 3_600_000L + m * 60_000L + s * 1_000L + ms)
                }

                3 -> {
                    val m = parts[0].toInt()
                    val s = parts[1].toInt()
                    val ms = parts[2].toInt()
                    (m * 60_000L + s * 1_000L + ms)
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
