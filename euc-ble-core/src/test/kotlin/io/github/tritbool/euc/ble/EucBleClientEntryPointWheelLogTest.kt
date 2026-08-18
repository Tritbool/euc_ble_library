package io.github.tritbool.euc.ble

import android.content.Context
import io.github.tritbool.euc.ble.core.BLEManager
import io.github.tritbool.euc.ble.core.DataCallback
import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.GotwayProtocol
import io.github.tritbool.euc.ble.protocols.InMotionProtocol
import io.github.tritbool.euc.ble.protocols.KingsongProtocol
import io.github.tritbool.euc.ble.protocols.LeaperkimProtocol
import io.github.tritbool.euc.ble.protocols.NinebotZProtocol
import io.github.tritbool.euc.ble.protocols.NosfetProtocol
import io.github.tritbool.euc.ble.test.WheelLogCsvLoader
import io.github.tritbool.euc.ble.test.WheelLogResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * End-to-end decoding tests that verify each registered protocol correctly decodes real BLE
 * frames captured from WheelLog.
 *
 * Protocol selection is performed by directly setting `currentProtocol` via reflection and
 * calling `startDataFlowCollection`, rather than relying on GATT fingerprinting (which
 * requires a live BLE connection). These tests focus purely on frame decoding correctness.
 */
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
    fun gotwayProtocolDecodesGotwayFrames() = runTest {
        val decoded = feedFramesWithProtocol<GotwayProtocol>(
            resourcePath = WheelLogResources.rawFile("gotway", "RAW_2023_11_25_15_11_39.csv"),
            maxFrames = 300,
            expectedFrames = 1
        )
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.batteryLevel in 0..100 })
    }

    /*************************************************************************************/
    /*                                    INMOTION                                       */
    /*************************************************************************************/
    @Test
    fun inMotionProtocolDecodesInMotionV8SFrames() = runTest {
        val decoded = feedFramesWithProtocol<InMotionProtocol>(
            resourcePath = WheelLogResources.rawFile("inmotion", "RAW_inmotion_V8S.csv"),
            maxFrames = 300,
            expectedFrames = 1
        )
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("V8S", ignoreCase = true) })
    }

    @Test
    fun inMotionProtocolDecodesInMotionP6Frames() = runTest {
        val decoded = feedFramesWithProtocol<InMotionProtocol>(
            resourcePath = WheelLogResources.rawFile("inmotion", "P6_RAW_2026_05_11_14_05_18.csv"),
            maxFrames = 300,
            expectedFrames = 1
        )
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("InMotion", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("P6", ignoreCase = true) })
    }

    /*************************************************************************************/
    /*                                    KINGSONG                                       */
    /*************************************************************************************/
    @Test
    fun kingsongProtocolDecodesKingsongFrames() = runTest {
        val decoded = feedFramesWithProtocol<KingsongProtocol>(
            resourcePath = WheelLogResources.rawFile("kingsong", "RAW_2023_08_25_15_02_03.csv"),
            maxFrames = 300,
            expectedFrames = 1
        )
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.any { it.manufacturer.contains("KingSong", ignoreCase = true) })
        assertTrue(decoded.any { it.model.contains("KS-S22", ignoreCase = true) })
    }

    /*************************************************************************************/
    /*                                   LEAPERKIM                                       */
    /*************************************************************************************/
    @Test
    fun leaperkimProtocolDecodesLeaperkimFrames() = runTest {
        val decoded = feedFramesWithProtocol<LeaperkimProtocol>(
            resourcePath = WheelLogResources.rawFile("leaperkim", "RAW_2026_04_30_07_04_10.csv"),
            maxFrames = 300,
            expectedFrames = 1
        )
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("LeaperKim", ignoreCase = true) })
    }

    @Test
    fun leaperkimProtocolDecodesNosfetFrames() = runTest {
        // Nosfet uses the same frame format as Leaperkim; verify the Leaperkim engine can decode
        // Nosfet raw frames.  The decoded data carries the Leaperkim manufacturer label because
        // LeaperkimProtocol is used directly here — NosfetProtocol is the right choice when the
        // device has been identified as a Nosfet wheel (see nosfetProtocolDecodesNosfetFrames).
        val decoded = feedFramesWithProtocol<LeaperkimProtocol>(
            resourcePath = WheelLogResources.rawFile("nosfet", "RAW_2026_05_08_18_55_45.csv"),
            maxFrames = 300,
            expectedFrames = 1
        )
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("Leaperkim", ignoreCase = true) })
    }

    @Test
    fun leaperkimProtocolDecodesPattonFrames() = runTest {
        val decoded = feedFramesWithProtocol<LeaperkimProtocol>(
            resourcePath = WheelLogResources.rawFile("leaperkim", "RAW_2026_04_30_07_04_10.csv"),
            maxFrames = 300,
            expectedFrames = 1
        )
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("Leaperkim", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("patton", ignoreCase = true) })
    }

    /*************************************************************************************/
    /*                                     NOSFET                                        */
    /*************************************************************************************/
    @Test
    fun nosfetProtocolDecodesNosfetFrames() = runTest {
        val decoded = feedFramesWithProtocol<NosfetProtocol>(
            resourcePath = WheelLogResources.rawFile("nosfet", "RAW_2026_05_08_18_55_45.csv"),
            maxFrames = 300,
            expectedFrames = 1
        )
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("Nosfet", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("Nosfet", ignoreCase = true) })
    }

    /*************************************************************************************/
    /*                                    NINEBOT-Z                                      */
    /*************************************************************************************/
    @Test
    fun ninebotZProtocolDecodesNinebotZFrames() = runTest {
        val decoded = feedFramesWithProtocol<NinebotZProtocol>(
            resourcePath = WheelLogResources.rawFile("ninebot", "RAW_2023_08_21_11_24_37.csv"),
            maxFrames = 300,
            expectedFrames = 1
        )
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.manufacturer.contains("Ninebot", ignoreCase = true) })
        assertTrue(decoded.all { it.model.contains("Ninebot", ignoreCase = true) })
    }

    /*************************************************************************************/
    /*                                    TOOLING                                        */
    /*************************************************************************************/

    private suspend inline fun <reified T : EUCProtocol> feedFramesWithProtocol(
        resourcePath: String,
        maxFrames: Int,
        expectedFrames: Int
    ): List<EUCData> {
        val protocol = bleManager.protocols.firstOrNull { it.javaClass == T::class.java } as? T
            ?: error("Protocol ${T::class.simpleName} not registered in EucBleClient")
        return feedFramesAndCollect(protocol, resourcePath, maxFrames, expectedFrames)
    }

    private suspend fun feedFramesAndCollect(
        protocol: EUCProtocol,
        resourcePath: String,
        maxFrames: Int,
        expectedFrames: Int
    ): List<EUCData> {
        val currentProtocolField = BLEManager::class.java.getDeclaredField("currentProtocol")
        currentProtocolField.isAccessible = true
        currentProtocolField.set(bleManager, protocol)
        bleManager.startDataFlowCollection(protocol)

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

    private fun loadFrames(resourcePath: String, maxFrames: Int): List<ByteArray> {
        val result = WheelLogCsvLoader.load(resourcePath, maxFrames)
        WheelLogCsvLoader.assertHealthyParse(resourcePath, result)
        return result.frames.map { it.bleData }
    }
}
