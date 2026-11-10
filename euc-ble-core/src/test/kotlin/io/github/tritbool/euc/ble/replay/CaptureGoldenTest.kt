package io.github.tritbool.euc.ble.replay

import io.github.tritbool.euc.ble.core.*
import io.github.tritbool.euc.ble.protocols.*
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import io.github.tritbool.euc.ble.test.BtsnoopCapture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Golden outputs are compressed JSON Lines, not Kotlin objects or platform hashes.
 * The manifest accounts for every corpus file, including documentation and decoded-only CSVs.
 */
class CaptureGoldenTest {
    private val clock = object : EucClock {
        override fun currentTimeMillis() = 100_000L
        override fun localDateTime() = EucDateTime(2026, 1, 1, 0, 0, 0)
    }

    @Test
    fun allCapturesMatchSynchronousGoldenOutputs() {
        val root = File(System.getProperty("replay.captureRoot"))
        val goldenRoot = File(System.getProperty("replay.goldenRoot"))
        val update = System.getProperty("replay.updateGoldens") == "true"
        val manifest = mutableListOf("path\tkind\tsha256\tinputs\toutputs")
        val goldenPaths = mutableSetOf<String>()
        var replayed = 0
        root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex()
            val decoded = file.bufferedReader().use { it.readLine()?.startsWith("date,time,") == true }
            val raw = file.extension == "csv" && "/RAW_WHEELLOG/" in "/$path" && !decoded
            val btsnoop = file.extension == "hex"
            val engine = if (raw) engine(path.substringBefore('/')) else if (btsnoop) engine("inmotion") else null
            if (engine == null) {
                manifest += "$path\t${if (file.extension == "csv") "decoded-or-example" else "documentation"}\t$digest\t0\t0"
                return@forEach
            }
            val golden = File(goldenRoot, "$path.jsonl.gz")
            goldenPaths += "$path.jsonl.gz"
            var inputCount = 0
            var outputCount = 0
            var lastBms: String? = null
            fun replay(emit: (String) -> Unit) {
                fun input(index: Int, bytes: ByteArray) {
                    inputCount++
                    engine.onNotification(bytes)
                    while (true) {
                        val output = engine.pollOutput() ?: break
                        if (output is ProtocolOutput.DebugLog) continue
                        emit("""{"line":$index,"event":${json(output)}}""")
                        outputCount++
                    }
                    val bms = engine.getBMSData()?.let(::json)
                    if (bms != lastBms) {
                        emit("""{"line":$index,"event":{"type":"BmsSnapshot","packs":${bms ?: "null"}}}""")
                        outputCount++
                        lastBms = bms
                    }
                }
                if (btsnoop) {
                    BtsnoopCapture.notifications(file).forEach { input(it.record, it.bytes) }
                    return
                }
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (line.isBlank() || line.startsWith("#") || line.startsWith("timestamp")) return@forEachIndexed
                        val columns = line.split(',', limit = 3)
                        require(columns.size >= 2) { "$path:${index + 1}: missing raw bytes" }
                        val hex = columns[1].trim().removeSurrounding("\"").replace(" ", "")
                        require(hex.isNotEmpty() && hex.length % 2 == 0 &&
                            hex.all { it in "0123456789abcdefABCDEF" }) {
                            "$path:${index + 1}: invalid raw bytes"
                        }
                        input(index + 1, ByteUtils.hexToBytes(hex))
                    }
                }
            }
            if (update) {
                golden.parentFile.mkdirs()
                GZIPOutputStream(golden.outputStream()).bufferedWriter().use { writer ->
                    replay { writer.appendLine(it) }
                }
            } else {
                assertTrue(golden.isFile, "Missing golden: $path (regenerate explicitly with -PupdateReplayGoldens)")
                GZIPInputStream(golden.inputStream()).bufferedReader().use { expected ->
                    replay { actual ->
                        assertEquals(expected.readLine(), actual, "$path output ${outputCount + 1}")
                    }
                    assertNull(expected.readLine(), "$path: missing outputs")
                }
            }
            assertTrue(inputCount > 0, "$path: no raw inputs")
            manifest += "$path\t${if (btsnoop) "btsnoop" else "raw"}\t$digest\t$inputCount\t$outputCount"
            replayed++
        }
        assertTrue(replayed > 40, "Capture discovery unexpectedly replayed only $replayed files")
        val manifestFile = File(goldenRoot, "manifest.tsv")
        val manifestText = manifest.joinToString("\n", postfix = "\n")
        if (update) manifestFile.writeText(manifestText)
        else assertEquals(manifestFile.readText(), manifestText, "Corpus files or output counts changed")
        assertEquals(goldenPaths, goldenRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".jsonl.gz") }
            .map { it.relativeTo(goldenRoot).invariantSeparatorsPath }.toSet(), "Unreferenced or missing goldens")
    }

    private fun engine(brand: String): SyncEucProtocol = when (brand) {
        "gotway" -> GotwaySyncProtocol(clock)
        "extreme_bull" -> ExtremeBullSyncProtocol(clock)
        "kingsong" -> KingsongSyncProtocol(clock, EucRandom { 0 })
        "inmotion" -> InMotionSyncProtocol(clock)
        "leaperkim" -> LeaperkimSyncProtocol(clock)
        "nosfet" -> NosfetSyncProtocol(clock)
        "ninebot" -> NinebotSyncProtocol(clock)
        else -> error("Unknown capture brand: $brand")
    }

    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + buildString {
            value.forEach {
                when (it) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (it.code < 32) append("\\u${it.code.toString(16).padStart(4, '0')}") else append(it)
                }
            }
        } + "\""
        is Boolean, is Byte, is Short, is Int, is Long -> value.toString()
        is Double -> { require(value.isFinite()); value.toString() }
        is Float -> { require(value.isFinite()); value.toString() }
        is Enum<*> -> json(value.name)
        is ByteArray -> json(value.hex())
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> {
            val fields = value.javaClass.declaredFields.filter {
                !it.isSynthetic && !Modifier.isStatic(it.modifiers)
            }.sortedBy { it.name }
            val properties = fields.map {
                it.isAccessible = true
                json(it.name) + ":" + json(it.get(value))
            }
            (listOf("\"type\":" + json(value.javaClass.simpleName)) + properties).joinToString(",", "{", "}")
        }
    }

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
