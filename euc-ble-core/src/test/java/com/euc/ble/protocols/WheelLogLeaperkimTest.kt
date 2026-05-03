package com.euc.ble.protocols

import com.euc.ble.SlowTest
import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import com.euc.ble.test.JUnit4AssertionsCompat.assumeTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

@SlowTest
class WheelLogLeaperkimTest {

    private val resourceDir = "/ble_frames/leaperkim/RAW_WHEELLOG/"
    private val tripResetMinDistanceBeforeReset = 5.0
    private val tripResetMaxDistanceAfterReset = 1.0
    private val tripResetMinDropDistance = 10.0

    @Test
    fun decodeRealLeaperkimWheelLogFrames() = runBlocking {
        val protocol = LeaperkimProtocol()
        val frames = loadFrames("${resourceDir}RAW_2026_04_30_07_04_10.csv", maxFrames = 7000)
        assertTrue("Expected WheelLog frames", frames.isNotEmpty())

        val collector = async {
            withTimeoutOrNull(8000) {
                protocol.dataFlow.take(1200).toList()
            } ?: emptyList()
        }

        delay(100)
        frames.forEach { protocol.decode(it.bleData) }
        delay(300)

        val decoded = collector.await()
        assertTrue("Expected decoded Leaperkim telemetry", decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.equals("Leaperkim", ignoreCase = true) })
        assertTrue(decoded.all { it.voltage in 90.0..160.0 })
        assertTrue(decoded.all { it.temperature in 20.0..80.0 })
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
        assertTrue(decoded.any { it.model.contains("Patton", ignoreCase = true) })
        protocol.close()
    }

    @Test
    fun decodedLeaperkimFramesAreConsistent() = runBlocking {
        val protocol = LeaperkimProtocol()
        val frames = loadFrames("${resourceDir}RAW_2026_04_30_20_08_09.csv", maxFrames = 9000)
        assertTrue("Expected WheelLog frames", frames.isNotEmpty())

        val collector = async {
            withTimeoutOrNull(10000) {
                protocol.dataFlow.take(1800).toList()
            } ?: emptyList()
        }
        delay(100)
        frames.forEach { protocol.decode(it.bleData) }
        delay(300)
        val decoded = collector.await()
        assertTrue("Need enough decoded frames for consistency checks", decoded.size >= 50)

        for (i in 1 until decoded.size) {
            val prev = decoded[i - 1]
            val cur = decoded[i]
            assertTrue("Voltage jump too large", abs(cur.voltage - prev.voltage) < 6.0)
            assertTrue("Speed jump too large", abs(cur.speed - prev.speed) < 25.0)
        }
        /*
        val hasTripResetToZero = decoded.zipWithNext().any { (prev, cur) ->
            isLikelyTripResetToZero(prev.distance, cur.distance)
        }
        assumeTrue(
            "Trip distance resets to near zero in this capture (kickstand/firmware behavior), monotonic trip check is not applicable",
            !hasTripResetToZero
        )*/

        for (i in 1 until decoded.size) {
            val prev = decoded[i - 1]
            val cur = decoded[i]
            assertTrue(
                "Trip distance should not sharply decrease",
                isLikelyTripResetToZero(prev.distance, cur.distance) || (cur.distance >= prev.distance - 1.0)
            )
        }
        protocol.close()
    }

    private fun isLikelyTripResetToZero(previousDistance: Double, currentDistance: Double): Boolean {
        return previousDistance >= tripResetMinDistanceBeforeReset &&
                currentDistance <= tripResetMaxDistanceAfterReset &&
                previousDistance - currentDistance >= tripResetMinDropDistance
    }

    @Test
    fun canHandleLeaperkimAndVeteranDeviceNames() {
        val protocol = LeaperkimProtocol()
        val devices = listOf(
            EUCDevice(name = "Patton-S", address = "A", manufacturerId = BLEConstants.MANUFACTURER_LEAPERKIM, rssi = -50),
            EUCDevice(name = "Veteran Patton", address = "B", manufacturerId = 0, rssi = -60),
            EUCDevice(name = "Leaperkim Lynx", address = "C", manufacturerId = 0, rssi = -70)
        )
        devices.forEach { assertTrue(protocol.canHandle(it)) }
        assertEquals(
            false,
            protocol.canHandle(EUCDevice(name = "KS-16X", address = "D", manufacturerId = BLEConstants.MANUFACTURER_KINGSONG, rssi = -45))
        )
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
