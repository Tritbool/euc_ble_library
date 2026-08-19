package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.AndroidLogger
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Single InMotion protocol entrypoint with auto-detected internal dialects (legacy V1 and V2).
 */
class InMotionProtocol(private val logger: Logger = AndroidLogger()) : EUCProtocol {

    companion object {

        private const val TAG = "InMotionProtocol"
        private val HEADER = BLEConstants.INMOTION_FRAME_HEADER
        private val LEGACY_TAIL = BLEConstants.INMOTION_LEGACY_TAIL
        private const val FLAG_INITIAL = 0x11
        private const val FLAG_DEFAULT = 0x14
        private const val FLAG_EXTENDED = 0x16

        private const val COMMAND_MAIN_INFO = 0x02
        private const val COMMAND_REAL_TIME_INFO = 0x04
        private const val COMMAND_BATTERY_INFO = 0x05
        private const val COMMAND_TOTAL_STATS = 0x11
        private const val COMMAND_CONTROL = 0x60

        private const val MIN_FRAME_SIZE = 5
        private const val MAX_LEN = 240

        /** InMotion V2 escape marker byte. Any 0xAA or 0xA5 in the payload is
         *  transmitted as 0xA5 followed by the original byte. */
        private const val ESCAPE_MARKER = 0xA5

        private const val LEGACY_CURRENT_OFFSET = 39
        private const val LEGACY_VOLTAGE_OFFSET = 43
        private const val LEGACY_TEMP_OFFSET = 51
        private const val LEGACY_MOTOR_TEMP_OFFSET = 53
        private const val LEGACY_TOTAL_DISTANCE_OFFSET = 63
        private const val LEGACY_TRIP_DISTANCE_OFFSET = 83
        private const val LEGACY_SPEED_OFFSET = 95
        private const val LEGACY_RIDE_TIME_OFFSET = 103
        private const val LEGACY_BATTERY_OFFSET = 154

        private const val LEGACY_SPEED_DIVISOR = 820.0
        private const val LEGACY_SPEED_MIN = -80.0
        private const val LEGACY_SPEED_MAX = 80.0
        private const val LEGACY_BATTERY_BASE_VOLTAGE = 55.0
        private const val LEGACY_BATTERY_VOLTAGE_RANGE = 30.0

        /** P6 motor torque constant (N·m per amp of phase current), recovered by
         *  correlating the InMotion app's Phase Current vs Motor Torque readings
         *  across a labelled ride. phase_A = torque_Nm / this.
         *  (Source: eucplanet commit 32385baa, verified over a 75x torque range.) */
        private const val P6_KT_NM_PER_A = 0.586
        private const val P6_STATS_QUERY_INTERVAL_MS = 4_000L

        /** Minimum realtime payload size for V14/P6/V13/V11 (78 bytes). */
        private const val V2_REALTIME_MIN_SIZE = 78

        /** Minimum realtime payload size for V12 HS/HT/PRO/S (56 bytes). The V12
         *  uses a more compact layout: speed/torque/pwm shift earlier, a single
         *  battery field at offset 24, and state/light bytes at 54/55. */
        private const val V12_REALTIME_MIN_SIZE = 56

        private val V12_MODEL_NAMES = setOf(
            "InMotion V12 HS",
            "InMotion V12 HT",
            "InMotion V12 PRO",
            "InMotion V12S"
        )

        private val V14_BMS_PACK_ADDRESSES = listOf(0x24, 0x25, 0x26, 0x27)

        // --- InMotion V1 wire-format constants ---
        // V1 frames: AA AA <escaped 16-byte CAN frame> <escaped checksum> 55 55
        // CAN ID written little-endian at bytes 0..3 of the 16-byte prefix.
        // checksum = sum(all 16 CAN bytes) mod 256, also escaped.
        // Source: eucplanet InMotionV1Protocol.kt + InMotionV1Commands.kt

        private const val V1_HEADER: Byte = 0xAA.toByte()
        private const val V1_TRAILER: Byte = 0x55.toByte()
        private const val V1_ESCAPE: Byte = 0xA5.toByte()

        // Metadata bytes fixed for all phone→wheel frames.
        private const val V1_LEN_NORMAL: Byte = 0x08
        private const val V1_CHANNEL_PHONE: Byte = 0x05
        private const val V1_FORMAT_STANDARD: Byte = 0x00
        private const val V1_TYPE_DATA: Byte = 0x00

        // CAN IDs used by the protocol.
        private const val V1_CAN_FAST_INFO  = 0x0F550113
        private const val V1_CAN_HEADLIGHT  = 0x0F55010D
        private const val V1_CAN_REMOTE_CTRL = 0x0F550116
        private const val V1_CAN_PIN        = 0x0F550307

        private const val V1_DEFAULT_PIN = "000000"
        private const val V1_FACTORY_PASSWORD = "INMOTI"
    }


    override val manufacturer: String = "InMotion"
    override val supportedCommandTypes: Set<CommandType> = setOf(
        CommandType.LIGHT_ON,
        CommandType.LIGHT_OFF,
        CommandType.LIGHT_BRIGHTNESS,
        CommandType.BEEP,
        CommandType.LOCK,
        CommandType.UNLOCK,
        CommandType.POWER_OFF,
        CommandType.REQUEST_SERIAL,
        CommandType.REQUEST_FIRMWARE,
        CommandType.REQUEST_BATTERY_INFO,
        CommandType.CUSTOM
    )

    override fun getServiceUUID(): UUID {
        return if (lastDetectedDialect == Dialect.V2) {
            UUID.fromString(BLEConstants.INMOTION_V2_SERVICE_UUID)
        } else {
            UUID.fromString(BLEConstants.INMOTION_SERVICE_UUID)
        }
    }

    override fun getDataCharacteristicUUID(): UUID {
        return if (lastDetectedDialect == Dialect.V2) {
            UUID.fromString(BLEConstants.INMOTION_V2_READ_CHARACTERISTIC)
        } else {
            UUID.fromString(BLEConstants.INMOTION_READ_CHARACTERISTIC)
        }
    }

    override fun getWriteCharacteristicUUID(): UUID {
        return if (lastDetectedDialect == Dialect.V2) {
            UUID.fromString(BLEConstants.INMOTION_V2_WRITE_CHARACTERISTIC)
        } else {
            UUID.fromString(BLEConstants.INMOTION_WRITE_CHARACTERISTIC)
        }
    }

    /**
     * InMotion exposes two possible data characteristics depending on the dialect detected
     * at runtime. Both must be enabled for notifications at connection time so that the
     * protocol can determine which dialect is in use from the incoming frames:
     * - V1 (legacy): [BLEConstants.INMOTION_READ_CHARACTERISTIC] (`0000ffe4`)
     * - V2 (modern): [BLEConstants.INMOTION_V2_READ_CHARACTERISTIC] (`6e400003`)
     */
    override fun getCandidateDataCharacteristicUUIDs(): List<UUID> = listOf(
        UUID.fromString(BLEConstants.INMOTION_READ_CHARACTERISTIC),
        UUID.fromString(BLEConstants.INMOTION_V2_READ_CHARACTERISTIC)
    )

    private val _channel = Channel<EUCData>(capacity = Channel.UNLIMITED)
    override val dataFlow: Flow<EUCData> = _channel.receiveAsFlow()
    private val _writeChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    override val writeFlow: Flow<ByteArray> = _writeChannel.receiveAsFlow()

    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val rawFrameFlow: Flow<ByteArray> = _rawFrameFlow.asSharedFlow()


    private val parseLock = Any()
    private val v2Buffer = ArrayList<Byte>()
    private val legacyBuffer = ArrayList<Byte>()

    private enum class Dialect { UNKNOWN, LEGACY_V1, V2 }

    @Volatile
    private var lastDetectedDialect: Dialect = Dialect.UNKNOWN

    @Volatile
    private var modelName: String = "InMotion"

    @Volatile
    private var serialNumber: String? = null

    @Volatile
    private var firmwareVersion: String? = null

    @Volatile
    private var totalDistanceKm: Double? = null

    @Volatile
    private var v2SessionStartTimestampMs: Long? = null

    @Volatile
    private var hasSeenV2MainInfo: Boolean = false

    @Volatile
    private var hasSeenV2Realtime: Boolean = false

    @Volatile
    private var hasSeenLegacyRealtime: Boolean = false

    @Volatile
    private var lastKnownBmsSnapshot = InMotionBmsSnapshot()

    @Volatile
    private var totalRideTimeSeconds: Long? = null

    @Volatile
    private var totalPowerOnTimeSeconds: Long? = null

    @Volatile
    private var lastP6StatsQueryAtMs: Long = 0L

    private data class InMotionBmsSnapshot(
        val voltage: Double? = null,
        val current: Double? = null,
        val temperatures: List<Double>? = null,
        val packVoltages: List<Double>? = null,
        val cellVoltages: List<Double>? = null,
        val packCellVoltages: Map<Int, List<Double>> = emptyMap()
    )

    override fun decode(data: ByteArray): EUCData? {
        if (data.isEmpty()) return null
        _rawFrameFlow.tryEmit(data.clone())
        var lastDecoded: EUCData? = null

        val v2Frames = extractV2Frames(data)
        for (frame in v2Frames) {
            val decoded = parseV2Frame(frame) ?: continue
            lastDecoded = decoded
            _channel.trySend(decoded)
        }

        val legacyFrames = extractLegacyFrames(data)
        for (frame in legacyFrames) {
            val decoded = parseLegacyFrame(frame) ?: continue
            lastDecoded = decoded
            _channel.trySend(decoded)
        }
        return lastDecoded
    }

    fun setDialect(version: Int) {
        android.util.Log.e("InMotionProtocol", "setDialect called version=$version")
        when (version) {
            0, 1, 2 -> {
                logger.info(TAG,"DIALECT SET TO ${Dialect.entries[version]} ")
                lastDetectedDialect = Dialect.entries[version]
            }

            else -> {}
        }

    }

    private fun extractV2Frames(chunk: ByteArray): List<ByteArray> {
        if (!isLikelyV2Chunk(chunk) && v2Buffer.isEmpty()) return emptyList()
        synchronized(parseLock) {
            for (b in chunk) v2Buffer.add(b)
            val out = mutableListOf<ByteArray>()

            while (true) {
                val headerIndex = findHeader(v2Buffer)
                if (headerIndex < 0) {
                    if (v2Buffer.size > 1) {
                        val keep = v2Buffer.last()
                        v2Buffer.clear()
                        v2Buffer.add(keep)
                    }
                    break
                }

                if (headerIndex > 0) {
                    repeat(headerIndex) { v2Buffer.removeAt(0) }
                }

                if (v2Buffer.size < MIN_FRAME_SIZE) break

                // The InMotion V2 wire format escapes 0xAA→{0xA5,0xAA} and 0xA5→{0xA5,0xA5}
                // inside the payload. The AA AA header bytes and the trailing checksum byte
                // are NOT escaped. We must unescape the payload to:
                //   (a) correctly determine the frame boundary (escapes inflate raw byte count)
                //   (b) verify the checksum, which is computed over the UNESCAPED bytes
                //   (c) parse field offsets correctly

                // Step 1: unescape the first two payload bytes (FLAG and LEN) to learn the
                // unescaped payload length. FLAG values (0x11/0x14/0x16) are never escape
                // markers, so this is safe.
                val flagLen = unescapeNBytes(v2Buffer, startIdx = 2, count = 2) ?: break
                val (headerBytes, _) = flagLen
                val flags = headerBytes[0].toInt() and 0xFF
                val len = headerBytes[1].toInt() and 0xFF

                if (flags != FLAG_INITIAL && flags != FLAG_DEFAULT && flags != FLAG_EXTENDED) {
                    v2Buffer.removeAt(0)
                    continue
                }
                if (len !in 1..MAX_LEN) {
                    v2Buffer.removeAt(0)
                    continue
                }

                // Step 2: find the index of the checksum byte by consuming exactly `len`
                // unescaped bytes (FLAG and LEN already counted as 2; total = len+2 from pos 2).
                // We already consumed 2 above, so we need `len` more from where the FLAG/LEN
                // scan left off. But it's simpler to scan from scratch for all len+2 bytes.
                val checksumIdx = findChecksumIndex(v2Buffer, startIdx = 2, targetUnescaped = len + 2)
                if (checksumIdx < 0) break  // frame incomplete, wait for more data

                // Step 3: verify the checksum over the unescaped payload bytes
                if (!isValidChecksumEscaped(v2Buffer, fromIdx = 2, toIdx = checksumIdx)) {
                    v2Buffer.removeAt(0)
                    continue
                }

                // Step 4: build an unescaped frame for parsing:
                //   [AA AA] [unescaped FLAG LEN CMD DATA...] [checksum]
                val unescapedPayload = unescapeRange(v2Buffer, fromIdx = 2, toIdx = checksumIdx)
                val unescapedFrame = ByteArray(2 + unescapedPayload.size + 1)
                unescapedFrame[0] = HEADER[0]
                unescapedFrame[1] = HEADER[1]
                unescapedPayload.copyInto(unescapedFrame, 2)
                unescapedFrame[unescapedFrame.size - 1] = v2Buffer[checksumIdx]

                out.add(unescapedFrame)
                repeat(checksumIdx + 1) { v2Buffer.removeAt(0) }
            }
            return out
        }
    }

    /**
     * Unescape exactly [count] bytes starting at [startIdx] in [buffer], handling
     * 0xA5 escape sequences (0xA5 XX → real byte XX).
     *
     * @return Pair(unescaped bytes, raw bytes consumed) or null if buffer has insufficient data.
     */
    private fun unescapeNBytes(buffer: List<Byte>, startIdx: Int, count: Int): Pair<ByteArray, Int>? {
        val result = ByteArray(count)
        var rawIdx = startIdx
        var n = 0
        while (rawIdx < buffer.size && n < count) {
            val b = buffer[rawIdx].toInt() and 0xFF
            if (b == ESCAPE_MARKER) {
                if (rawIdx + 1 >= buffer.size) return null  // incomplete escape
                result[n] = buffer[rawIdx + 1]
                rawIdx += 2
            } else {
                result[n] = buffer[rawIdx]
                rawIdx++
            }
            n++
        }
        if (n < count) return null
        return Pair(result, rawIdx - startIdx)
    }

    /**
     * Scan [buffer] from [startIdx], consuming escape sequences, until [targetUnescaped]
     * unescaped bytes have been processed. Returns the index of the next byte after the
     * last unescaped byte (i.e. the checksum byte position), or -1 if insufficient data.
     */
    private fun findChecksumIndex(buffer: List<Byte>, startIdx: Int, targetUnescaped: Int): Int {
        var rawIdx = startIdx
        var n = 0
        while (rawIdx < buffer.size && n < targetUnescaped) {
            val b = buffer[rawIdx].toInt() and 0xFF
            if (b == ESCAPE_MARKER) {
                if (rawIdx + 1 >= buffer.size) return -1  // incomplete escape
                rawIdx += 2
            } else {
                rawIdx++
            }
            n++
        }
        if (n < targetUnescaped) return -1
        if (rawIdx >= buffer.size) return -1  // checksum byte not yet received
        return rawIdx
    }

    /**
     * Verify the InMotion V2 checksum: XOR of all unescaped bytes in [buffer] from
     * [fromIdx] to [toIdx]-1 (exclusive), compared to [buffer][toIdx].
     */
    private fun isValidChecksumEscaped(buffer: List<Byte>, fromIdx: Int, toIdx: Int): Boolean {
        var xor = 0
        var rawIdx = fromIdx
        while (rawIdx < toIdx) {
            val b = buffer[rawIdx].toInt() and 0xFF
            if (b == ESCAPE_MARKER && rawIdx + 1 < toIdx) {
                xor = xor xor (buffer[rawIdx + 1].toInt() and 0xFF)
                rawIdx += 2
            } else {
                xor = xor xor b
                rawIdx++
            }
        }
        return xor == (buffer[toIdx].toInt() and 0xFF)
    }

    /**
     * Unescape all bytes in [buffer] from [fromIdx] to [toIdx]-1 (exclusive),
     * collapsing 0xA5 escape sequences into their real byte values.
     */
    private fun unescapeRange(buffer: List<Byte>, fromIdx: Int, toIdx: Int): ByteArray {
        val result = mutableListOf<Byte>()
        var rawIdx = fromIdx
        while (rawIdx < toIdx) {
            val b = buffer[rawIdx].toInt() and 0xFF
            if (b == ESCAPE_MARKER && rawIdx + 1 < toIdx) {
                result.add(buffer[rawIdx + 1])
                rawIdx += 2
            } else {
                result.add(buffer[rawIdx])
                rawIdx++
            }
        }
        return result.toByteArray()
    }

    private fun extractLegacyFrames(chunk: ByteArray): List<ByteArray> {
        if (!isLikelyLegacyChunk(chunk) && legacyBuffer.isEmpty()) return emptyList()
        synchronized(parseLock) {
            for (b in chunk) legacyBuffer.add(b)
            val out = mutableListOf<ByteArray>()

            while (true) {
                val headerIndex = findHeader(legacyBuffer)
                if (headerIndex < 0) {
                    if (legacyBuffer.size > 1) {
                        val keep = legacyBuffer.last()
                        legacyBuffer.clear()
                        legacyBuffer.add(keep)
                    }
                    break
                }
                if (headerIndex > 0) {
                    repeat(headerIndex) { legacyBuffer.removeAt(0) }
                }

                val frameEndIndex = findTail(legacyBuffer)
                if (frameEndIndex < 0) break

                val frameSize = frameEndIndex + LEGACY_TAIL.size
                val frame = ByteArray(frameSize) { i -> legacyBuffer[i] }
                out.add(frame)
                repeat(frameSize) { legacyBuffer.removeAt(0) }
            }
            return out
        }
    }

    private fun findHeader(source: List<Byte>): Int {
        if (source.size < 2) return -1
        for (i in 0 until source.size - 1) {
            if (source[i] == HEADER[0] && source[i + 1] == HEADER[1]) return i
        }
        return -1
    }

    private fun findTail(source: List<Byte>): Int {
        if (source.size < 4) return -1
        for (i in 2 until source.size - 1) {
            if (source[i] == LEGACY_TAIL[0] && source[i + 1] == LEGACY_TAIL[1]) return i
        }
        return -1
    }

    private fun isLikelyV2Chunk(chunk: ByteArray): Boolean {
        if (chunk.size < MIN_FRAME_SIZE) return false
        if (chunk[0] != HEADER[0] || chunk[1] != HEADER[1]) return false
        val flags = chunk[2].toInt() and 0xFF
        if (flags != FLAG_INITIAL && flags != FLAG_DEFAULT && flags != FLAG_EXTENDED) return false
        val len = chunk[3].toInt() and 0xFF
        return len in 1..MAX_LEN
    }

    private fun isLikelyLegacyChunk(chunk: ByteArray): Boolean {
        if (chunk.size >= 2 && chunk[0] == HEADER[0] && chunk[1] == HEADER[1]) {
            if (chunk.size >= 4) {
                val flags = chunk[2].toInt() and 0xFF
                val len = chunk[3].toInt() and 0xFF
                if ((flags == FLAG_INITIAL || flags == FLAG_DEFAULT || flags == FLAG_EXTENDED) && len in 1..MAX_LEN) {
                    return false
                }
            }
            return true
        }
        return chunk.size >= 2 && chunk[chunk.size - 2] == LEGACY_TAIL[0] && chunk[chunk.size - 1] == LEGACY_TAIL[1]
    }

    private fun parseV2Frame(frame: ByteArray): EUCData? {
        val flags = frame[2].toInt() and 0xFF
        if (flags != FLAG_INITIAL && flags != FLAG_DEFAULT && flags != FLAG_EXTENDED) return null

        val len = frame[3].toInt() and 0xFF
        if (len <= 0) return null

        val command = frame[4].toInt() and 0x7F
        val payload = if (len > 1) frame.copyOfRange(5, 5 + (len - 1)) else ByteArray(0)

        return when (command) {
            COMMAND_MAIN_INFO -> {
                lastDetectedDialect = Dialect.V2
                parseMainInfo(payload)
                hasSeenV2MainInfo = true
                null
            }

            COMMAND_TOTAL_STATS -> {
                lastDetectedDialect = Dialect.V2
                parseTotalStats(payload)
                null
            }

            COMMAND_REAL_TIME_INFO -> parseRealTime(payload, frame)?.also {
                lastDetectedDialect = Dialect.V2
                hasSeenV2Realtime = true
            }

            COMMAND_BATTERY_INFO -> {
                lastDetectedDialect = Dialect.V2
                parseBatteryInfo(payload)
                null
            }

            in V14_BMS_PACK_ADDRESSES -> {
                lastDetectedDialect = Dialect.V2
                parseV14PackCellsResponse(command, payload)
                null
            }

            else -> null
        }
    }

    private fun parseLegacyFrame(frame: ByteArray): EUCData? {
        if (frame.size < 8) return null
        if (frame[0] != HEADER[0] || frame[1] != HEADER[1]) return null

        return when (frame[2].toInt() and 0xFF) {
            0x14 -> {
                lastDetectedDialect = Dialect.LEGACY_V1
                parseLegacyInfo(frame)
                null
            }

            0x13 -> parseLegacyRealtime(frame)?.also {
                lastDetectedDialect = Dialect.LEGACY_V1
                hasSeenLegacyRealtime = true
            }

            else -> null
        }
    }

    private fun parseLegacyInfo(frame: ByteArray) {
        if (frame.size < 48) return

        // Legacy captures encode model marker and serial seed in the same block.
        serialNumber = decodeLegacySerial(frame)
        modelName = mapLegacyModel(frame.getOrNull(19)?.toInt()?.and(0xFF) ?: 0)
        firmwareVersion = decodeLegacyFirmware(frame)
    }

    private fun decodeLegacySerial(frame: ByteArray): String? {
        if (frame.size < 27) return null
        val serialBytes = frame.copyOfRange(19, 27).reversedArray()
        return serialBytes.joinToString("") { "%02X".format(it) }.ifEmpty { null }
    }

    private fun mapLegacyModel(modelCode: Int): String {
        return when (modelCode) {
            0x1B -> "InMotion V5F"
            0x0E -> "InMotion V8F"
            0x06 -> "InMotion V8S"
            else -> "InMotion"
        }
    }

    private fun decodeLegacyFirmware(frame: ByteArray): String? {
        if (frame.size < 48) return null
        val b43 = frame[43].toInt() and 0xFF
        val b44 = frame[44].toInt() and 0xFF
        val b45 = frame[45].toInt() and 0xFF
        val b46 = frame[46].toInt() and 0xFF
        val b47 = frame[47].toInt() and 0xFF

        return if (b43 == 0 && b47 > 0 && b46 > 0) {
            val build = b44
            val minor = b46
            val major = b47
            "$major.$minor.$build"
        } else {
            val build = ByteUtils.getUnsignedShortLE(frame, 43)
            val minor = b45
            val major = b46
            "$major.$minor.$build"
        }
    }

    private fun parseLegacyRealtime(frame: ByteArray): EUCData? {
        if (frame.size < 67) return null

        val voltage =
            (ByteUtils.tryGetUnsignedShortLE(frame, LEGACY_VOLTAGE_OFFSET) ?: return null) / 100.0
        val current =
            (ByteUtils.tryGetSignedShortLE(frame, LEGACY_CURRENT_OFFSET)?.toInt() ?: 0) / 100.0
        val speedRaw = ByteUtils.tryGetSignedShortLE(frame, LEGACY_SPEED_OFFSET)?.toInt() ?: 0
        val speed = (speedRaw / LEGACY_SPEED_DIVISOR).coerceIn(LEGACY_SPEED_MIN, LEGACY_SPEED_MAX)
        val tripDistanceKm =
            (ByteUtils.tryGetUnsignedIntLE(frame, LEGACY_TRIP_DISTANCE_OFFSET)?.toDouble()
                ?: 0.0) / 1000.0
        val totalDistance =
            (ByteUtils.tryGetUnsignedIntLE(frame, LEGACY_TOTAL_DISTANCE_OFFSET)?.toDouble()
                ?: 0.0) / 1000.0
        val battery = if (frame.size > LEGACY_BATTERY_OFFSET) {
            (frame[LEGACY_BATTERY_OFFSET].toInt() and 0xFF).coerceIn(0, 100)
        } else {
            (((voltage - LEGACY_BATTERY_BASE_VOLTAGE) / LEGACY_BATTERY_VOLTAGE_RANGE) * 100.0).roundToInt()
                .coerceIn(0, 100)
        }

        val temperature = ByteUtils.tryGetSignedByte(frame, LEGACY_TEMP_OFFSET)?.toDouble() ?: 0.0
        val motorTemp = ByteUtils.tryGetSignedByte(frame, LEGACY_MOTOR_TEMP_OFFSET)?.toDouble()
        val rideTimeSeconds =
            ByteUtils.tryGetUnsignedIntLE(frame, LEGACY_RIDE_TIME_OFFSET) ?: 0L

        if (totalDistance > 0.0) totalDistanceKm = totalDistance

        val modeFromLegacy = when {
            current < 0 -> "charging"
            speed != 0.0 -> "active"
            else -> "idle"
        }

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = battery,
            distance = tripDistanceKm,
            power = voltage * current,
            timestamp = System.currentTimeMillis(),
            rawData = frame,
            manufacturer = manufacturer,
            model = modelName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            isCharging = false,
            rideTime = rideTimeSeconds,
            cellVoltages = null,
            motorTemperature = motorTemp,
            totalDistance = totalDistanceKm,
            mode = modeFromLegacy,
        )
            .also { decoded ->
                updateBmsSnapshot(
                    voltage = decoded.voltage,
                    current = decoded.current,
                    temperatures = listOfNotNull(
                        decoded.temperature,
                        decoded.motorTemperature
                    )
                )
            }
    }

    private fun parseMainInfo(payload: ByteArray) {
        if (payload.isEmpty()) return
        when (payload[0].toInt() and 0xFF) {
            0x01 -> { // car type
                if (payload.size >= 4) {
                    val series = payload[2].toInt() and 0xFF
                    val type = payload[3].toInt() and 0xFF
                    modelName = when {
                        series == 6 && type == 1 -> "InMotion V11"
                        series == 6 && type == 2 -> "InMotion V11Y"
                        series == 7 && type == 1 -> "InMotion V12 HS"
                        series == 7 && type == 2 -> "InMotion V12 HT"
                        series == 7 && type == 3 -> "InMotion V12 PRO"
                        series == 8 && type == 1 -> "InMotion V13"
                        series == 8 && type == 2 -> "InMotion V13 PRO"
                        series == 9 && type == 1 -> "InMotion V14 50GB"
                        series == 9 && type == 2 -> "InMotion V14 50S"
                        series == 11 && type == 1 -> "InMotion V12S"
                        series == 12 && type == 1 -> "InMotion V9"
                        series == 13 && type == 1 -> "InMotion P6"
                        else -> "InMotion $series.$type"
                    }
                    enqueueP6StatsQueryIfDue(force = true)
                }
            }

            0x02 -> { // serial
                if (payload.size >= 17) {
                    serialNumber =
                        payload.copyOfRange(1, 17).decodeToString().trim('\u0000').ifEmpty { null }
                }
            }

            0x06 -> { // versions
                if (payload.size >= 24) {
                    val drv3 = ByteUtils.getUnsignedShortLE(payload, 2)
                    val drv2 = ByteUtils.getUnsignedByte(payload, 4)
                    val drv1 = ByteUtils.getUnsignedByte(payload, 5)
                    val main3 = ByteUtils.getUnsignedShortLE(payload, 11)
                    val main2 = ByteUtils.getUnsignedByte(payload, 13)
                    val main1 = ByteUtils.getUnsignedByte(payload, 14)
                    val ble3 = ByteUtils.getUnsignedShortLE(payload, 20)
                    val ble2 = ByteUtils.getUnsignedByte(payload, 22)
                    val ble1 = ByteUtils.getUnsignedByte(payload, 23)
                    firmwareVersion =
                        "Main:$main1.$main2.$main3 Drv:$drv1.$drv2.$drv3 BLE:$ble1.$ble2.$ble3"
                }
            }
        }
    }

    private fun parseTotalStats(payload: ByteArray) {
        val totalMeters = decodeTotalMeters(payload) ?: return
        if (totalMeters >= 0) totalDistanceKm = totalMeters / 1000.0
        // Offsets 12 and 16 carry cumulative ride-time and power-on-time in seconds
        // (uint32 LE). Only latch onto values that fit within a plausible lifetime
        // (20 years ≈ 630 million seconds).
        val maxLifetimeSeconds = 630_000_000L
        ByteUtils.tryGetUnsignedIntLE(payload, 12)
            ?.takeIf { it in 0L..maxLifetimeSeconds }
            ?.let { totalRideTimeSeconds = it }
        ByteUtils.tryGetUnsignedIntLE(payload, 16)
            ?.takeIf { it in 0L..maxLifetimeSeconds }
            ?.let { totalPowerOnTimeSeconds = it }
    }

    private fun decodeTotalMeters(payload: ByteArray): Long? {
        if (payload.size < 4) return null
        return if (isPrefixedTotalStatsEncoding(payload)) {
            ByteUtils.tryGetUnsignedIntLE(payload, 1)?.times(10L)
        } else {
            ByteUtils.tryGetSignedIntLE(payload, 0)?.toLong()?.times(10L)
        }
    }

    private fun isPrefixedTotalStatsEncoding(payload: ByteArray): Boolean {
        return payload.size >= 5 && payload[0] == payload[1]
    }

    private fun isV12Model(): Boolean = modelName in V12_MODEL_NAMES

    private fun parseRealTime(payload: ByteArray, rawFrame: ByteArray): EUCData? {
        return if (isV12Model()) {
            parseRealTimeV12(payload, rawFrame)
        } else {
            parseRealTimeV14(payload, rawFrame)
        }
    }

    /**
     * V12 HS / HT / PRO / S realtime telemetry (command 0x04, payload ≥ 56 bytes).
     *
     * Layout (all multi-byte fields are uint16/int16 LE unless noted):
     *   offset  0..1   voltage      uint16  ×0.01 V
     *   offset  2..3   current      int16   ×0.01 A
     *   offset  4..5   speed        int16   ×0.01 km/h (signed)
     *   offset  6..7   torque       int16   ×0.01 N·m
     *   offset  8..9   pwm          int16   ×0.01 %
     *   offset 10..11  motorPower   int16   W
     *   offset 16..17  pitchAngle   int16   ×0.01°
     *   offset 20..21  rollAngle    int16   ×0.01°
     *   offset 22..23  mileage      uint16  ×0.01 km (trip distance)
     *   offset 24..25  batLevel     uint16  ×0.01 %
     *   offset 30..31  dynSpeedLimit uint16 ×0.01 km/h
     *   offset 40      MOS temp     uint8   (offset80: byte + 80 – 256 → °C)
     *   offset 41      MOT temp     uint8
     *   offset 43      BOARD temp   uint8
     *   offset 44      CPU temp     uint8   (0x00 = sensor absent)
     *   offset 45      IMU temp     uint8
     *   offset 54      state byte   bits 0..2 = pcMode, bit 7 = charging
     *   offset 55      light byte   bit 0 = low beam, bit 1 = high beam
     *
     * Reference: eucplanet InMotionV2ParserV12.kt (InMotionV2ParserV12.parseTelemetry).
     */
    private fun parseRealTimeV12(payload: ByteArray, rawFrame: ByteArray): EUCData? {
        if (payload.size < V12_REALTIME_MIN_SIZE) return null

        val voltage = ByteUtils.getUnsignedShortLE(payload, 0) / 100.0
        val current = ByteUtils.getSignedShortLE(payload, 2) / 100.0
        val speed = ByteUtils.getSignedShortLE(payload, 4) / 100.0
        val torque = ByteUtils.getSignedShortLE(payload, 6) / 100.0
        val pwm = ByteUtils.getSignedShortLE(payload, 8) / 100.0
        val pitchAngle = ByteUtils.getSignedShortLE(payload, 16) / 100.0
        val rollAngle = ByteUtils.getSignedShortLE(payload, 20) / 100.0
        val tripKm = ByteUtils.getUnsignedShortLE(payload, 22) / 100.0
        val batteryRaw = ByteUtils.getUnsignedShortLE(payload, 24)
        val batteryPercent = (batteryRaw / 100.0).roundToInt().coerceIn(0, 100)
        val dynSpeedLimit = ByteUtils.tryGetUnsignedShortLE(payload, 30)
            ?.let { it / 100.0 }?.takeIf { it > 0.0 }

        // Temperatures use the offset-80 encoding (same helper as V14/P6):
        //   byte value = (desired_Celsius + 256 - 80)  (unsigned uint8)
        //   decoded    = (raw + 80 - 256)              (signed Celsius)
        // Offsets: 40=MOS, 41=MOT, 43=BOARD, 44=CPU, 45=IMU. Skip 42 (BAT, always 0).
        val mosTemp = decodeTemperature(payload[40])
        val motTemp = decodeTemperature(payload[41])
        val boardTemp = decodeTemperature(payload[43])
        // CPU sensor reports 0x00 when absent; decodeTemperature(0x00) = -176, so filter that.
        val cpuTempRaw = ByteUtils.tryGetUnsignedByte(payload, 44)
        val cpuTempC = cpuTempRaw?.let { decodeTemperature(it.toByte()) }?.takeIf { it > -100 }
        val imuTempC = ByteUtils.tryGetUnsignedByte(payload, 45)
            ?.let { decodeTemperature(it.toByte()) }

        val stateByte = payload[54].toInt() and 0xFF
        val isCharging = (stateByte and 0x80) != 0

        val modeString = when {
            isCharging -> "charging"
            (stateByte and 0x01) == 1 -> "active"
            (stateByte and 0x02) == 2 -> "calibration"
            else -> "idle"
        }

        val now = System.currentTimeMillis()
        val cellVoltages = getCombinedCellVoltages(lastKnownBmsSnapshot)

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = mosTemp.toDouble(),
            batteryLevel = batteryPercent,
            distance = tripKm,
            power = voltage * current,
            pwm = pwm,
            torque = torque,
            timestamp = now,
            rawData = rawFrame,
            manufacturer = manufacturer,
            model = modelName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            isCharging = isCharging,
            rideTime = deriveV2RideTimeSeconds(now),
            cellVoltages = cellVoltages,
            motorTemperature = motTemp.toDouble(),
            mosfetTemperature = mosTemp.toDouble(),
            boardTemperature = boardTemp.toDouble(),
            imuTemperature = imuTempC?.toDouble(),
            totalDistance = totalDistanceKm,
            totalRideTimeSeconds = totalRideTimeSeconds,
            totalPowerOnTimeSeconds = totalPowerOnTimeSeconds,
            angle = pitchAngle,
            roll = rollAngle,
            speedLimit = dynSpeedLimit,
            mode = modeString,
        ).also { decoded ->
            updateBmsSnapshot(
                voltage = decoded.voltage,
                current = decoded.current,
                temperatures = listOfNotNull(
                    decoded.temperature,
                    decoded.motorTemperature,
                    decoded.mosfetTemperature,
                    decoded.boardTemperature,
                    decoded.imuTemperature
                )
            )
        }
    }

    private fun parseRealTimeV14(payload: ByteArray, rawFrame: ByteArray): EUCData? {
        if (payload.size < V2_REALTIME_MIN_SIZE) return null

        val isP6 = modelName == "InMotion P6"
        if (isP6) enqueueP6StatsQueryIfDue()
        val voltage = ByteUtils.getUnsignedShortLE(payload, 0) / 100.0
        val current = ByteUtils.getSignedShortLE(payload, 2) / 100.0
        val speed = ByteUtils.getSignedShortLE(payload, 8) / 100.0
        val torque = ByteUtils.tryGetSignedShortLE(payload, 12)?.let { it / 100.0 }
        // Phase current is not transmitted by the P6; the InMotion app derives it from
        // torque using the motor's torque constant. Verified against a same-ride
        // video+btsnoop by eucplanet (commit 32385baa): phase = torque / 0.586 Nm/A
        // reproduces app readings within rounding over a 75x torque range.
        // Kept signed (negative on regen) to match how current and torque are shown.
        val phaseCurrent = if (modelName == "InMotion P6") torque?.div(P6_KT_NM_PER_A) else null
        val pwm = (ByteUtils.tryGetSignedShortLE(payload, 14)?.toDouble() ?: 0.0) / 100.0

        // Pitch and roll angles per the V14 telemetry layout (verified against
        // eucplanet's InMotionV2Parser reference implementation).
        val pitchAngle = ByteUtils.tryGetSignedShortLE(payload, 20)?.let { it / 100.0 }
        val rollAngle = ByteUtils.tryGetSignedShortLE(payload, 22)?.let { it / 100.0 }

        val distanceKm = (ByteUtils.getUnsignedShortLE(payload, 28) * 10.0) / 1000.0

        val battery = if (isP6) {
            val battery1 = ByteUtils.tryGetUnsignedShortLE(payload, 20)?.let { it / 100.0 }
            val battery2 = ByteUtils.tryGetUnsignedShortLE(payload, 22)?.let { it / 100.0 }
            val avg = when {
                battery1 != null && battery2 != null && (battery1 > 0.0 || battery2 > 0.0) -> (battery1 + battery2) / 2.0
                battery1 != null && battery1 > 0.0 -> battery1
                battery2 != null && battery2 > 0.0 -> battery2
                else -> null
            }
            (avg ?: 0.0).roundToInt().coerceIn(0, 100)
        } else {
            val battery1 = ByteUtils.getUnsignedShortLE(payload, 34)
            val battery2 = ByteUtils.getUnsignedShortLE(payload, 36)
            // Use the higher of the two battery banks to match the InMotion app display.
            // The two banks track independently and averaging reads ~2% low vs the
            // manufacturer app (per eucplanet research).
            (maxOf(battery1, battery2) / 100.0).roundToInt().coerceIn(0, 100)
        }

        // Dynamic speed limit at offset 40 (reported in 0.01 km/h units).
        val dynSpeedLimit = ByteUtils.tryGetUnsignedShortLE(payload, 40)
            ?.let { it / 100.0 }
            ?.takeIf { it > 0.0 }

        val mosTemp = decodeTemperature(payload[58])
        val boardTemp = decodeTemperature(payload[59])
        val imuTemp = ByteUtils.tryGetUnsignedByte(payload, 63)?.let { decodeTemperature(it.toByte()) }
        val p6MotorTemp = if (isP6) {
            ByteUtils.tryGetSignedByte(payload, 31)?.let { decodeP6SignedOffset80Temperature(it) }
        } else {
            null
        }
        val telemetryTemp = if (isP6) boardTemp.toDouble() else mosTemp.toDouble()
        val telemetryMotorTemp = p6MotorTemp ?: boardTemp.toDouble()
        val stateByte = payload[74].toInt() and 0xFF
        val isCharging = ((stateByte shr 7) and 0x01) == 1
        val now = System.currentTimeMillis()
        val rideTimeFromPayload = ByteUtils.tryGetUnsignedIntLE(payload, 24)
            ?.takeIf { it in 0L..604_800L }
        val rideTimeSeconds = rideTimeFromPayload ?: deriveV2RideTimeSeconds(now)
        val modeString = when {
            isCharging -> "charging"
            (stateByte and 0x01) == 1 -> "active"
            (stateByte and 0x02) == 2 -> "calibration"
            else -> "idle"
        }

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = telemetryTemp,
            batteryLevel = battery,
            distance = distanceKm,
            power = voltage * current,
            pwm = pwm,
            torque = torque,
            phaseCurrent = phaseCurrent,
            timestamp = now,
            rawData = rawFrame,
            manufacturer = manufacturer,
            model = modelName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            isCharging = isCharging,
            rideTime = rideTimeSeconds,
            cellVoltages = getCombinedCellVoltages(lastKnownBmsSnapshot),
            motorTemperature = telemetryMotorTemp,
            mosfetTemperature = mosTemp.toDouble(),
            boardTemperature = boardTemp.toDouble(),
            imuTemperature = imuTemp?.toDouble(),
            totalDistance = totalDistanceKm,
            totalRideTimeSeconds = totalRideTimeSeconds,
            totalPowerOnTimeSeconds = totalPowerOnTimeSeconds,
            angle = pitchAngle,
            roll = rollAngle,
            speedLimit = dynSpeedLimit,
            mode = modeString,
        )
            .also { decoded ->
                updateBmsSnapshot(
                    voltage = decoded.voltage,
                    current = decoded.current,
                    temperatures = listOfNotNull(
                        decoded.temperature,
                        decoded.motorTemperature,
                        decoded.mosfetTemperature,
                        decoded.boardTemperature,
                        decoded.imuTemperature
                    )
                )
            }
    }

    private fun enqueueP6StatsQueryIfDue(force: Boolean = false) {
        if (lastDetectedDialect != Dialect.V2 || modelName != "InMotion P6") return
        val now = System.currentTimeMillis()
        if (!force && now - lastP6StatsQueryAtMs < P6_STATS_QUERY_INTERVAL_MS) return
        lastP6StatsQueryAtMs = now
        _writeChannel.trySend(buildMessage(FLAG_EXTENDED, COMMAND_REAL_TIME_INFO, byteArrayOf()))
    }

    private fun deriveV2RideTimeSeconds(nowMs: Long): Long {
        val start = v2SessionStartTimestampMs ?: nowMs.also { v2SessionStartTimestampMs = it }
        return ((nowMs - start) / 1000L).coerceAtLeast(0L)
    }

    /**
     * Decode the BATTERY_INFO response (command 0x05).
     *
     * Two layouts are handled:
     *
     * 1. V14 per-cell response (`payload[0] == 0x02`, `payload[1] == 0x82`): 32 cell voltages
     *    as uint16-LE millivolts starting at offset 2. Requires at least 2 + 64 = 66 bytes.
     *    Reverse-engineered from Nordic sniffer captures of the InMotion Android app against a
     *    V14 Adventure (eucplanet InMotionV2Parser.parseV14PackCells).
     *
     * 2. Legacy 4-pack summary: 4 × uint16-LE centivolts at byte strides of 8. This was the
     *    original path and is kept as the fallback.
     */
    private fun parseBatteryInfo(payload: ByteArray) {
        // Try V14 per-cell format first.
        val cells = parseV14PackCells(payload)
        if (cells != null) {
            updateBmsSnapshot(cellVoltages = cells)
            return
        }

        // Fall back to 4-pack voltage summary.
        if (payload.size < 32) return
        val packVoltages = mutableListOf<Double>()
        var offset = 0
        repeat(4) {
            val packCentiVolts = ByteUtils.tryGetUnsignedShortLE(payload, offset) ?: 0
            if (packCentiVolts > 0) {
                packVoltages.add(packCentiVolts / 100.0)
            }
            offset += 8
        }
        if (packVoltages.isNotEmpty()) {
            updateBmsSnapshot(packVoltages = packVoltages)
        }
    }

    /**
     * Decode the V14 per-pack cells response prefix `02 82` followed by 32 × uint16-LE
     * millivolt values (e.g. `03 10` = 0x1003 = 4099 mV = 4.099 V).
     *
     * Returns a list of 32 voltages in volts on success, null when the prefix or length
     * doesn't match (so the caller can fall through to the legacy path).
     */
    private fun parseV14PackCells(payload: ByteArray): List<Double>? {
        if (payload.size < 2 + 64) return null
        if (payload[0] != 0x02.toByte()) return null
        if ((payload[1].toInt() and 0xFF) != 0x82) return null
        val cells = mutableListOf<Double>()
        var off = 2
        repeat(32) {
            val mv = (payload[off].toInt() and 0xFF) or
                ((payload[off + 1].toInt() and 0xFF) shl 8)
            cells.add(mv / 1000.0)
            off += 2
        }
        return cells
    }

    private fun parseV14PackCellsResponse(command: Int, payload: ByteArray) {
        val cells = parseV14PackCells(payload) ?: return
        val packIndex = (command - V14_BMS_PACK_ADDRESSES.first()) + 1
        updateBmsSnapshot(packCellVoltages = mapOf(packIndex to cells))
    }

    private fun updateBmsSnapshot(
        voltage: Double? = null,
        current: Double? = null,
        temperatures: List<Double>? = null,
        packVoltages: List<Double>? = null,
        cellVoltages: List<Double>? = null,
        packCellVoltages: Map<Int, List<Double>>? = null
    ) {
        synchronized(parseLock) {
            val currentSnapshot = lastKnownBmsSnapshot
            lastKnownBmsSnapshot = InMotionBmsSnapshot(
                voltage = voltage ?: currentSnapshot.voltage,
                current = current ?: currentSnapshot.current,
                temperatures = (temperatures ?: currentSnapshot.temperatures)?.takeIf { it.isNotEmpty() },
                packVoltages = (packVoltages ?: currentSnapshot.packVoltages)?.takeIf { it.isNotEmpty() },
                cellVoltages = (cellVoltages ?: currentSnapshot.cellVoltages)?.takeIf { it.isNotEmpty() },
                packCellVoltages = if (packCellVoltages.isNullOrEmpty()) {
                    currentSnapshot.packCellVoltages
                } else {
                    currentSnapshot.packCellVoltages + packCellVoltages
                }
            )
        }
    }

    private fun getCombinedCellVoltages(snapshot: InMotionBmsSnapshot): List<Double>? {
        if (snapshot.packCellVoltages.isNotEmpty()) {
            return snapshot.packCellVoltages
                .toSortedMap()
                .values
                .flatten()
                .takeIf { it.isNotEmpty() }
        }
        return snapshot.cellVoltages?.takeIf { it.isNotEmpty() }
    }

    private fun allowsActivePolling(): Boolean {
        return lastDetectedDialect != Dialect.LEGACY_V1
    }

    /**
     * Command dispatcher for legacy InMotion V1 wheels. Uses V1 CAN frames
     * wrapped in `AA AA … 55 55` framing (eucplanet InMotionV1Commands).
     */
    private fun createV1Command(commandType: CommandType, value: Any): ByteArray {
        return when (commandType) {
            CommandType.LIGHT_ON -> buildV1LightFrame(true)
            CommandType.LIGHT_OFF -> buildV1LightFrame(false)
            CommandType.BEEP -> buildV1HornFrame()
            CommandType.LOCK -> buildV1LockFrame(true)
            CommandType.UNLOCK -> buildV1LockFrame(false)
            CommandType.CUSTOM -> (value as? ByteArray)?.clone() ?: byteArrayOf()
            else -> byteArrayOf()
        }
    }

    private fun decodeTemperature(raw: Byte): Int = (raw.toInt() and 0xFF) + 80 - 256

    // P6 motor temp uses a signed-byte +80°C encoding in the field consumed here:
    // 0x00 -> 80°C, 0xB0(-80) -> 0°C, 0xFF(-1) -> 79°C. This intentionally
    // differs from decodeTemperature(), which assumes an unsigned-byte input.
    private fun decodeP6SignedOffset80Temperature(raw: Int): Double =
        (raw + 80).toDouble()

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        // Route legacy V1 commands through the V1 CAN frame builder.
        if (lastDetectedDialect == Dialect.LEGACY_V1) {
            return createV1Command(commandType, value)
        }
        if (!allowsActivePolling()) return byteArrayOf()
        // While dialect is unknown, only allow the V2 probe and pre-built CUSTOM frames
        // (the V1 handshake queries) to avoid spamming V2-only requests against legacy devices.
        if (lastDetectedDialect == Dialect.UNKNOWN &&
            commandType != CommandType.REQUEST_FIRMWARE &&
            commandType != CommandType.CUSTOM) {
            return byteArrayOf()
        }
        return when (commandType) {
            // V12 HS/HT/PRO/S use a two-beam form [0x50, low, high]; the standard
            // single-byte form is silently ignored on those models (eucplanet InMotionV2Adapter).
            CommandType.LIGHT_ON -> if (isV12Model()) {
                buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x50, 0x01, 0x01))
            } else {
                buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x50, 0x01))
            }

            CommandType.LIGHT_OFF -> if (isV12Model()) {
                buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x50, 0x00, 0x00))
            } else {
                buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x50, 0x00))
            }

            CommandType.LIGHT_BRIGHTNESS -> {
                val brightness = (value as? Int)?.coerceIn(0, 100) ?: return byteArrayOf()
                buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x2b, brightness.toByte()))
            }

            CommandType.BEEP -> buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x18, 0x00))
            CommandType.LOCK -> buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x31, 0x01))
            CommandType.UNLOCK -> buildMessage(
                FLAG_DEFAULT,
                COMMAND_CONTROL,
                byteArrayOf(0x31, 0x00)
            )

            CommandType.POWER_OFF -> buildMessage(
                FLAG_DEFAULT,
                COMMAND_CONTROL,
                byteArrayOf(0x77, 0x01)
            )
            // InMotion V2 returns model/serial/firmware from the same MAIN_INFO page; both queries use the same request.
            CommandType.REQUEST_SERIAL,
            CommandType.REQUEST_FIRMWARE -> buildMessage(
                FLAG_INITIAL,
                COMMAND_MAIN_INFO,
                byteArrayOf()
            )

            CommandType.REQUEST_BATTERY_INFO -> buildMessage(
                FLAG_DEFAULT,
                COMMAND_REAL_TIME_INFO,
                byteArrayOf()
            )

            CommandType.CUSTOM -> (value as? ByteArray)?.clone() ?: byteArrayOf()

            else -> byteArrayOf()
        }
    }

    override fun getPollingPlan(): ProtocolPollingPlan {
        // Legacy InMotion wheels are telemetry-push based in this library path, so
        // active polling should stay disabled once legacy dialect is identified.
        if (!allowsActivePolling()) {
            return ProtocolPollingPlan.disabled()
        }
        return ProtocolPollingPlan(
            enabled = true,
            startupQueries = listOf(
                // V1 PIN handshake: sent before any V2 probe so locked V1 wheels unlock
                // and begin streaming. V2 wheels ignore these frames (different framing).
                // eucplanet InMotionV1Adapter.initSequence() sends exactly these three
                // frames before polling begins.
                ProtocolQuerySpec(
                    id = "inmotion.v1-factory-password",
                    commandType = CommandType.CUSTOM,
                    value = buildV1FactoryPasswordFrame(),
                    initialDelayMs = 0L,
                    responseTimeoutMs = 400L,
                    maxRetries = 2
                ),
                ProtocolQuerySpec(
                    id = "inmotion.v1-pin",
                    commandType = CommandType.CUSTOM,
                    value = buildV1PinFrame(),
                    initialDelayMs = 50L,
                    responseTimeoutMs = 400L,
                    maxRetries = 2
                ),
                ProtocolQuerySpec(
                    id = "inmotion.dialect-probe",
                    commandType = CommandType.REQUEST_FIRMWARE,
                    initialDelayMs = 150L,
                    responseTimeoutMs = 800L,
                    maxRetries = 1
                )
            ),
            periodicQueries = listOf(
                ProtocolQuerySpec(
                    id = "inmotion.realtime",
                    commandType = CommandType.REQUEST_BATTERY_INFO,
                    intervalMs = 1_000L,
                    responseTimeoutMs = 1_200L,
                    maxRetries = 1
                ),
                ProtocolQuerySpec(
                    id = "inmotion.v14-pack-1-cells",
                    commandType = CommandType.CUSTOM,
                    value = buildV14PackCellsQuery(0x24),
                    initialDelayMs = 1_000L,
                    intervalMs = 4_000L,
                    responseTimeoutMs = 1_200L,
                    maxRetries = 1
                ),
                ProtocolQuerySpec(
                    id = "inmotion.v14-pack-2-cells",
                    commandType = CommandType.CUSTOM,
                    value = buildV14PackCellsQuery(0x25),
                    initialDelayMs = 2_000L,
                    intervalMs = 4_000L,
                    responseTimeoutMs = 1_200L,
                    maxRetries = 1
                ),
                ProtocolQuerySpec(
                    id = "inmotion.v14-pack-3-cells",
                    commandType = CommandType.CUSTOM,
                    value = buildV14PackCellsQuery(0x26),
                    initialDelayMs = 3_000L,
                    intervalMs = 4_000L,
                    responseTimeoutMs = 1_200L,
                    maxRetries = 1
                ),
                ProtocolQuerySpec(
                    id = "inmotion.v14-pack-4-cells",
                    commandType = CommandType.CUSTOM,
                    value = buildV14PackCellsQuery(0x27),
                    initialDelayMs = 4_000L,
                    intervalMs = 4_000L,
                    responseTimeoutMs = 1_200L,
                    maxRetries = 1
                )
            )
        )
    }

    override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean {
        if (data.size < 5 || data[0] != HEADER[0] || data[1] != HEADER[1]) return false
        val command = data[4].toInt() and 0x7F
        return when (query.commandType) {
            CommandType.REQUEST_SERIAL,
            CommandType.REQUEST_FIRMWARE -> command == COMMAND_MAIN_INFO

            CommandType.REQUEST_BATTERY_INFO ->
                command == COMMAND_REAL_TIME_INFO || command == COMMAND_TOTAL_STATS || command == COMMAND_BATTERY_INFO
            CommandType.CUSTOM -> matchesCustomQueryResponse(query, command, data)
            else -> false
        }
    }

    private fun matchesCustomQueryResponse(query: ProtocolQuerySpec, command: Int, data: ByteArray): Boolean {
        val request = query.value as? ByteArray ?: return false
        if (request.size < 7 || request[4].toInt() and 0xFF != COMMAND_MAIN_INFO) return false
        val packAddress = request[5].toInt() and 0xFF
        val subCommand = request[6].toInt() and 0xFF
        if (packAddress !in V14_BMS_PACK_ADDRESSES || subCommand != 0x02) return false
        if (command != packAddress) return false
        // When the response bit (0x80) makes cmdByte equal 0xA5 or 0xAA (the two escape-
        // trigger values), the device encodes the cmd byte as {0xA5, cmdByte} so the
        // first payload byte is at position 6; otherwise it is at position 5.
        val cmdByte = packAddress or 0x80
        val escaped = data.size > 5 &&
            (data[4].toInt() and 0xFF) == 0xA5 &&
            (data[5].toInt() and 0xFF) == cmdByte
        val payloadOffset = if (escaped) 6 else 5
        if (data.size < payloadOffset + 2) return false
        return (data[payloadOffset].toInt() and 0xFF) == 0x02 &&
            (data[payloadOffset + 1].toInt() and 0xFF) == 0x82
    }

    private fun buildV14PackCellsQuery(packAddress: Int): ByteArray =
        buildMessage(FLAG_EXTENDED, COMMAND_MAIN_INFO, byteArrayOf(packAddress.toByte(), 0x02))

    // --- InMotion V1 frame builder -----------------------------------------

    /**
     * Build a V1 BLE frame from a 32-bit CAN ID and an 8-byte payload:
     * `AA AA <escaped 16-byte CAN prefix> <escaped checksum> 55 55`.
     *
     * Ported from eucplanet InMotionV1Protocol.buildFrame() / wrap().
     */
    private fun buildV1Frame(canId: Int, data: ByteArray): ByteArray {
        require(data.size == 8) { "V1 CAN data must be 8 bytes" }
        val can = ByteArray(16)
        can[0] = (canId and 0xFF).toByte()
        can[1] = ((canId ushr 8) and 0xFF).toByte()
        can[2] = ((canId ushr 16) and 0xFF).toByte()
        can[3] = ((canId ushr 24) and 0xFF).toByte()
        data.copyInto(can, 4)
        can[12] = V1_LEN_NORMAL
        can[13] = V1_CHANNEL_PHONE
        can[14] = V1_FORMAT_STANDARD
        can[15] = V1_TYPE_DATA

        var checksum = 0
        for (b in can) checksum = (checksum + (b.toInt() and 0xFF)) and 0xFF

        val out = java.io.ByteArrayOutputStream(40)
        out.write(V1_HEADER.toInt() and 0xFF)
        out.write(V1_HEADER.toInt() and 0xFF)
        for (b in can) v1WriteEscaped(out, b)
        v1WriteEscaped(out, checksum.toByte())
        out.write(V1_TRAILER.toInt() and 0xFF)
        out.write(V1_TRAILER.toInt() and 0xFF)
        return out.toByteArray()
    }

    private fun v1WriteEscaped(out: java.io.ByteArrayOutputStream, b: Byte) {
        when (b) {
            V1_HEADER, V1_TRAILER, V1_ESCAPE -> {
                out.write(V1_ESCAPE.toInt() and 0xFF)
                out.write(b.toInt() and 0xFF)
            }
            else -> out.write(b.toInt() and 0xFF)
        }
    }

    /** Factory handshake password frame ("INMOTI"); must be sent first on every connect. */
    private fun buildV1FactoryPasswordFrame(): ByteArray = buildV1PasswordFrame(V1_FACTORY_PASSWORD)

    /** User PIN frame (default "000000"); sent after the factory password. */
    private fun buildV1PinFrame(pin: String = V1_DEFAULT_PIN): ByteArray = buildV1PasswordFrame(pin)

    private fun buildV1PasswordFrame(password: String): ByteArray {
        val data = ByteArray(8)
        for (i in 0 until minOf(6, password.length)) data[i] = password[i].code.toByte()
        return buildV1Frame(V1_CAN_PIN, data)
    }

    /** Fast-info query: fills the payload with 0xFF per the V1 spec. */
    private fun buildV1FastInfoFrame(): ByteArray =
        buildV1Frame(V1_CAN_FAST_INFO, ByteArray(8) { 0xFF.toByte() })

    /** Headlight on/off command for V1 wheels. */
    private fun buildV1LightFrame(on: Boolean): ByteArray =
        buildV1Frame(V1_CAN_HEADLIGHT, byteArrayOf(if (on) 0x01 else 0x00, 0, 0, 0, 0, 0, 0, 0))

    /** Horn / beep command for V1 wheels using the dedicated opcode (V8F / V8S / V10 / Glide 3).
     *  Wheels without the dedicated horn opcode silently ignore it. */
    private fun buildV1HornFrame(): ByteArray =
        buildV1Frame(V1_CAN_REMOTE_CTRL, byteArrayOf(0xB2.toByte(), 0, 0, 0, 0x11, 0, 0, 0))

    /** Software lock command for V1 wheels (sub-commands 0x03 / 0x04 of the remote-control group). */
    private fun buildV1LockFrame(locked: Boolean): ByteArray =
        buildV1Frame(V1_CAN_REMOTE_CTRL,
            byteArrayOf(0xB2.toByte(), 0, 0, 0, if (locked) 0x03 else 0x04, 0, 0, 0))

    private fun buildMessage(flag: Int, command: Int, data: ByteArray): ByteArray {
        val len = data.size + 1
        val body = ByteArray(3 + data.size)
        body[0] = flag.toByte()
        body[1] = len.toByte()
        body[2] = command.toByte()
        if (data.isNotEmpty()) data.copyInto(body, destinationOffset = 3)

        var xor = 0
        for (b in body) xor = xor xor (b.toInt() and 0xFF)
        val checksum = xor.toByte()

        return HEADER + body + byteArrayOf(checksum)
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        if (data.voltage <= BLEConstants.MIN_READY_VOLTAGE_V || data.batteryLevel <= 0) return false
        return when (lastDetectedDialect) {
            Dialect.V2 -> hasSeenV2MainInfo && hasSeenV2Realtime
            Dialect.LEGACY_V1 -> hasSeenLegacyRealtime
            Dialect.UNKNOWN -> false
        }
    }

    override fun getBMSData(): List<BMSData>? {
        val snapshot = lastKnownBmsSnapshot
        val voltage = snapshot.voltage
        val current = snapshot.current
        val temperatures = snapshot.temperatures
        val packVoltages = snapshot.packVoltages
        val cellVoltages = getCombinedCellVoltages(snapshot)
        val packCellVoltages = snapshot.packCellVoltages
        if (voltage == null && current == null && temperatures.isNullOrEmpty()
            && packVoltages.isNullOrEmpty() && cellVoltages.isNullOrEmpty()
        ) {
            return null
        }
        if (packCellVoltages.isNotEmpty()) {
            return packCellVoltages.toSortedMap().map { (index, cells) ->
                BMSData(
                    bmsIndex = index,
                    voltage = packVoltages?.getOrNull(index - 1),
                    current = if (index == 1) current else null,
                    remainingCapacity = null,
                    factoryCapacity = null,
                    cycles = null,
                    temperatures = if (index == 1) temperatures?.takeIf { it.isNotEmpty() } else null,
                    cellVoltages = cells
                )
            }
        }
        // V14 per-cell path: return one BMSData entry with all 32 cell voltages.
        if (!cellVoltages.isNullOrEmpty()) {
            return listOf(
                BMSData(
                    bmsIndex = 1,
                    voltage = voltage,
                    current = current,
                    remainingCapacity = null,
                    factoryCapacity = null,
                    cycles = null,
                    temperatures = temperatures?.takeIf { it.isNotEmpty() },
                    cellVoltages = cellVoltages
                )
            )
        }
        if (!packVoltages.isNullOrEmpty()) {
            return packVoltages.mapIndexed { index, packVoltage ->
                BMSData(
                    bmsIndex = index + 1,
                    voltage = packVoltage,
                    current = if (index == 0) current else null,
                    remainingCapacity = null,
                    factoryCapacity = null,
                    cycles = null,
                    temperatures = if (index == 0) temperatures?.takeIf { it.isNotEmpty() } else null,
                    cellVoltages = null
                )
            }
        }
        return listOf(
            BMSData(
                bmsIndex = 1,
                voltage = voltage,
                current = current,
                remainingCapacity = null,
                factoryCapacity = null,
                cycles = null,
                temperatures = temperatures?.takeIf { it.isNotEmpty() },
                cellVoltages = null
            )
        )
    }

    override fun close() {
        // No resources to clean up
    }
}
