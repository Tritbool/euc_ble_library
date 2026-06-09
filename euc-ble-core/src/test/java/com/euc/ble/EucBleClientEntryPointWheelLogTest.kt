package com.euc.ble

import android.content.Context
import com.euc.ble.core.BLEConstants
import com.euc.ble.core.BLEManager
import com.euc.ble.core.ByteUtils
import com.euc.ble.core.DataCallback
import com.euc.ble.core.NoOpLogger
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import com.euc.ble.protocols.EUCProtocol
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private data class ClientFixture(
        val expectedProtocolSimpleName: String,
        val device: EUCDevice,
        val expectedManufacturers: Set<String>,
        val resourcePath: String,
        val maxFrames: Int,
        val decodedFrameCount: Int = EXPECTED_DECODED_FRAME_COUNT,
        val additionalAssertions: (List<EUCData>) -> Unit = {}
    )

    private lateinit var client: EucBleClient
    private lateinit var bleManager: BLEManager
    private lateinit var registeredProtocols: List<EUCProtocol>

    @BeforeEach
    fun setUp() {
        client = EucBleClient(mock<Context>(), NoOpLogger())
        bleManager = extractBleManager(client)
        registeredProtocols = extractRegisteredProtocols(bleManager)
    }

    @AfterEach
    fun tearDown() {
        cancelDataFlowCollection(bleManager)
        registeredProtocols.forEach { it.close() }
    }

    @Test
    fun kingsongWheelLogIsHandledByKingsongProtocolOnly() = runBlocking {
        assertClientDecodesWheelLog(
            ClientFixture(
                expectedProtocolSimpleName = "KingsongProtocol",
                device = createTestDevice("KS-16X", BLEConstants.MANUFACTURER_KINGSONG),
                expectedManufacturers = setOf("KingSong"),
                resourcePath = "/ble_frames/kingsong/RAW_WHEELLOG/RAW_2023_08_25_15_02_03.csv",
                maxFrames = 4_000
            ) { decoded ->
                assertTrue(decoded.all { it.voltage in 60.0..130.0 })
                assertTrue(decoded.all { it.batteryLevel in 0..100 })
                assertTrue(decoded.all { it.rideTime >= 0 })
            }
        )
    }

    @Test
    fun gotwayWheelLogIsHandledByGotwayProtocolOnly() = runBlocking {
        assertClientDecodesWheelLog(
            ClientFixture(
                expectedProtocolSimpleName = "GotwayProtocol",
                device = createTestDevice("Begode Nikola"),
                expectedManufacturers = setOf("Gotway", "Begode"),
                resourcePath = "/ble_frames/gotway/RAW_WHEELLOG/RAW_2023_11_25_15_11_39.csv",
                maxFrames = 2_000
            ) { decoded ->
                assertTrue(decoded.all { it.batteryLevel in 0..100 })
                assertTrue(decoded.any { it.model.contains("Type", ignoreCase = true) })
            }
        )
    }

    @Test
    fun inmotionWheelLogIsHandledByInMotionProtocolOnly() = runBlocking {
        assertClientDecodesWheelLog(
            ClientFixture(
                expectedProtocolSimpleName = "InMotionProtocol",
                device = createTestDevice("V8S", BLEConstants.MANUFACTURER_INMOTION),
                expectedManufacturers = setOf("InMotion"),
                resourcePath = "/ble_frames/inmotion/RAW_WHEELLOG/RAW_inmotion_V8S.csv",
                maxFrames = 2_000
            ) { decoded ->
                assertTrue(decoded.any { it.model.contains("V8S", ignoreCase = true) })
                assertTrue(decoded.all { it.voltage in 40.0..100.0 })
                assertTrue(decoded.all { it.batteryLevel in 0..100 })
            }
        )
    }

    @Test
    fun ninebotWheelLogIsHandledByNinebotProtocolOnly() = runBlocking {
        assertClientDecodesWheelLog(
            ClientFixture(
                expectedProtocolSimpleName = "NinebotProtocol",
                device = createTestDevice("Ninebot One"),
                expectedManufacturers = setOf("Ninebot"),
                resourcePath = "/ble_frames/ninebot/RAW_WHEELLOG/RAW_2023_09_09_11_02_51.csv",
                maxFrames = 5_000
            ) { decoded ->
                assertTrue(decoded.any { it.model.contains("Ninebot", ignoreCase = true) })
                assertTrue(decoded.all { it.voltage in 20.0..150.0 })
                assertTrue(decoded.all { it.batteryLevel in 0..100 })
            }
        )
    }

    @Test
    fun leaperkimWheelLogIsHandledByLeaperkimProtocolOnly() = runBlocking {
        assertClientDecodesWheelLog(
            ClientFixture(
                expectedProtocolSimpleName = "LeaperkimProtocol",
                device = createTestDevice("Patton-S", BLEConstants.MANUFACTURER_LEAPERKIM),
                expectedManufacturers = setOf("Leaperkim"),
                resourcePath = "/ble_frames/leaperkim/RAW_WHEELLOG/RAW_2026_04_30_07_04_10.csv",
                maxFrames = 15_000
            ) { decoded ->
                assertTrue(decoded.any { it.model.contains("Patton", ignoreCase = true) })
                assertTrue(decoded.all { it.voltage in 90.0..160.0 })
                assertTrue(decoded.all { it.batteryLevel in 0..100 })
            }
        )
    }

    @Test
    fun nosfetWheelLogIsHandledByNosfetProtocolOnly() = runBlocking {
        assertClientDecodesWheelLog(
            ClientFixture(
                expectedProtocolSimpleName = "NosfetProtocol",
                device = createTestDevice("Nosfet Aero"),
                expectedManufacturers = setOf("Nosfet"),
                resourcePath = "/ble_frames/nosfet/RAW_WHEELLOG/RAW_2026_05_08_18_55_45.csv",
                maxFrames = 3_000
            ) { decoded ->
                assertTrue(decoded.any { it.model.contains("Nosfet", ignoreCase = true) })
                assertTrue(decoded.all { it.batteryLevel in 0..100 })
                assertTrue(decoded.all { it.rideTime >= 0 })
            }
        )
    }

    private suspend fun assertClientDecodesWheelLog(fixture: ClientFixture) = coroutineScope {
        val frames = loadFrames(fixture.resourcePath, fixture.maxFrames)
        assertTrue(frames.isNotEmpty(), "Expected WheelLog frames in ${fixture.resourcePath}")

        val matchingProtocols = registeredProtocols.filter { it.canHandle(fixture.device) }
        assertEquals(
            1,
            matchingProtocols.size,
            "Expected exactly one built-in protocol for ${fixture.device.name}, matches=${matchingProtocols.map { it.javaClass.simpleName }}"
        )

        val selectedProtocol = matchingProtocols.single()
        assertEquals(fixture.expectedProtocolSimpleName, selectedProtocol.javaClass.simpleName)

        val decodedFrames = Channel<EUCData>(capacity = Channel.UNLIMITED)
        val decodedCount = AtomicInteger(0)

        client.setDataCallback(object : DataCallback {
            override fun onDataReceived(data: EUCData) {
                decodedCount.incrementAndGet()
                decodedFrames.trySend(data)
            }
        })

        setCurrentProtocol(bleManager, selectedProtocol)
        startDataFlowCollection(bleManager, selectedProtocol)
        delay(COLLECTOR_SUBSCRIBE_DELAY_MS)

        val feedJob = launch {
            for (frame in frames) {
                handleIncomingBytes(bleManager, frame)
                if (decodedCount.get() >= fixture.decodedFrameCount) {
                    break
                }
            }
        }

        try {
            val decoded = withTimeout(DECODE_TIMEOUT_MS) {
                buildList {
                    repeat(fixture.decodedFrameCount) {
                        add(decodedFrames.receive())
                    }
                }
            }

            assertEquals(fixture.decodedFrameCount, decoded.size)
            assertTrue(
                decoded.all { data ->
                    fixture.expectedManufacturers.any { expected ->
                        data.manufacturer.equals(expected, ignoreCase = true)
                    }
                },
                "Expected only ${fixture.expectedManufacturers} data from ${fixture.expectedProtocolSimpleName}"
            )
            fixture.additionalAssertions(decoded)
        } finally {
            feedJob.join()
            cancelDataFlowCollection(bleManager)
            decodedFrames.close()
        }
    }

    private fun extractBleManager(client: EucBleClient): BLEManager {
        val bleManagerField = EucBleClient::class.java.getDeclaredField("bleManager").apply { isAccessible = true }
        return bleManagerField.get(client) as BLEManager
    }

    private fun extractRegisteredProtocols(bleManager: BLEManager): List<EUCProtocol> {
        val protocolsField = BLEManager::class.java.getDeclaredField("protocols").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (protocolsField.get(bleManager) as List<EUCProtocol>)
    }

    private fun setCurrentProtocol(bleManager: BLEManager, protocol: EUCProtocol) {
        BLEManager::class.java.getDeclaredField("currentProtocol").apply {
            isAccessible = true
            set(bleManager, protocol)
        }
    }

    private fun startDataFlowCollection(bleManager: BLEManager, protocol: EUCProtocol) {
        BLEManager::class.java.getDeclaredMethod("startDataFlowCollection", EUCProtocol::class.java).apply {
            isAccessible = true
            invoke(bleManager, protocol)
        }
    }

    private fun cancelDataFlowCollection(bleManager: BLEManager) {
        BLEManager::class.java.getDeclaredMethod("cancelDataFlowCollection").apply {
            isAccessible = true
            invoke(bleManager)
        }
    }

    private fun handleIncomingBytes(bleManager: BLEManager, frame: ByteArray) {
        BLEManager::class.java.getDeclaredMethod("handleIncomingBytes", ByteArray::class.java).apply {
            isAccessible = true
            invoke(bleManager, frame)
        }
    }

    private fun createTestDevice(name: String, manufacturerId: Int = 0): EUCDevice {
        val seed = name.fold(0L) { acc, char -> (acc * DEVICE_ADDRESS_HASH_PRIME + char.code) and MAC_ADDRESS_MASK }
        val address = (5 downTo 0).joinToString(":") { index ->
            ((seed shr (index * 8)) and 0xFF).toString(16).padStart(2, '0')
        }
        return EUCDevice(
            name = name,
            address = address,
            manufacturerId = manufacturerId,
            rssi = -42
        )
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

    companion object {
        private const val EXPECTED_DECODED_FRAME_COUNT = 200
        private const val COLLECTOR_SUBSCRIBE_DELAY_MS = 150L
        private const val DECODE_TIMEOUT_MS = 15_000L
        // Small prime commonly used in simple rolling hashes to spread values cheaply in tests.
        private const val DEVICE_ADDRESS_HASH_PRIME = 131L
        // Keep only 48 bits so the generated synthetic address always fits the 6-byte MAC format.
        private const val MAC_ADDRESS_MASK = 0xFFFFFFFFFFFFL
    }
}
