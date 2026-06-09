package com.euc.ble

import android.content.Context
import com.euc.ble.core.BLEConstants
import com.euc.ble.core.BLEManager
import com.euc.ble.core.ByteUtils
import com.euc.ble.core.DataCallback
import com.euc.ble.core.NoOpLogger
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
        client = EucBleClient(mock<Context>(), NoOpLogger())
        bleManager = client.bleManager
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        bleManager.cancelDataFlowCollection()
        bleManager.protocols.forEach { it.close() }
        Dispatchers.resetMain()
    }

    /*************************************************************************************/
    /*                                 BEGODE / GOTWAY                                   */
    /*************************************************************************************/
    @Test
    fun metadataAndFrameSelectGotwayProtocol() = runBlocking {
        bleManager.prepareProtocolCandidates(createTestDevice("Begode Master PRO", BLEConstants.MANUFACTURER_GOTWAY))
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/gotway/RAW_WHEELLOG/RAW_2023_11_25_15_11_39.csv",
            maxFrames = 300,
            expectedFrames = 1
        )
        assertEquals("GotwayProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.any { it.model.contains("Master PRO", ignoreCase = true)})
        }

    @Test
    fun noMetadataAndFrameSelectGotwayProtocol() = runBlocking {
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
    fun noMetadataAndFrameSelectInmotionProtocol() = runBlocking {
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
    fun MetadataAndFrameSelectInMotionProtocol() = runBlocking {
        bleManager.prepareProtocolCandidates(createTestDevice("P6", BLEConstants.MANUFACTURER_INMOTION))
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
    fun noMetadataAndFrameSelectKingsongProtocol() = runBlocking {
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
    fun MetadataAndFrameSelectKingsongProtocol() = runBlocking {
        bleManager.prepareProtocolCandidates(createTestDevice("KS-S22", BLEConstants.MANUFACTURER_KINGSONG))
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/kingsong/RAW_WHEELLOG/RAW_2023_08_25_15_02_03.csv",
            maxFrames = 300,
            expectedFrames = 1
        )
        assertEquals("KingsongProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("KingSong", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("KS-S22", ignoreCase = true) })
    }



    /*************************************************************************************/
    /*                                    KINGSONG                                       */
    /*************************************************************************************/
    @Test
    fun noMetadataAndFrameSelectLeaperkimProtocol() = runBlocking {
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
    fun MetadataAndFrameSelectLeaperkimProtocol() = runBlocking {
        bleManager.prepareProtocolCandidates(createTestDevice("KS-S22", BLEConstants.MANUFACTURER_KINGSONG))
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/leaperkim/RAW_WHEELLOG/RAW_2026_04_30_20_08_09.csv",
            maxFrames = 300,
            expectedFrames = 1
        )
        assertEquals("KingsongProtocol", bleManager.currentProtocol?.javaClass!!.simpleName ?: "")
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("KingSong", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("KS-S22", ignoreCase = true) })
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

        frames.forEach { frame ->
            bleManager.handleIncomingBytes(frame)
        }

        return buildList {
            withTimeout(10_000L) {
                repeat(expectedFrames) {
                    add(decodedFrames.receive())
                }
            }
        }.also {
            decodedFrames.close()
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