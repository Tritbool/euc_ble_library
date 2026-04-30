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
    abstract fun createFrameParser(): FrameParser  // même parser qu'en production

    // ---------------------------------------------------------------
    // Oracle : FrameReassembler seul, sans décodage protocole
    // ---------------------------------------------------------------
    private fun oracleFrameCount(packets: List<ByteArray>): Int = runBlocking {
        val reassembler = FrameReassembler(createFrameParser())
        var count = 0
        val job = launch { reassembler.observeFrames().collect { count++ } }
        packets.forEach { reassembler.processIncomingBytes(it) }
        delay(1_000L) // laisser drainer le FrameReassembler
        job.cancel()
        count
    }

    // ---------------------------------------------------------------
    // Test principal
    // ---------------------------------------------------------------
    @Test
    fun `no frame is dropped between FrameReassembler and dataFlow`() = runBlocking {
        val packets = loadCsvFrames(csvResourcePath)
        assertTrue(
            "CSV trop petit : ${packets.size} paquets",
            packets.size >= minimumExpectedFrameCount
        )

        val expectedCount = oracleFrameCount(packets)
        assertTrue(
            "L'oracle a produit 0 frames — vérifie le CSV ou le FrameParser",
            expectedCount > 0
        )

        val protocol = createProtocol()
        val collectJob = async(Dispatchers.Default) {
            withTimeout(15_000L) {
                protocol.dataFlow.take(expectedCount).toList()
            }
        }

        packets.forEach { protocol.decode(it) }

        val collected: List<EUCData> = collectJob.await()

        assertEquals(
            "Drop détecté ! oracle=$expectedCount, reçu=${collected.size}",
            expectedCount,
            collected.size
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
    override fun createFrameParser() = FixedSizeFrameParser(
        GotwayProtocol.FRAME_SIZE,
        GotwayProtocol.HEADER,
        GotwayProtocol.FOOTER
    )
}

class KingsongNoDropTest : ProtocolNoDropTestBase() {
    override val csvResourcePath = "/ble_frames/kingsong/RAW_WHEELLOG/RAW_2023_08_25_15_02_03.csv"
    override val minimumExpectedFrameCount = 200
    override fun createProtocol() = KingsongProtocol()
    override fun createFrameParser() =
        ByteByByteFrameParser(KingsongProtocol.unpacker, resetUnpacker = {
            KingsongProtocol.unpackBuffer.clear()
        })

}