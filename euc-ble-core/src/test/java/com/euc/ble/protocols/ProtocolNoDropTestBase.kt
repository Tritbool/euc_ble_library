package com.euc.ble.protocols

import com.euc.ble.core.ByteUtils
import com.euc.ble.frames.ByteByByteFrameParser
import com.euc.ble.frames.FixedSizeFrameParser
import com.euc.ble.frames.FrameParser
import com.euc.ble.frames.FrameReassembler
import com.euc.ble.models.EUCData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

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
    abstract fun createProtocol(): EUCProtocol

    @Test
    fun `no frame is dropped between decode and dataFlow`() = runBlocking {
        val packets = loadCsvFrames(csvResourcePath)
        assertTrue(packets.size >= minimumExpectedFrameCount)

        // Oracle : compter les émissions dataFlow sur une instance fraîche
        val oracle = createProtocol()
        var oracleCount = 0
        val oracleJob = launch { oracle.dataFlow.collect { oracleCount++ } }
        packets.forEach { oracle.decode(it) }
        delay(1_000L) // laisser drainer
        oracleJob.cancel()

        assertTrue("Oracle a produit 0 frames", oracleCount > 0)

        // SUT : rejouer sur une nouvelle instance, collecter exactement oracleCount
        val sut = createProtocol()
        val collectJob = async(Dispatchers.Default) {
            withTimeout(15_000L) { sut.dataFlow.take(oracleCount).toList() }
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
        val stream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Ressource introuvable : $resourcePath")
        val frames = mutableListOf<ByteArray>()
        var malformed = 0
        BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.lineSequence().forEach { rawLine ->
                if (frames.size >= maxFrames) return@forEach
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                val idx = line.indexOf(',')
                if (idx <= 0 || idx >= line.length - 1) return@forEach
                val hex = line.substring(idx + 1).trim().removeSurrounding("\"")
                try {
                    frames.add(ByteUtils.hexToBytes(hex))
                } catch (_: IllegalArgumentException) {
                    malformed++
                }
            }
        }
        val total = frames.size + malformed
        assertTrue("Aucune ligne parsable dans $resourcePath", total > 0)
        assertTrue("Trop de lignes malformées : $malformed/$total", malformed <= total / 5)
        return frames
    }
}

class GotwayNoDropTest : ProtocolNoDropTestBase() {
    override val csvResourcePath = "/ble_frames/gotway/RAW_WHEELLOG/RAW_2023_11_25_15_11_39.csv"
    override val minimumExpectedFrameCount = 200
    override fun createProtocol() = GotwayProtocol()

}

class KingsongNoDropTest : ProtocolNoDropTestBase() {
    override val csvResourcePath = "/ble_frames/kingsong/RAW_WHEELLOG/RAW_2023_08_25_15_02_03.csv"
    override val minimumExpectedFrameCount = 200
    override fun createProtocol() = KingsongProtocol()


}

class InmotionNoDropTest: ProtocolNoDropTestBase(){
    override val csvResourcePath = "/ble_frames/inmotion/RAW_WHEELLOG/RAW_inmotion_V8S.csv"
    override val minimumExpectedFrameCount = 200
    override fun createProtocol() = InMotionProtocol()

}