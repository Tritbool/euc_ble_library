package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.SlowTest
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import io.github.tritbool.euc.ble.test.WheelLogCsvLoader
import io.github.tritbool.euc.ble.test.WheelLogResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Base class for no-drop pipeline tests.
 *
 * Oracle  = FrameReassembler seul → compte les frames complètes extraites
 * SUT     = Protocol réel (dataFlow) → doit émettre exactement ce même nombre
 *
 * Si oracle == SUT : pas de drop.
 * Si SUT < oracle : drop détecté dans le pipeline async.
 */
sealed class ProtocolNoDropTestBase {

    abstract val csvResourcePath: String
    abstract val minimumExpectedFrameCount: Int
    abstract fun createProtocol(testScope: CoroutineScope): EUCProtocol

    private lateinit var oracle: EUCProtocol
    private lateinit var sut: EUCProtocol

    @BeforeEach
    fun setUp(){
        // Oracle : compter les émissions dataFlow sur une instance fraîche
        val testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        oracle = createProtocol(testScope)
        // SUT : rejouer sur une nouvelle instance, collecter exactement oracleCount
        sut = createProtocol(testScope)
    }

    @AfterEach
    fun tearDown() {
        oracle.close()
        sut.close()
    }

    @Test
    fun `no frame is dropped between decode and dataFlow`() = runTest {
        val packets = loadCsvFrames(csvResourcePath)
        assertTrue(packets.size >= minimumExpectedFrameCount)

       var oracleCount = 0
        val oracleJob = launch { oracle.dataFlow.collect { oracleCount++ } }
        packets.forEach { oracle.decode(it) }
        delay(5_000L.milliseconds) // laisser drainer
        oracleJob.cancel()

        assertTrue("Oracle a produit 0 frames", oracleCount > 0)

        val collectJob = async(Dispatchers.Default) {
            withTimeout(30_000L.milliseconds) { sut.dataFlow.take(oracleCount).toList() }
        }
        packets.forEach { sut.decode(it) }

        val collected = collectJob.await()
        assertEquals(
            "Drop détecté : oracle=$oracleCount, reçu=${collected.size}",
            oracleCount, collected.size
        )
    }

    // ---------------------------------------------------------------
    // Chargeur CSV partagé (remplace les doublons dans chaque fichier de test)
    // ---------------------------------------------------------------
    protected fun loadCsvFrames(
        resourcePath: String,
        maxFrames: Int = Int.MAX_VALUE
    ): List<ByteArray> {
        val result = WheelLogCsvLoader.loadBytes(resourcePath, maxFrames)
        WheelLogCsvLoader.assertHealthyParse(resourcePath, result)
        return result.frames.map { it.bleData }
    }
}

@SlowTest
class GotwayNoDropTest : ProtocolNoDropTestBase(){
    override val csvResourcePath = WheelLogResources.rawFile("gotway", "RAW_2023_11_25_15_11_39.csv")
    override val minimumExpectedFrameCount = 200
    override fun createProtocol(testScope: CoroutineScope)  = GotwayProtocol(testScope)

}
@SlowTest
class KingsongNoDropTest : ProtocolNoDropTestBase() {
    override val csvResourcePath = WheelLogResources.rawFile("kingsong", "RAW_2023_08_25_15_02_03.csv")
    override val minimumExpectedFrameCount = 200
    override fun createProtocol(testScope: CoroutineScope)  = KingsongProtocol(testScope)


}
@SlowTest
class InmotionNoDropTest: ProtocolNoDropTestBase(){
    override val csvResourcePath = WheelLogResources.rawFile("inmotion", "RAW_inmotion_V8S.csv")
    override val minimumExpectedFrameCount = 200
    override fun createProtocol(testScope: CoroutineScope)  = InMotionProtocol()

}

@SlowTest
class LeaperkimNoDropTest : ProtocolNoDropTestBase() {
    override val csvResourcePath = WheelLogResources.rawFile("leaperkim", "RAW_2026_04_30_07_04_10.csv")
    override val minimumExpectedFrameCount = 200
    override fun createProtocol(testScope: CoroutineScope)  = LeaperkimProtocol(testScope)
}

@SlowTest
class NosfetNoDropTest : ProtocolNoDropTestBase() {
    override val csvResourcePath = WheelLogResources.rawFile("nosfet", "RAW_2026_05_08_18_55_45.csv")
    override val minimumExpectedFrameCount = 200
    override fun createProtocol(testScope: CoroutineScope)  = NosfetProtocol(testScope)
}

@SlowTest
class NinebotNoDropTest : ProtocolNoDropTestBase() {
    override val csvResourcePath = WheelLogResources.rawFile("ninebot", "RAW_2023_09_07_11_29_37.csv")
    override val minimumExpectedFrameCount = 200
    override fun createProtocol(testScope: CoroutineScope) = NinebotProtocol()
}
