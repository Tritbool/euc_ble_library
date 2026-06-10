package com.euc.ble.protocols

import com.euc.ble.SlowTest
import app.cash.turbine.test
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@SlowTest
class WheelLogNosfetTest {

    private val resourceDir = "/ble_frames/nosfet/RAW_WHEELLOG/"
    private lateinit var protocol: NosfetProtocol

    @BeforeEach
    fun setUp() {
        //protocol = NosfetProtocol()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun decodeRealNosfetWheelLogFrames_diagnostic() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)

        val frames = loadFrames("${resourceDir}RAW_2026_05_08_18_55_45.csv", maxFrames = 200)
        assertTrue("Expected WheelLog frames", frames.isNotEmpty())

        var received = 0

        protocol.dataFlow.test(timeout = 100.milliseconds) {
            // envoyer quelques frames seulement
            frames.take(200).forEach { protocol.decode(it.bleData) }

            // consommer au plus 50 items
            repeat(50) {
                try {
                    awaitItem()
                    received++
                } catch (_: AssertionError) {
                    // plus rien à lire avant timeout Turbine
                                    }
            }

            cancelAndIgnoreRemainingEvents()
        }

        println("frames size=${frames.size}")
        println("debugFramesObserved=${(protocol as LeaperkimProtocol).debugFramesObserved}")
        println("debugFramesParsed=${(protocol as LeaperkimProtocol).debugFramesParsed}")
        println("debugFramesSent=${(protocol as LeaperkimProtocol).debugFramesSent}")
        println("debugSendFailures=${(protocol as LeaperkimProtocol).debugSendFailures}")
        println("received=$received")

        assertTrue("Expected at least one decoded frame", received > 0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun decodeRealNosfetWheelLogFrames() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)

        val frames = loadFrames("${resourceDir}RAW_2026_05_08_18_55_45.csv", maxFrames = 7000)
        assertTrue("Expected WheelLog frames", frames.isNotEmpty())

        protocol.dataFlow.test(timeout = 60_000.milliseconds) {
            frames.forEach { protocol.decode(it.bleData) }
            testScheduler.advanceUntilIdle()

            val decoded: List<EUCData> = buildList {
                repeat(1200) {
                    add(awaitItem())
                }
            }

            assertTrue("Expected decoded Nosfet telemetry", decoded.isNotEmpty())
            assertTrue(decoded.all { it.manufacturer.equals("Nosfet", ignoreCase = true) })
            assertTrue(decoded.any { it.model.contains("Nosfet", ignoreCase = true) })
            assertTrue(decoded.all { it.batteryLevel in 0..100 })
            assertTrue(decoded.all { it.rideTime >= 0 })
            assertTrue(decoded.all { abs(it.power - (it.voltage * it.current)) < 0.5 })

            cancelAndIgnoreRemainingEvents()
        }
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