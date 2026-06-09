package com.euc.ble

import android.content.Context
import com.euc.ble.core.BLEManager
import com.euc.ble.core.ByteUtils
import com.euc.ble.core.NoOpLogger
import com.euc.ble.protocols.EUCProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

@SlowTest
class EucBleClientEntryPointWheelLogTest {
    private data class RoutingFixture(
        val expectedProtocolSimpleName: String,
        val expectedManufacturer: String,
        val resourcePath: String
    )

    private lateinit var client: EucBleClient
    private lateinit var registeredProtocolClasses: List<Class<out EUCProtocol>>

    @BeforeEach
    fun setUp() {
        client = EucBleClient(mock<Context>(), NoOpLogger())
        registeredProtocolClasses = extractRegisteredProtocols(client).map { it.javaClass as Class<out EUCProtocol> }
    }

    @AfterEach
    fun tearDown() {
        extractRegisteredProtocols(client).forEach { it.close() }
    }

    @Test
    fun entryPointDistributesRawWheelLogFramesToExpectedProtocol() = runBlocking {
        val fixtures = listOf(
            RoutingFixture("KingsongProtocol", "Kingsong", "/ble_frames/kingsong/RAW_WHEELLOG/RAW_2023_08_25_15_02_03.csv"),
            RoutingFixture("GotwayProtocol", "Gotway", "/ble_frames/gotway/RAW_WHEELLOG/RAW_2023_11_25_15_11_39.csv"),
            RoutingFixture("InMotionProtocol", "Inmotion", "/ble_frames/inmotion/RAW_WHEELLOG/RAW_inmotion_V8S.csv"),
            RoutingFixture("NinebotProtocol", "Ninebot", "/ble_frames/ninebot/RAW_WHEELLOG/RAW_2023_09_09_11_02_51.csv"),
            RoutingFixture("LeaperkimProtocol", "Leaperkim", "/ble_frames/leaperkim/RAW_WHEELLOG/RAW_2026_04_30_07_04_10.csv"),
            RoutingFixture("NosfetProtocol", "Nosfet", "/ble_frames/nosfet/RAW_WHEELLOG/RAW_2026_05_08_18_55_45.csv")
        )

        fixtures.forEach { fixture ->
            val frames = loadFrames(fixture.resourcePath, maxFrames = 2_000)
            assertTrue(frames.isNotEmpty(), "Expected WheelLog frames in ${fixture.resourcePath}")

            val expectedClass = registeredProtocolClasses.firstOrNull {
                it.simpleName == fixture.expectedProtocolSimpleName
            }
            assertTrue(expectedClass != null, "Expected ${fixture.expectedProtocolSimpleName} to be registered by EucBleClient")

            // Instantiate all registered protocols for this fixture run
            val entries = registeredProtocolClasses.map { cls -> cls to instantiateProtocol(cls) }
            val counts = entries.associate { (cls, _) -> cls to AtomicInteger(0) }
            val collectedManufacturers = mutableListOf<String>()

            // Launch one async collector per protocol in parallel
            val collectJobs = entries.map { (cls, protocol) ->
                launch {
                    protocol.dataFlow.collect { eucData ->
                        counts[cls]!!.incrementAndGet()
                        if (cls == expectedClass) {
                            synchronized(collectedManufacturers) {
                                collectedManufacturers.add(eucData.manufacturer)
                            }
                        }
                    }
                }
            }

            // Feed all frames to every protocol simultaneously
            frames.forEach { frame -> entries.forEach { (_, p) -> p.decode(frame) } }

            // Allow all async frame processing pipelines to drain.
            // Protocols that use FrameReassembler (Kingsong, Gotway, Leaperkim) enqueue data on IO
            // threads via runBlocking(IO); the delay gives the coroutine scheduler time to flush
            // all pending channel emissions before the collectors are cancelled.
            // This mirrors the oracle drain pattern used in ProtocolNoDropTestBase.
            delay(5_000L)

            collectJobs.forEach { it.cancel() }
            entries.forEach { (_, p) -> p.close() }

            val decodeCounts = counts.mapValues { (_, c) -> c.get() }

            val expectedDecodeCount = decodeCounts[expectedClass] ?: 0
            val maxDecodeCount = decodeCounts.values.maxOrNull() ?: 0
            assertTrue(
                expectedDecodeCount > 0,
                "Expected ${fixture.expectedProtocolSimpleName} to decode at least one frame from ${fixture.resourcePath}"
            )
            assertEquals(
                maxDecodeCount,
                expectedDecodeCount,
                "Expected ${fixture.expectedProtocolSimpleName} to be the dominant decoder for ${fixture.resourcePath}, counts=$decodeCounts"
            )

            assertTrue(
                collectedManufacturers.any { it.equals(fixture.expectedManufacturer, ignoreCase = true) },
                "Expected ${fixture.expectedProtocolSimpleName} decoded manufacturer to contain ${fixture.expectedManufacturer}"
            )
        }
    }

    private fun extractRegisteredProtocols(client: EucBleClient): List<EUCProtocol> {
        val bleManagerField = EucBleClient::class.java.getDeclaredField("bleManager").apply { isAccessible = true }
        val bleManager = bleManagerField.get(client) as BLEManager
        val protocolsField = BLEManager::class.java.getDeclaredField("protocols").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (protocolsField.get(bleManager) as List<EUCProtocol>)
    }

    private fun instantiateProtocol(protocolClass: Class<out EUCProtocol>): EUCProtocol {
        return protocolClass.getDeclaredConstructor().newInstance()
    }

    private fun loadFrames(resourcePath: String, maxFrames: Int): List<ByteArray> {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        val frames = mutableListOf<ByteArray>()
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            reader.lineSequence().forEach { rawLine ->
                if (frames.size >= maxFrames) return@forEach
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach

                val splitIndex = line.indexOf(',')
                if (splitIndex <= 0 || splitIndex >= line.length - 1) return@forEach

                val hex = line.substring(splitIndex + 1).trim().removeSurrounding("\"")
                runCatching { ByteUtils.hexToBytes(hex) }
                    .onSuccess { frames.add(it) }
            }
        }
        return frames
    }
}
