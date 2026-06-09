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

@SlowTest
class EucBleClientEntryPointWheelLogTest {
    private lateinit var client: EucBleClient
    private lateinit var bleManager: BLEManager

    @BeforeEach
    fun setUp() {
        client = EucBleClient(mock<Context>(), NoOpLogger())
        bleManager = extractBleManager(client)
    }

    @AfterEach
    fun tearDown() {
        cancelDataFlowCollection(bleManager)
        extractRegisteredProtocols(bleManager).forEach { it.close() }
    }

    @Test
    fun metadataAndFrameSelectGotwayProtocol() = runBlocking {
        prepareProtocolCandidates(createTestDevice("Begode Nikola", BLEConstants.MANUFACTURER_GOTWAY))
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/gotway/RAW_WHEELLOG/RAW_2023_11_25_15_11_39.csv",
            maxFrames = 300,
            expectedFrames = 1
        )

        assertEquals("GotwayProtocol", currentProtocolSimpleName())
        assertTrue(decoded.isNotEmpty())
        assertTrue(
            decoded.all {
                it.manufacturer.contains("Gotway", ignoreCase = true) ||
                    it.manufacturer.contains("Begode", ignoreCase = true)
            }
        )
    }

    @Test
    fun unknownMetadataFallsBackToFrameSignatureSelection() = runBlocking {
        prepareProtocolCandidates(createTestDevice("Unknown wheel", manufacturerId = 0))
        val decoded = feedFramesAndCollect(
            resourcePath = "/ble_frames/inmotion/RAW_WHEELLOG/RAW_inmotion_V8S.csv",
            maxFrames = 300,
            expectedFrames = 1
        )

        assertEquals("InMotionProtocol", currentProtocolSimpleName())
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("InMotion", ignoreCase = true) })
    }

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
            handleIncomingBytes(bleManager, frame)
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

    private fun prepareProtocolCandidates(device: EUCDevice) {
        invokePrivate(
            bleManager,
            "prepareProtocolCandidates",
            arrayOf(EUCDevice::class.java),
            arrayOf<Any?>(device)
        )
    }

    private fun currentProtocolSimpleName(): String {
        val protocolField = BLEManager::class.java.getDeclaredField("currentProtocol").apply { isAccessible = true }
        val protocol = protocolField.get(bleManager) as EUCProtocol?
        return requireNotNull(protocol) { "Protocol was not auto-selected" }.javaClass.simpleName
    }

    private fun extractBleManager(client: EucBleClient): BLEManager {
        val field = EucBleClient::class.java.getDeclaredField("bleManager").apply { isAccessible = true }
        return field.get(client) as BLEManager
    }

    private fun extractRegisteredProtocols(bleManager: BLEManager): List<EUCProtocol> {
        val field = BLEManager::class.java.getDeclaredField("protocols").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(bleManager) as List<EUCProtocol>
    }

    private fun cancelDataFlowCollection(bleManager: BLEManager) {
        invokePrivate(bleManager, "cancelDataFlowCollection")
    }

    private fun handleIncomingBytes(bleManager: BLEManager, frame: ByteArray) {
        invokePrivate(
            bleManager,
            "handleIncomingBytes",
            arrayOf(ByteArray::class.java),
            arrayOf<Any?>(frame)
        )
    }

    private fun invokePrivate(
        target: Any,
        methodName: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        args: Array<out Any?> = emptyArray()
    ) {
        target.javaClass.getDeclaredMethod(methodName, *parameterTypes).apply {
            isAccessible = true
            invoke(target, *args)
        }
    }

    private fun createTestDevice(name: String, manufacturerId: Int): EUCDevice {
        val stableHash = (name.fold(0L) { acc, c -> acc * 131L + c.code } and 0xFFFFFFFFL)
        val bytes = ByteArray(6) { index ->
            val shift = (5 - index) * 8
            ((stableHash shr shift) and 0xFF).toByte()
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
