package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.SlowTest
import app.cash.turbine.test
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.test.WheelLogCsvLoader
import io.github.tritbool.euc.ble.test.WheelLogFrame
import io.github.tritbool.euc.ble.test.WheelLogResources
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@SlowTest
class WheelLogNosfetTest {

    private val resourceDir = WheelLogResources.rawDir("nosfet")
    private lateinit var protocol: NosfetProtocol

    @BeforeEach
    fun setUp() {
        protocol = NosfetProtocol()
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

            val decoded = mutableListOf<EUCData>()
            while (decoded.size < 1500) {
                val item = withTimeoutOrNull(150.milliseconds) { awaitItem() } ?: break
                decoded += item
            }

            assertTrue("Expected at least 1200 decoded Nosfet frames, got ${decoded.size}", decoded.size >= 1200)
            assertTrue("Expected decoded Nosfet telemetry", decoded.isNotEmpty())
            assertTrue(decoded.all { it.manufacturer.equals("Nosfet", ignoreCase = true) })
            assertTrue(decoded.any { it.model.contains("Nosfet", ignoreCase = true) })
            assertTrue(decoded.all { it.batteryLevel in 0..100 })
            assertTrue(decoded.all { it.rideTime >= 0 })
            assertTrue(decoded.all { abs(it.power - (it.voltage * it.current)) < 0.5 })
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun loadFrames(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): List<WheelLogFrame> {
        val result = WheelLogCsvLoader.load(resourcePath, maxFrames)
        WheelLogCsvLoader.assertHealthyParse(resourcePath, result)
        return result.frames
    }
}