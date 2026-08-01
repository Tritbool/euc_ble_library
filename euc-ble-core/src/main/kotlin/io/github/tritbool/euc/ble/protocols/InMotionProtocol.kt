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
        CommandType.REQUEST_BATTERY_INFO
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

    private fun parseRealTime(payload: ByteArray, rawFrame: ByteArray): EUCData? {
        if (payload.size < 78) return null

        val voltage = ByteUtils.getUnsignedShortLE(payload, 0) / 100.0
        val current = ByteUtils.getSignedShortLE(payload, 2) / 100.0
        val speed = ByteUtils.getSignedShortLE(payload, 8) / 100.0
        val torque = ByteUtils.tryGetSignedShortLE(payload, 12)?.let { it / 100.0 }
        val pwm = (ByteUtils.tryGetSignedShortLE(payload, 14)?.toDouble() ?: 0.0) / 100.0

        // Pitch and roll angles per the V14 telemetry layout (verified against
        // eucplanet's InMotionV2Parser reference implementation).
        val pitchAngle = ByteUtils.tryGetSignedShortLE(payload, 20)?.let { it / 100.0 }
        val rollAngle = ByteUtils.tryGetSignedShortLE(payload, 22)?.let { it / 100.0 }

        val distanceKm = (ByteUtils.getUnsignedShortLE(payload, 28) * 10.0) / 1000.0

        val battery1 = ByteUtils.getUnsignedShortLE(payload, 34)
        val battery2 = ByteUtils.getUnsignedShortLE(payload, 36)
        // Use the higher of the two battery banks to match the InMotion app display.
        // The two banks track independently and averaging reads ~2% low vs the
        // manufacturer app (per eucplanet research).
        val battery = (maxOf(battery1, battery2) / 100.0).roundToInt().coerceIn(0, 100)

        // Dynamic speed limit at offset 40 (reported in 0.01 km/h units).
        val dynSpeedLimit = ByteUtils.tryGetUnsignedShortLE(payload, 40)
            ?.let { it / 100.0 }
            ?.takeIf { it > 0.0 }

        val mosTemp = decodeTemperature(payload[58])
        val boardTemp = decodeTemperature(payload[59])
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
            temperature = mosTemp.toDouble(),
            batteryLevel = battery,
            distance = distanceKm,
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
            rideTime = rideTimeSeconds,
            cellVoltages = null,
            motorTemperature = boardTemp.toDouble(),
            totalDistance = totalDistanceKm,
            angle = pitchAngle,
            roll = rollAngle,
            speedLimit = dynSpeedLimit,
            mode = modeString,
        )
    }

    private fun deriveV2RideTimeSeconds(nowMs: Long): Long {
        val start = v2SessionStartTimestampMs ?: nowMs.also { v2SessionStartTimestampMs = it }
        return ((nowMs - start) / 1000L).coerceAtLeast(0L)
    }

    private fun allowsActivePolling(): Boolean {
        return lastDetectedDialect != Dialect.LEGACY_V1
    }

    private fun decodeTemperature(raw: Byte): Int = (raw.toInt() and 0xFF) + 80 - 256

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        if (!allowsActivePolling()) return byteArrayOf()
        // While dialect is unknown, only allow a minimal V2 probe command to avoid
        // spamming V2-only requests against legacy devices.
        if (lastDetectedDialect == Dialect.UNKNOWN && commandType != CommandType.REQUEST_FIRMWARE) {
            return byteArrayOf()
        }
        return when (commandType) {
            CommandType.LIGHT_ON -> buildMessage(
                FLAG_DEFAULT,
                COMMAND_CONTROL,
                byteArrayOf(0x50, 0x01)
            )

            CommandType.LIGHT_OFF -> buildMessage(
                FLAG_DEFAULT,
                COMMAND_CONTROL,
                byteArrayOf(0x50, 0x00)
            )

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
                ProtocolQuerySpec(
                    id = "inmotion.dialect-probe",
                    commandType = CommandType.REQUEST_FIRMWARE,
                    initialDelayMs = 0L,
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

            CommandType.REQUEST_BATTERY_INFO -> command == COMMAND_REAL_TIME_INFO || command == COMMAND_TOTAL_STATS
            else -> false
        }
    }

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

    /**
     * InMotion V2 protocol does not expose individual cell voltage data in the
     * standard telemetry frames. The P6 has a 56S4P battery configuration (56 cells
     * in series, 4 in parallel) with nominal voltage of 201.6V (3.6V per cell) and
     * full charge voltage of 235.2V (4.2V per cell).
     * 
     * After analyzing the raw frame data from test resources, no BMS cell voltage
     * information was found in the available frame types (MAIN_INFO, REAL_TIME_INFO,
     * TOTAL_STATS). The protocol may not expose individual cell voltages through
     * the standard BLE interface.
     * 
     * Returns null as BMS data is not available through the current protocol implementation.
     */
    override fun getBMSData(): List<BMSData>? = null

    override fun close() {
        // No resources to clean up
    }
}
