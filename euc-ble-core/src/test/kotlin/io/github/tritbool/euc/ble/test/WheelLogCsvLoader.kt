package io.github.tritbool.euc.ble.test

import io.github.tritbool.euc.ble.core.ByteUtils
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.BufferedReader
import java.io.InputStreamReader

object WheelLogResources {
    private const val RAW_BASE_DIR = "/ble_frames"
    private const val RAW_SUB_DIR = "RAW_WHEELLOG"

    fun rawDir(brand: String): String = "$RAW_BASE_DIR/$brand/$RAW_SUB_DIR/"

    fun rawFile(brand: String, fileName: String): String = "${rawDir(brand)}$fileName"
}

class WheelLogFrame(
    val timestamp: Long,
    val bleData: ByteArray,
    val metadata: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WheelLogFrame) return false
        if (timestamp != other.timestamp) return false
        if (!bleData.contentEquals(other.bleData)) return false
        return metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + bleData.contentHashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}

data class WheelLogLoadResult(
    val frames: List<WheelLogFrame>,
    val totalRows: Int,
    val malformedRows: Int
) {
    val parsedRows: Int get() = frames.size
    val malformedRatio: Double
        get() = if (totalRows == 0) 0.0 else malformedRows.toDouble() / totalRows.toDouble()
}

object WheelLogCsvLoader {
    const val DEFAULT_MAX_MALFORMED_RATIO = 0.20

    fun load(resourcePath: String, maxFrames: Int = Int.MAX_VALUE): WheelLogLoadResult {
        val stream = WheelLogCsvLoader::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        val frames = mutableListOf<WheelLogFrame>()
        var malformedRows = 0
        var totalRows = 0

        BufferedReader(InputStreamReader(stream)).use { reader ->
            var lineNumber = 0
            while (totalRows < maxFrames) {
                val rawLine = reader.readLine() ?: break
                lineNumber++

                val line = rawLine.trim()
                if (line.isEmpty()) continue

                if (lineNumber == 1 && line.startsWith("timestamp", ignoreCase = true)) {
                    continue
                }

                val splitIndex = line.indexOf(',')
                if (splitIndex <= 0 || splitIndex >= line.length - 1) {
                    malformedRows++
                    totalRows++
                    continue
                }

                val ts = line.substring(0, splitIndex).trim()
                val hex = line.substring(splitIndex + 1).trim().removeSurrounding("\"")
                if (hex.isEmpty()) {
                    malformedRows++
                    totalRows++
                    continue
                }

                totalRows++
                try {
                    frames.add(
                        WheelLogFrame(
                            timestamp = parseTimestampToMs(ts),
                            bleData = ByteUtils.hexToBytes(hex),
                            metadata = "L$lineNumber"
                        )
                    )
                } catch (_: Exception) {
                    malformedRows++
                }
            }
        }

        return WheelLogLoadResult(frames = frames, totalRows = totalRows, malformedRows = malformedRows)
    }

    fun assertHealthyParse(
        resourcePath: String,
        result: WheelLogLoadResult,
        maxMalformedRatio: Double = DEFAULT_MAX_MALFORMED_RATIO
    ) {
        assertTrue(result.totalRows > 0, "No parsable rows found in $resourcePath")
        val maxMalformedRows = (result.totalRows * maxMalformedRatio).toInt()
        assertTrue(
            result.malformedRows <= maxMalformedRows,
            "Too many malformed rows in $resourcePath: ${result.malformedRows} out of ${result.totalRows} (max: $maxMalformedRows)"
        )
    }

    private fun parseTimestampToMs(ts: String): Long {
        val parts = ts.trim().split(':', '.')
        return try {
            when (parts.size) {
                4 -> {
                    val h = parts[0].toInt()
                    val m = parts[1].toInt()
                    val s = parts[2].toInt()
                    val ms = parts[3].toInt()
                    h * 3_600_000L + m * 60_000L + s * 1_000L + ms
                }

                3 -> {
                    val m = parts[0].toInt()
                    val s = parts[1].toInt()
                    val ms = parts[2].toInt()
                    m * 60_000L + s * 1_000L + ms
                }

                2 -> {
                    val s = parts[0].toInt()
                    val ms = parts[1].toInt()
                    s * 1_000L + ms
                }

                else -> ts.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
