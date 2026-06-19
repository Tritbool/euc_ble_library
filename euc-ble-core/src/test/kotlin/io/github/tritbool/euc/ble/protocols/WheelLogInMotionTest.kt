package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.SlowTest
import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

@SlowTest
class WheelLogInMotionTest {

    private val resourceDir = "/ble_frames/inmotion/RAW_WHEELLOG/"

    private lateinit var protocol: InMotionProtocol

    @BeforeEach
    fun setUp() {
        protocol = InMotionProtocol()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
    }


    @Test
    fun decodeRealV9WheelLogFrames() {

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
        assertTrue(decoded.any { it.rideTime > 0 })
        assertTrue(decoded.any { abs(it.power - (it.voltage * it.current)) < 0.5 })

    }

    @Test
    fun decodeShortWheelLogCaptureStillProducesTelemetry() {
        val frames = loadFrames("${resourceDir}RAW_2026_03_11_12_16_00.csv")
        assertTrue("Expected short WheelLog capture", frames.isNotEmpty())

        val decodedCount = frames.count { protocol.decode(it.bleData) != null }
        assertTrue("Expected at least one decoded realtime frame", decodedCount > 0)
    }

    @Test
    fun decodeLegacyV5FWheelLogFrames() {
        val frames = loadFrames("${resourceDir}RAW_inmotion_V5F.csv")
        assertTrue("Expected legacy V5F WheelLog frames", frames.isNotEmpty())

        val decoded = mutableListOf<EUCData>()
        for (frame in frames) {
            protocol.decode(frame.bleData)?.let(decoded::add)
        }

        assertTrue("Expected decoded telemetry from legacy V5F frames", decoded.isNotEmpty())
        assertTrue(decoded.any { it.model.contains("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.manufacturer.equals("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.voltage in 40.0..100.0 })
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
        assertTrue(decoded.any { it.rideTime > 0 })
    }

    @Test
    fun decodeLegacyV8SWheelLogFrames() {
        val frames = loadFrames("${resourceDir}RAW_inmotion_V8S.csv")
        assertTrue("Expected legacy V8S WheelLog frames", frames.isNotEmpty())

        val decoded = mutableListOf<EUCData>()
        for (frame in frames) {
            protocol.decode(frame.bleData)?.let(decoded::add)
        }

        assertTrue("Expected decoded telemetry from legacy V8S frames", decoded.isNotEmpty())
        assertTrue(decoded.any { it.model.contains("V8S", ignoreCase = true) })
        assertTrue(decoded.all { it.manufacturer.equals("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.voltage in 40.0..100.0 })
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
        assertTrue(decoded.any { it.rideTime > 0 })
    }

    @Test
    fun decodeLegacyAlertCaptureWithoutCrashing() {
        val frames = loadFrames("${resourceDir}RAW_inmotion_alerts.csv")
        assertTrue("Expected legacy alert WheelLog frames", frames.isNotEmpty())

        var decodedCount = 0
        for (frame in frames) {
            if (protocol.decode(frame.bleData) != null) decodedCount++
        }

        assertTrue(
            "Expected at least one decoded realtime packet from alert capture",
            decodedCount > 0
        )
    }

    @Test
    fun decodeP6WheelLogFramesUsesExpectedVoltageAndTotalDistance() {
        val frames =
            loadFrames("${resourceDir}P6_RAW_2026_05_11_14_05_18.csv", maxFrames = 1800)
        assertTrue("Expected P6 WheelLog frames", frames.isNotEmpty())

        val decoded = mutableListOf<EUCData>()
        for (frame in frames) {
            protocol.decode(frame.bleData)?.let(decoded::add)
        }
        assertTrue("Expected decoded telemetry from P6 WheelLog frames", decoded.isNotEmpty())

        val first = decoded.first()
        val last = decoded.last()
        assertTrue(first.model.contains("P6", ignoreCase = true))
        assertEquals(223.95, first.voltage, 0.2)
        assertEquals(587.92, last.totalDistance ?: -1.0, 0.02)

    }

    @Test
    fun exportDecodedP6WheelLogFramesToHumanReadableCsv() {

        val frames = loadFrames("${resourceDir}P6_RAW_2026_05_11_14_05_18.csv", maxFrames = 12000)
        assertTrue("Expected P6 WheelLog frames", frames.isNotEmpty())

        val decoded = mutableListOf<EUCData>()
        for (frame in frames) {
            protocol.decode(frame.bleData)?.let(decoded::add)
        }
        assertTrue("Expected decoded telemetry from P6 WheelLog frames", decoded.isNotEmpty())

        val outputDir = Path.of("build", "reports", "decoded-wheellog")
        Files.createDirectories(outputDir)
        val outputFile = outputDir.resolve("P6_2026_05_11_14_05_18.decoded.csv")

        Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8).use { writer ->
            writer.appendLine(
                "index,timestamp_ms,manufacturer,model,speed_kmh,voltage_v,current_a,power_w,temperature_c,motor_temperature_c,battery_level,distance_km,total_distance_km,ride_time_s,is_charging,raw_hex"
            )
            decoded.forEachIndexed { index, data ->
                writer.appendLine(data.toCsvRow(index))
            }
        }

        assertTrue("Decoded CSV should be generated", Files.exists(outputFile))
        assertTrue("Decoded CSV should contain data rows", Files.size(outputFile) > 300)

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
                    frames.add(
                        BleFrame(
                            parseTimestampToMs(ts),
                            ByteUtils.hexToBytes(hex),
                            "L$lineNumber"
                        )
                    )
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

    private fun EUCData.toCsvRow(index: Int): String {
        val columns = listOf(
            index.toString(),
            timestamp.toString(),
            manufacturer,
            model,
            speed.toString(),
            voltage.toString(),
            current.toString(),
            power.toString(),
            temperature.toString(),
            motorTemperature?.toString() ?: "null",
            batteryLevel.toString(),
            distance.toString(),
            totalDistance?.toString() ?: "null",
            rideTime.toString(),
            isCharging.toString(),
            ByteUtils.bytesToHex(rawData)
        )
        return columns.joinToString(",") { csvEscape(it) }
    }

    private fun csvEscape(value: String): String {
        if (!value.contains(',') && !value.contains('"') && !value.contains('\n') && !value.contains(
                '\r'
            )
        ) {
            return value
        }
        return "\"${value.replace("\"", "\"\"")}\""
    }
}
