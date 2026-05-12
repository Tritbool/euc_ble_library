package com.euc.ble.analysis

import com.euc.ble.SlowTest
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt

/**
 * Analyses BLE message frequency from WheelLog RAW CSV files.
 *
 * For each file it reads all timestamps, computes inter-arrival times (dt in ms)
 * and prints a summary:
 *   - packet count
 *   - recording duration (ms)
 *   - avg dt, median dt, p95 dt
 *   - avg msgs/sec, median msgs/sec, p95 msgs/sec (best-case based on p5 dt)
 *
 * Assertions are intentionally minimal (count > 0, duration > 0) so the tests
 * remain stable across different data sets.
 */
@SlowTest
class BleFrequencyAnalysisTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Parses a WheelLog timestamp string to milliseconds.
     *  Supports HH:MM:SS.mmm, MM:SS.mmm, SS.mmm and raw long values. */
    private fun parseTimestampToMs(ts: String): Long {
        val cleaned = ts.trim()
        val parts = cleaned.split(':', '.')
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
                else -> cleaned.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /** Loads all valid timestamps from a WheelLog RAW CSV resource. */
    private fun loadTimestamps(resourcePath: String): List<Long> {
        val stream = javaClass.getResourceAsStream(resourcePath) ?: return emptyList()
        val timestamps = mutableListOf<Long>()
        BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                val idx = line.indexOf(',')
                if (idx <= 0) return@forEach
                try {
                    timestamps.add(parseTimestampToMs(line.substring(0, idx)))
                } catch (_: Exception) { /* skip malformed */ }
            }
        }
        return timestamps
    }

    data class FrequencyStats(
        val fileName: String,
        val count: Int,
        val durationMs: Long,
        val avgDtMs: Double,
        val medianDtMs: Double,
        val p95DtMs: Double,
        val avgMsgPerSec: Double,
        val medianMsgPerSec: Double,
        val p95MsgPerSec: Double   // based on p5 dt (best-case hi-freq bound)
    )

    /** Computes BLE frequency statistics from a list of monotonically-parsed timestamps. */
    private fun computeStats(fileName: String, timestamps: List<Long>): FrequencyStats? {
        if (timestamps.size < 2) return null

        // Build list of positive inter-arrival times, skipping non-monotonic gaps
        val dts = mutableListOf<Long>()
        for (i in 1 until timestamps.size) {
            val dt = timestamps[i] - timestamps[i - 1]
            if (dt > 0) dts.add(dt)
        }
        if (dts.isEmpty()) return null

        val sorted = dts.sorted()
        val count = timestamps.size
        val durationMs = timestamps.last() - timestamps.first()

        fun percentile(pct: Double): Double {
            val idx = ((pct / 100.0) * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
            return sorted[idx].toDouble()
        }

        val avgDt = dts.average()
        val medianDt = percentile(50.0)
        val p95Dt = percentile(95.0)
        val p5Dt = percentile(5.0)

        val avgMps = if (durationMs > 0) (count - 1) * 1000.0 / durationMs else 0.0
        val medianMps = if (medianDt > 0) 1000.0 / medianDt else 0.0
        val p95Mps = if (p5Dt > 0) 1000.0 / p5Dt else 0.0

        return FrequencyStats(
            fileName = fileName,
            count = count,
            durationMs = durationMs,
            avgDtMs = avgDt,
            medianDtMs = medianDt,
            p95DtMs = p95Dt,
            avgMsgPerSec = avgMps,
            medianMsgPerSec = medianMps,
            p95MsgPerSec = p95Mps
        )
    }

    private fun printStats(manufacturer: String, stats: FrequencyStats) {
        println(
            "  [%-10s] %-40s  count=%5d  dur=%6dms  " +
            "avgDt=%5.1fms  medDt=%5.1fms  p95Dt=%5.1fms  " +
            "avg=%.1f msg/s  med=%.1f msg/s  p95=%.1f msg/s".format(
                manufacturer,
                stats.fileName,
                stats.count,
                stats.durationMs,
                stats.avgDtMs,
                stats.medianDtMs,
                stats.p95DtMs,
                stats.avgMsgPerSec,
                stats.medianMsgPerSec,
                stats.p95MsgPerSec
            )
        )
    }

    /**
     * Lists CSV filenames available in a classpath resource directory.
     * Falls back to the provided [fallback] list when directory listing is not
     * supported by the class-loader (e.g. inside a JAR).
     */
    private fun listCsvResources(resourceDir: String, fallback: List<String>): List<String> {
        return try {
            val url = javaClass.getResource(resourceDir) ?: return fallback
            java.io.File(url.toURI())
                .listFiles { f -> f.name.endsWith(".csv") }
                ?.map { it.name }
                ?.sorted()
                ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    /**
     * Runs the frequency analysis for a single manufacturer.
     * Prints per-file results and a grand summary, then asserts basic sanity.
     */
    private fun analyseManufacturer(manufacturer: String, resourceDir: String, csvFiles: List<String>) {
        println("\n=== BLE Frequency Analysis : $manufacturer ===")
        val allResults = mutableListOf<FrequencyStats>()

        for (fileName in csvFiles) {
            val path = "$resourceDir$fileName"
            val timestamps = loadTimestamps(path)
            val stats = computeStats(fileName, timestamps)
            if (stats != null) {
                printStats(manufacturer, stats)
                allResults.add(stats)
            } else {
                println("  [%-10s] %-40s  <skipped: not enough data>".format(manufacturer, fileName))
            }
        }

        if (allResults.isNotEmpty()) {
            val totalCount = allResults.sumOf { it.count }
            val avgFreq = allResults.map { it.avgMsgPerSec }.average()
            val medFreq = allResults.map { it.medianMsgPerSec }.average()
            println(
                "  --- $manufacturer SUMMARY: ${allResults.size} files, " +
                "totalPackets=$totalCount, avgFreq=%.1f msg/s, medFreq=%.1f msg/s".format(avgFreq, medFreq)
            )

            // Minimal sanity assertions
            assertTrue(
                "$manufacturer: expected at least one file with packets",
                allResults.any { it.count > 0 }
            )
            assertTrue(
                "$manufacturer: expected at least one file with positive duration",
                allResults.any { it.durationMs > 0 }
            )
        }
    }

    // -------------------------------------------------------------------------
    // Tests – one per manufacturer
    // -------------------------------------------------------------------------

    @Test
    fun analyseBleFrequencyGotway() {
        val dir = "/ble_frames/gotway/RAW_WHEELLOG/"
        val fallback = listOf(
            "RAW_2023_11_24_18_43_22.csv",
            "RAW_2023_11_25_15_11_39.csv",
            "RAW_2023_12_06_15_47_00.csv",
            "RAW_2023_12_07_17_56_03.csv",
            "RAW_2023_12_07_17_57_39.csv",
            "RAW_2023_12_15_12_02_26.csv",
            "RAW_2023_12_18_19_57_16.csv",
            "RAW_2023_12_20_14_42_45.csv",
            "RAW_2023_12_20_14_56_50.csv",
            "RAW_2023_12_25_12_00_44.csv",
            "RAW_2023_12_26_16_43_51.csv",
            "RAW_2024_06_21_09_29_27.csv",
            "RAW_2024_07_14_11_59_36.csv",
            "RAW_2024_09_17_12_42_35.csv",
            "RAW_2024_09_17_14_23_32.csv",
            "RAW_2024_09_17_17_16_52.csv",
            "RAW_2024_10_04_16_56_06.csv",
            "RAW_2024_10_11_11_30_21.csv",
            "RAW_2024_10_11_15_06_09.csv",
            "RAW_2024_10_11_15_58_11.csv",
            "RAW_2024_10_11_16_49_43.csv",
            "RAW_2024_10_30_10_03_01.csv",
            "RAW_2024_10_30_11_47_06.csv",
            "RAW_2024_10_30_12_58_32.csv",
            "RAW_2024_10_30_15_04_35.csv",
            "RAW_2024_10_30_16_17_22.csv"
        )
        val files = listCsvResources(dir, fallback)
        analyseManufacturer("Gotway", dir, files)
    }

    @Test
    fun analyseBleFrequencyKingsong() {
        val dir = "/ble_frames/kingsong/RAW_WHEELLOG/"
        val fallback = listOf(
            "RAW_2023_08_19_18_34_07.csv",
            "RAW_2023_08_25_15_02_03.csv",
            "RAW_2023_08_25_17_32_16.csv",
            "RAW_2023_08_25_17_32_47.csv",
            "RAW_2023_08_30_19_15_30.csv"
        )
        val files = listCsvResources(dir, fallback)
        analyseManufacturer("Kingsong", dir, files)
    }

    @Test
    fun analyseBleFrequencyNinebot() {
        val dir = "/ble_frames/ninebot/RAW_WHEELLOG/"
        val fallback = listOf(
            "RAW_2023_08_21_11_24_37.csv",
            "RAW_2023_09_07_11_18_45.csv",
            "RAW_2023_09_07_11_29_37.csv",
            "RAW_2023_09_09_11_02_51.csv"
        )
        val files = listCsvResources(dir, fallback)
        analyseManufacturer("Ninebot", dir, files)
    }

    @Test
    fun analyseBleFrequencyInMotion() {
        val dir = "/ble_frames/inmotion/RAW_WHEELLOG/"
        val fallback = listOf(
            "RAW_2026_03_11_08_20_23.csv",
            "RAW_2026_03_11_12_16_00.csv",
            "RAW_inmotion_V5F.csv",
            "RAW_inmotion_V8S.csv",
            "RAW_inmotion_alerts.csv"
        )
        val files = listCsvResources(dir, fallback)
        analyseManufacturer("InMotion", dir, files)
    }

    @Test
    fun analyseBleFrequencyLeaperkim() {
        val dir = "/ble_frames/leaperkim/RAW_WHEELLOG/"
        val fallback = listOf(
            "RAW_2026_04_30_07_04_10.csv",
            "RAW_2026_04_30_20_08_09.csv"
        )
        val files = listCsvResources(dir, fallback)
        analyseManufacturer("Leaperkim", dir, files)
    }

    @Test
    fun analyseBleFrequencyNosfet() {
        val dir = "/ble_frames/nosfet/RAW_WHEELLOG/"
        val fallback = listOf(
            "RAW_2026_05_08_18_55_45.csv"
        )
        val files = listCsvResources(dir, fallback)
        analyseManufacturer("Nosfet", dir, files)
    }
}
