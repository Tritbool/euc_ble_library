package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.SlowTest
import io.github.tritbool.euc.ble.test.WheelLogCsvLoader
import io.github.tritbool.euc.ble.test.WheelLogResources
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs

@SlowTest
class WheelLogNinebotTest {
    companion object {
        private const val MAX_MALFORMED_ROW_RATIO = WheelLogCsvLoader.DEFAULT_MAX_MALFORMED_RATIO
    }

    private val resourceDir = WheelLogResources.rawDir("ninebot")

    private lateinit var protocol: NinebotProtocol

    @BeforeEach
    fun setUp() {
        protocol = NinebotProtocol()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
    }


    @Test
    fun decodeRealNinebotWheelLogFrames() {
        val frames = loadFrames("${resourceDir}RAW_2023_09_09_11_02_51.csv", maxFrames = 5000)
        assertTrue("Expected Ninebot WheelLog frames", frames.isNotEmpty())

        val decoded = frames.mapNotNull { protocol.decode(it) }
        assertTrue("Expected decoded Ninebot telemetry from WheelLog data", decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.equals("Ninebot", ignoreCase = true) })
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
        assertTrue(decoded.all { it.voltage in 20.0..150.0 })
        assertTrue(decoded.all { it.speed in -120.0..120.0 })
        assertTrue(decoded.any { it.model.contains("Ninebot", ignoreCase = true) })
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
            val frames = loadFrames("$resourceDir$fileName", maxFrames = 2000)
            assertTrue("Expected frames in $fileName", frames.isNotEmpty())

            val decodedCount = frames.count { protocol.decode(it) != null }
            assertTrue("Expected decoded telemetry in $fileName", decodedCount > 0)
        }
    }

    @Test
    fun decodedNinebotTelemetryIsReasonablyConsistent() {
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
    }

    private fun loadFrames(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): List<ByteArray> {
        val result = WheelLogCsvLoader.loadBytes(resourcePath, maxFrames)
        WheelLogCsvLoader.assertHealthyParse(resourcePath, result, MAX_MALFORMED_ROW_RATIO)
        return result.frames.map { it.bleData }
    }
}
