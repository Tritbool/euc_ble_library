import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.protocols.KingsongProtocol
import java.io.BufferedReader
import java.io.File

/**
 * Simple test runner to validate WheelLog integration without requiring full Gradle setup
 */
fun main() {
    println("🚀 WheelLog Integration Test Runner")
    println("===================================")
    
    // Test 1: Basic file reading
    testFileReading()
    
    // Test 2: Kingsong frame parsing
    testKingsongFrameParsing()
    
    // Test 3: Kingsong protocol decoding
    testKingsongProtocolDecoding()
    
    println("\n✅ All manual tests completed!")
}

fun testFileReading() {
    println("\n📁 Test 1: File Reading")
    println("---------------------")
    
    val testFile = File("src/test/resources/ble_frames/kingsong/RAW_WHEELLOG/RAW_2023_08_19_18_34_07.csv")
    
    if (!testFile.exists()) {
        println("❌ Test file not found: ${testFile.absolutePath}")
        return
    }
    
    println("✅ Test file found: ${testFile.name}")
    
    val lines = testFile.readLines()
    println("📊 File contains ${lines.size} lines")
    
    if (lines.isNotEmpty()) {
        println("📄 First line: ${lines[0]}")
        println("📄 Last line: ${lines.last()}")
    }
}

fun testKingsongFrameParsing() {
    println("\n🔍 Test 2: Kingsong Frame Parsing")
    println("-------------------------------")
    
    val testFile = File("src/test/resources/ble_frames/kingsong/RAW_WHEELLOG/RAW_2023_08_19_18_34_07.csv")
    
    if (!testFile.exists()) {
        println("❌ Test file not found")
        return
    }
    
    val reader = BufferedReader(testFile.reader())
    var lineCount = 0
    var validFrames = 0
    var invalidFrames = 0
    
    var line: String?
    while (reader.readLine().also { line = it } != null) {
        lineCount++
        
        try {
            val parts = line?.split(",") ?: continue
            if (parts.size < 2) continue
            
            val hexData = parts[1].trim()
            val bleData = ByteUtils.hexToBytes(hexData)
            
            // Check for Kingsong header (AA 55)
            if (bleData.size >= 2) {
                val headerValid = bleData[0].toInt() and 0xFF == 0xAA &&
                                 bleData[1].toInt() and 0xFF == 0x55
                
                if (headerValid) {
                    validFrames++
                } else {
                    invalidFrames++
                }
            }
            
        } catch (e: Exception) {
            invalidFrames++
        }
    }
    
    println("📊 Parsed $lineCount lines")
    println("✅ Valid Kingsong frames: $validFrames")
    println("❌ Invalid frames: $invalidFrames")
    println("📈 Success rate: ${(validFrames * 100.0 / lineCount).toInt()}%")
}

fun testKingsongProtocolDecoding() {
    println("\n🧪 Test 3: Kingsong Protocol Decoding")
    println("-----------------------------------")
    
    val protocol = KingsongProtocol()
    val testFile = File("src/test/resources/ble_frames/kingsong/RAW_WHEELLOG/RAW_2023_08_19_18_34_07.csv")
    
    if (!testFile.exists()) {
        println("❌ Test file not found")
        return
    }
    
    val reader = BufferedReader(testFile.reader())
    var lineCount = 0
    var decodedFrames = 0
    var failedDecodes = 0
    
    var line: String?
    while (reader.readLine().also { line = it } != null && lineCount < 50) {
        lineCount++
        
        try {
            val parts = line?.split(",") ?: continue
            if (parts.size < 2) continue
            
            val hexData = parts[1].trim()
            val bleData = ByteUtils.hexToBytes(hexData)
            
            val decoded = protocol.decode(bleData)
            if (decoded != null) {
                decodedFrames++
                
                // Print first few successful decodes
                if (decodedFrames <= 3) {
                    println("📊 Frame $decodedFrames: ${describeFrame(decoded)}")
                }
            } else {
                failedDecodes++
            }
            
        } catch (e: Exception) {
            failedDecodes++
        }
    }
    
    println("📊 Processed $lineCount frames")
    println("✅ Successfully decoded: $decodedFrames")
    println("❌ Failed to decode: $failedDecodes")
    println("📈 Success rate: ${(decodedFrames * 100.0 / lineCount).toInt()}%")
}

fun describeFrame(data: io.github.tritbool.euc.ble.models.EUCData): String {
    return "${data.speed.toInt()} km/h, ${data.voltage.toInt()} V, ${data.batteryLevel}% battery, " +
           "${data.current.toInt()} A, ${data.temperature.toInt()}°C"
}
