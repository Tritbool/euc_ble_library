// File: `euc-ble-core/src/test/java/com/euc/ble/protocols/GotwayProtocolWheelLogTest.kt`
package com.euc.ble.protocols

import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

class WheelLogGotwayTest {

    private val protocol = GotwayProtocol()
    private val resourceDir = "/ble_frames/gotway/RAW_WHEELLOG/"

    @Test
    fun testLoadAndDecodeRealFrames() {
        val frames = loadGotwayFrames("${resourceDir}RAW_2023_11_24_18_43_22.csv", maxFrames = 1000)
        assertTrue("Ressource CSV vide ou introuvable", frames.isNotEmpty())

        var decodedCount = 0
        var vendorMismatch = 0

        frames.forEach { frame ->
            val decoded = protocol.decode(frame.bleData)
            if (decoded != null) {
                decodedCount++
                // Basic invariants
                assertNotNull("rawData doit être préservé", decoded.rawData)
                assertTrue("timestamp doit être > 0", decoded.timestamp > 0)

                // Manufacturer attendu si présent
                if (decoded.manufacturer != null) {
                    if (!decoded.manufacturer!!.contains("Gotway", ignoreCase = true) &&
                        !decoded.manufacturer!!.contains("Begode", ignoreCase = true)
                    ) {
                        vendorMismatch++
                    }
                }

                // Ranges raisonnables (si présents)
                decoded.voltage.takeIf { it.isFinite() }?.let {
                    assertTrue("Voltage hors plage raisonnable", it in 20.0..120.0)
                }
                decoded.speed.takeIf { it.isFinite() }?.let {
                    assertTrue("Vitesse hors plage raisonnable", it in 0.0..100.0)
                }
                decoded.batteryLevel.takeIf { it in 0..255 }?.let {
                    assertTrue("Battery hors plage 0..100", it in 0..100)
                }
            }
        }

        val successRate = decodedCount.toDouble() / frames.size
        println("Decoded $decodedCount / ${frames.size} frames (${(successRate*100).toInt()}%)")
        // S'assurer d'un taux de décodage minimal (30%)
        assertTrue("Taux de décodage trop faible: ${(successRate*100).toInt()}%", successRate >= 0.3)

        // Pas trop de frames décodées avec fabricant incorrect
        assertTrue("Trop de décodages avec fabricant inattendu: $vendorMismatch",
            vendorMismatch <= decodedCount / 4)
    }

    @Test
    fun testDecodedFramesConsistencyShortSequence() {
        val frames = loadGotwayFrames("${resourceDir}RAW_2023_11_24_18_43_22.csv", maxFrames = 200)
        val decoded = frames.mapNotNull { protocol.decode(it.bleData) }
        assertTrue("Pas assez de frames décodées pour test de consistance", decoded.size >= 10)

        for (i in 1 until decoded.size) {
            val prev = decoded[i - 1]
            val cur = decoded[i]
            // Variation raisonnable de vitesse entre 2 frames consécutives
            val speedDiff = abs(cur.speed - prev.speed)
            assertTrue("Variation de vitesse anormale: $speedDiff", speedDiff < 20.0)

            // Distance non décroissante
            assertTrue("Distance décroissante détectée", cur.distance >= prev.distance - 0.5)
        }
    }

    private fun loadGotwayFrames(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): List<BleFrame> {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Ressource introuvable: $resourcePath")

        val frames = mutableListOf<BleFrame>()
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            var lineNumber = 0
            reader.lineSequence().forEach { rawLine ->
                if (frames.size >= maxFrames) return@forEach
                lineNumber++
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach

                // Split uniquement sur la première virgule (timestamp,hexdata)
                val idx = line.indexOf(',')
                if (idx <= 0 || idx >= line.length - 1) return@forEach

                val timestampStr = line.substring(0, idx).trim()
                var hexData = line.substring(idx + 1).trim()

                // Retirer éventuelles quotes autour de la colonne hex
                if (hexData.startsWith("\"") && hexData.endsWith("\"") && hexData.length >= 2) {
                    hexData = hexData.substring(1, hexData.length - 1)
                }

                try {
                    val bleData = ByteUtils.hexToBytes(hexData)
                    val timestampMs = parseTimestampToMs(timestampStr)
                    frames.add(BleFrame(timestamp = timestampMs, bleData = bleData, metadata = "L$lineNumber"))
                } catch (e: Exception) {
                    // ignorer ligne malformée
                }
            }
        }
        return frames
    }

    private fun parseTimestampToMs(ts: String): Long {
        // Supporte HH:MM:SS.mmm ou MM:SS.mmm ou SS.mmm
        val cleaned = ts.trim()
        val parts = cleaned.split(':', '.')
        return try {
            when (parts.size) {
                4 -> {
                    val h = parts[0].toInt()
                    val m = parts[1].toInt()
                    val s = parts[2].toInt()
                    val ms = parts[3].toInt()
                    (h * 3600000 + m * 60000 + s * 1000 + ms).toLong()
                }
                3 -> {
                    val m = parts[0].toInt()
                    val s = parts[1].toInt()
                    val ms = parts[2].toInt()
                    (m * 60000 + s * 1000 + ms).toLong()
                }
                2 -> {
                    val s = parts[0].toInt()
                    val ms = parts[1].toInt()
                    (s * 1000 + ms).toLong()
                }
                else -> cleaned.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    data class BleFrame(
        val timestamp: Long,
        val bleData: ByteArray,
        val metadata: String
    )
}
