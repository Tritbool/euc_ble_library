package com.euc.ble

import android.content.Context
import com.euc.ble.core.BLEConstants
import com.euc.ble.core.BLEManager
import com.euc.ble.core.ByteUtils
import com.euc.ble.core.DataCallback
import com.euc.ble.core.NoOpLogger
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.io.BufferedReader
import java.io.InputStreamReader

@SlowTest
class EucBleClientEntryPointWheelLogTest {
    private lateinit var client: EucBleClient
    private lateinit var bleManager: BLEManager

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setUp() {
        val testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        client = EucBleClient(mock<Context>(), NoOpLogger(), testScope)
        bleManager = client.bleManager
        //Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        bleManager.cancelDataFlowCollection()
        bleManager.protocols.forEach { it.close() }
    }

    /*************************************************************************************/
    /*                                    EXTREME BULL                                   */
    /*************************************************************************************/
    // NO DATA AVAILABLE YET

    /*************************************************************************************/
    /*                                 BEGODE / GOTWAY                                   */
    /*************************************************************************************/
    @Test
    fun metadataAndFrameSelectGotwayProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(
            createTestDevice(
                "Begode Master PRO",
                BLEConstants.MANUFACTURER_GOTWAY
            )
        )
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/gotway/RAW_WHEELLOG/RAW_2023_11_25_15_11_39.csv",
            maxFrames = 300,
            expectedFrames = 1
        )
        assertEquals("GotwayProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
    }

    @Test
    fun noMetadataAndFrameSelectGotwayProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(createTestDevice("Unknown wheel", manufacturerId = 0))
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/gotway/RAW_WHEELLOG/RAW_2023_11_25_15_11_39.csv",
            maxFrames = 300,
            expectedFrames = 1
        )
        assertEquals("GotwayProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
    }

    /*************************************************************************************/
    /*                                    INMOTION                                       */
    /*************************************************************************************/
    @Test
    fun noMetadataAndFrameSelectInmotionProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(createTestDevice("Unknown wheel", manufacturerId = 0))
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/inmotion/RAW_WHEELLOG/RAW_inmotion_V8S.csv",
            maxFrames = 300,
            expectedFrames = 1
        )

        assertEquals("InMotionProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("V8S", ignoreCase = true) })
    }

    @Test
    fun MetadataAndFrameSelectInMotionProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(
            createTestDevice(
                "P6",
                BLEConstants.MANUFACTURER_INMOTION
            )
        )
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/inmotion/RAW_WHEELLOG/P6_RAW_2026_05_11_14_05_18.csv",
            maxFrames = 300,
            expectedFrames = 1
        )
        assertEquals("InMotionProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("P6", ignoreCase = true) })
    }

    /*************************************************************************************/
    /*                                    KINGSONG                                       */
    /*************************************************************************************/
    @Test
    fun noMetadataAndFrameSelectKingsongProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(createTestDevice("Unknown wheel", manufacturerId = 0))
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/kingsong/RAW_WHEELLOG/RAW_2023_08_25_15_02_03.csv",
            maxFrames = 300,
            expectedFrames = 1
        )

        assertEquals("KingsongProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("KingSong", ignoreCase = true) })
    }

    @Test
    fun MetadataAndFrameSelectKingsongProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(
            createTestDevice(
                "KS-S22",
                BLEConstants.MANUFACTURER_KINGSONG
            )
        )
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/kingsong/RAW_WHEELLOG/RAW_2023_08_25_15_02_03.csv",
            maxFrames = 300,
            expectedFrames = 1
        )
        assertEquals("KingsongProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.any { it.manufacturer.contains("KingSong", ignoreCase = true) })
        assertTrue(decoded.any { it.model.contains("KS-S22", ignoreCase = true) })
    }


    /*************************************************************************************/
    /*                                   LEAPERKIM                                       */
    /*************************************************************************************/
    @Test
    fun noMetadataAndFrameSelectLeaperkimProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(createTestDevice("Unknown wheel", manufacturerId = 0))
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/leaperkim/RAW_WHEELLOG/RAW_2026_04_30_07_04_10.csv",
            maxFrames = 300,
            expectedFrames = 1
        )

        assertEquals("LeaperkimProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("LeaperKim", ignoreCase = true) })
    }

    @Test
    fun NosfetOnLeaperkimProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(
            createTestDevice(
                "Nosfet Aeon",
                BLEConstants.MANUFACTURER_LEAPERKIM
            )
        )
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/nosfet/RAW_WHEELLOG/RAW_2026_05_08_18_55_45.csv",
            maxFrames = 300,
            expectedFrames = 1
        )

        assertEquals("LeaperkimProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("Leaperkim", ignoreCase = true) })
    }

    @Test
    fun MetadataAndFrameSelectLeaperkimProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(
            createTestDevice(
                "PATTON",
                BLEConstants.MANUFACTURER_LEAPERKIM
            )
        )
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/leaperkim/RAW_WHEELLOG/RAW_2026_04_30_20_08_09.csv",
            maxFrames = 300,
            expectedFrames = 1
        )
        assertEquals("LeaperkimProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("Leaperkim", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("patton", ignoreCase = true) })
    }

    /*************************************************************************************/
    /*                                     NINEBOT                                       */
    /*************************************************************************************/
    // NO DATA AVAILABLE YET

    /*************************************************************************************/
    /*                                    NINEBOT-Z                                      */
    /*************************************************************************************/
    @Test
    fun noMetadataAndFrameSelectNosfetFallbackToLK() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(createTestDevice("Unknown wheel", manufacturerId = 0))
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/nosfet/RAW_WHEELLOG/RAW_2026_05_08_18_55_45.csv",
            maxFrames = 300,
            expectedFrames = 1
        )

        assertEquals("LeaperkimProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("LeaperKim", ignoreCase = true) })
    }

    @Test
    fun MetadataAndFrameSelectNosfetProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(
            createTestDevice(
                "Aero",
                BLEConstants.MANUFACTURER_NOSFET
            )
        )
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/nosfet/RAW_WHEELLOG/RAW_2026_05_08_18_55_45.csv",
            maxFrames = 300,
            expectedFrames = 1
        )
        assertEquals("NosfetProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("Nosfet", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("Nosfet", ignoreCase = true) })
    }


    /*************************************************************************************/
    /*                                     NOSFET                                        */
    /*************************************************************************************/
    @Test
    fun noMetadataAndFrameSelectNinebotZ() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(createTestDevice("Unknown wheel", manufacturerId = 0))
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/ninebot/RAW_WHEELLOG/RAW_2023_08_21_11_24_37.csv",
            maxFrames = 300,
            expectedFrames = 1
        )

        assertEquals("NinebotZProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("Ninebot", ignoreCase = true) })
    }

    @Test
    fun MetadataAndFrameSelectNinebotZProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(
            createTestDevice(
                "Z10",
                BLEConstants.MANUFACTURER_NINEBOT
            )
        )
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/ninebot/RAW_WHEELLOG/RAW_2023_08_21_11_24_37.csv",
            maxFrames = 300,
            expectedFrames = 1
        )
        assertEquals("NinebotZProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("Ninebot", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("Ninebot", ignoreCase = true) })
    }

    @Test
    fun MetadataAndFrameSelectNinebotProtocol() = runTest {
        //client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        bleManager.prepareProtocolCandidates(
            createTestDevice(
                "Ninebot",
                BLEConstants.MANUFACTURER_NINEBOT
            )
        )
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/ninebot/RAW_WHEELLOG/RAW_2023_08_21_11_24_37.csv",
            maxFrames = 300,
            expectedFrames = 1
        )
        assertEquals("NinebotZProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("Ninebot", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("Ninebot", ignoreCase = true) })
    }

    /*************************************************************************************/
    /*                                    TOOLING                                        */
    /*************************************************************************************/

    private suspend fun feedFramesAndCollect(
        resourcePath: String,
        maxFrames: Int,
        expectedFrames: Int
    ): List<EUCData> {
        val frames = loadFrames(resourcePath, maxFrames)
        val decodedFrames = Channel<EUCData>(Channel.UNLIMITED)
        client.setDataCallback(object : DataCallback {
            override fun onDataReceived(data: EUCData) {
                decodedFrames.trySend(data)
            }
        })
        frames.forEach { frame -> bleManager.handleIncomingBytes(frame) }
        decodedFrames.close()
        return buildList {
            for (item in decodedFrames) add(item)
        }.also {
            check(it.size >= expectedFrames) { "Expected $expectedFrames frames, got ${it.size}" }
        }
    }

    private fun createTestDevice(name: String, manufacturerId: Int): EUCDevice {
        val addressSeed = (name.fold(0L) { acc, c -> acc * 131L + c.code } and 0xFFFFFFFFL)
        val bytes = ByteArray(6) { index ->
            val shift = (5 - index) * 8
            ((addressSeed shr shift) and 0xFF).toByte()
        }
        val address = bytes.joinToString(":") { byte -> "%02X".format(byte) }
        return EUCDevice(
            bluetoothDevice = null,
            name = name,
            address = address,
            manufacturerId = manufacturerId,
            manufacturerData = null,
            rssi = -55
        )
    }

    private fun loadFrames(resourcePath: String, maxFrames: Int): List<ByteArray> {
        val stream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Missing resource $resourcePath")
        val frames = mutableListOf<ByteArray>()
        BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.lineSequence().forEach { rawLine ->
                if (frames.size >= maxFrames) return@forEach
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                val comma = line.indexOf(',')
                if (comma <= 0 || comma >= line.length - 1) return@forEach
                val hex = line.substring(comma + 1).trim().removeSurrounding("\"")
                runCatching { ByteUtils.hexToBytes(hex) }.getOrNull()?.let(frames::add)
            }
        }
        return frames
    }
}