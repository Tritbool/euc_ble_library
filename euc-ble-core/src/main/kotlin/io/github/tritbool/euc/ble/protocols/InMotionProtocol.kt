package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.AndroidLogger
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.models.AuthenticationState
import io.github.tritbool.euc.ble.models.resolveBmsChargingState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

        // Extended-routing dialect (official InMotion app; P6 and V9 verified against
        // real captures): requests `AA AA 16 03 02 21 <sub> <checksum>` — i.e. MAIN_INFO
        // with subdevice address 0x21 (main MCU), the same builder the pack queries
        // use with addresses 0x24/0x25. Responses echo the address as the command:
        // `AA AA 16 <len> 21 02 <sub|0x80> <body>`.
        // Matches eucplanet InMotionV2Commands.getP6Info/getP6RealTimeData.
        private const val EXTENDED_ROUTING_MCU_ADDRESS = 0x21

        /** Routing marker that prefixes extended response payloads: `02 <sub|0x80> ...`. */
        private const val EXTENDED_ROUTING_QUERY = 0x02
        private const val EXTENDED_SUB_INFO = 0x06
        private const val EXTENDED_SUB_REALTIME = 0x07
        private const val EXTENDED_RESPONSE_MARKER = 0x80

        // Control-endpoint auth handshake (the "password" dance). The wheel issues a
        // 16-byte token when queried and accepts it right back — the token is fetched
        // from the wheel at runtime; nothing is stored or hardcoded in this library.
        // Verified against a real V9 capture: request `13 04 02 00 00 02`, response
        // `12 13 02 80 02 <16B token>`, echo `13 14 02 00 00 82 <token>`, ack `12 04 02 80 82 01`.
        private const val AUTH_FLAG = 0x13
        // The wheel replies to auth requests with flag 0x12 (eucplanet Flags.QUERY13
        // uses 0x13 for requests; the observed reply flag is 0x12).
        private const val AUTH_RESPONSE_FLAG = 0x12
        private const val AUTH_SUB_REQUEST = 0x02
        private const val AUTH_SUB_VERIFY = 0x82
        private const val AUTH_TOKEN_SIZE = 16

        private const val MIN_FRAME_SIZE = 5
        private const val MAX_LEN = 240

        /* INMOTION WHEELS */
        private const val V5 = 0
        private const val V5F = "InMotion V5F"
        private const val V8 = 0
        private const val V8F = "InMotion V8F"
        private const val V8S = "InMotion V8S"
        private const val V9 = "InMotion V9"
        private const val V10 = 0
        private const val V10F = 0
        private const val V11 = "InMotion V11"
        private const val V11Y = "InMotion V11Y"
        private const val V12 = "InMotion V12"
        private const val V12S = "InMotion V12S"
        private const val V13 = "InMotion V13"
        private const val V14 = "InMotion V14"
        private const val P6 = "InMotion P6"


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
            "$V12 HS",
            "$V12 HT",
            "$V12 PRO",
            V12S
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
        private const val V1_TYPE_REMOTE: Byte = 0x01
        private const val V1_CAN_SLOW_INFO = 0x0F550114

        // CAN IDs used by the protocol.
        private const val V1_CAN_FAST_INFO = 0x0F550113
        private const val V1_CAN_HEADLIGHT = 0x0F55010D
        private const val V1_CAN_REMOTE_CTRL = 0x0F550116
        private const val V1_CAN_PIN = 0x0F550307

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
        CommandType.SET_SPEED_LIMIT,
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

    private val _securityStateFlow =
        MutableStateFlow(WheelSecurityState(authentication = AuthenticationState.UNKNOWN))
    override val securityStateFlow: StateFlow<WheelSecurityState> =
        _securityStateFlow.asStateFlow()

    override fun getSecurityState(): WheelSecurityState = _securityStateFlow.value

    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val rawFrameFlow: Flow<ByteArray> = _rawFrameFlow.asSharedFlow()


    private val parseLock = Any()
    private val v2Buffer = ArrayList<Byte>()
    private val legacyBuffer = ArrayList<Byte>()
    private var lastV2Chunk: ByteArray? = null
    private var lastV2ChunkSnapshot: ByteArray? = null
    private var lastV2Frames: List<ByteArray> = emptyList()

    @Volatile
    private var p6Packs: Map<Int, BMSData> = emptyMap()

    private enum class Dialect { UNKNOWN, LEGACY_V1, V2 }

    @Volatile
    private var lastDetectedDialect: Dialect = Dialect.UNKNOWN

    @Volatile
    private var lastKnownIsCharging: Boolean? = null

    @Volatile
    private var modelName: String = manufacturer

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

    /**
     * Wheel-issued auth token for the control endpoint (lock/unlock/speed/horn).
     * Fetched from the wheel at runtime over the auth handshake — never hardcoded.
     */
    @Volatile
    private var controlAuthToken: ByteArray? = null

    /** True once the wheel acked the echoed token (`80 82 01`). */
    @Volatile
    private var controlAuthVerified: Boolean = false

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

        val v2Frames = synchronized(parseLock) {
            val frames = validatedV2Frames(data)
            // Matching belongs to this notification only. A later decode may reuse
            // exactly the same array (even the same bytes) for a new notification.
            lastV2Chunk = null
            lastV2ChunkSnapshot = null
            lastV2Frames = emptyList()
            frames
        }
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
                logger.info(TAG, "DIALECT SET TO ${Dialect.entries[version]} ")
                lastDetectedDialect = Dialect.entries[version]
            }

            else -> {}
        }

    }

    // BLEManager matches each pending query before decode. Share framing, not parsing,
    // so a notification is consumed once even when several queries inspect it.
    private fun validatedV2Frames(chunk: ByteArray): List<ByteArray> = synchronized(parseLock) {
        if (lastV2Chunk !== chunk || lastV2ChunkSnapshot?.contentEquals(chunk) != true) {
            lastV2Frames = extractV2Frames(chunk)
            lastV2Chunk = chunk
            lastV2ChunkSnapshot = chunk.copyOf()
        }
        lastV2Frames
    }

    private fun extractV2Frames(chunk: ByteArray): List<ByteArray> {
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

                if (flags != FLAG_INITIAL && flags != FLAG_DEFAULT && flags != FLAG_EXTENDED &&
                    flags != AUTH_RESPONSE_FLAG
                ) {
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
                val checksumIdx =
                    findChecksumIndex(v2Buffer, startIdx = 2, targetUnescaped = len + 2)
                if (checksumIdx == -2) {
                    v2Buffer.removeAt(0)
                    continue
                }
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
    private fun unescapeNBytes(
        buffer: List<Byte>,
        startIdx: Int,
        count: Int
    ): Pair<ByteArray, Int>? {
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
                val escaped = buffer[rawIdx + 1].toInt() and 0xFF
                if (escaped != ESCAPE_MARKER && escaped != 0xAA) return -2
                rawIdx += 2
            } else {
                if (b == 0xAA) return -2
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

    private fun isLikelyLegacyChunk(chunk: ByteArray): Boolean {
        if (chunk.size >= 2 && chunk[0] == HEADER[0] && chunk[1] == HEADER[1]) {
            if (chunk.size >= 4) {
                val flags = chunk[2].toInt() and 0xFF
                val len = chunk[3].toInt() and 0xFF
                if ((flags == FLAG_INITIAL || flags == FLAG_DEFAULT || flags == FLAG_EXTENDED ||
                        flags == AUTH_RESPONSE_FLAG) && len in 1..MAX_LEN
                ) {
                    return false
                }
            }
            return true
        }
        return chunk.size >= 2 && chunk[chunk.size - 2] == LEGACY_TAIL[0] && chunk[chunk.size - 1] == LEGACY_TAIL[1]
    }

    private fun parseV2Frame(frame: ByteArray): EUCData? {
        val flags = frame[2].toInt() and 0xFF
        if (flags != FLAG_INITIAL && flags != FLAG_DEFAULT && flags != FLAG_EXTENDED &&
            flags != AUTH_RESPONSE_FLAG
        ) return null

        val len = frame[3].toInt() and 0xFF
        if (len <= 0) return null

        val command = frame[4].toInt() and 0x7F
        val payload = if (len > 1) frame.copyOfRange(5, 5 + (len - 1)) else ByteArray(0)

        return when (command) {
            0x00 -> {
                if (isLegacyIdentification(command, frame)) {
                    parseLegacyIdentification(payload)
                }
                null
            }

            // Extended-routing responses echo the main-MCU address 0x21 as the
            // command: `21 02 <sub|0x80> <body>` (official-app dialect, verified
            // against a real V9 capture).
            EXTENDED_ROUTING_MCU_ADDRESS -> {
                if (flags == FLAG_EXTENDED) {
                    lastDetectedDialect = Dialect.V2
                    parseExtendedRoutingResponse(payload)
                } else {
                    null
                }
            }

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
                if (supportsExtendedRoutingPacks()) {
                    if (flags == FLAG_EXTENDED) parseP6Pack(command, payload)
                } else {
                    parseV14PackCellsResponse(command, payload)
                }
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
            0x1B -> V5F
            0x0E -> V8F
            0x06 -> V8S
            else -> manufacturer
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
        // Control-endpoint auth replies ride on MAIN_INFO with a 0x80 routing prefix:
        // [80 02 <16B token>] = token issued, [80 82 01] = echo accepted.
        // Distinct from the regular pages, which start with 01/02/06.
        if (payload[0].toInt() and 0xFF == 0x80) {
            handleControlAuth(payload)
            return
        }
        when (payload[0].toInt() and 0xFF) {
            0x01 -> { // car type
                if (payload.size >= 4) {
                    val series = payload[2].toInt() and 0xFF
                    val type = payload[3].toInt() and 0xFF
                    modelName = when {
                        series == 6 && type == 1 -> V11
                        series == 6 && type == 2 -> V11Y
                        series == 7 && type == 1 -> "$V12 HS"
                        series == 7 && type == 2 -> "$V12 HT"
                        series == 7 && type == 3 -> "$V12 PRO"
                        series == 8 && type == 1 -> V13
                        series == 8 && type == 2 -> "$V13 PRO"
                        series == 9 && type == 1 -> "$V14 50GB"
                        series == 9 && type == 2 -> "$V14 50S"
                        series == 11 && type == 1 -> V12S
                        series == 12 && type == 1 -> V9
                        series == 13 && type == 1 -> P6
                        else -> "$manufacturer $series.$type"
                    }
                    enqueueP6StatsQueryIfDue(force = true)
                    // Extended-routing wheels gate control commands behind the auth
                    // handshake; request the token as soon as the model latches so
                    // lock/unlock/speed-limit work whenever the user sends them.
                    if (supportsExtendedRoutingPacks()) enqueueControlAuthRequest()
                }
            }

            0x02 -> { // serial
                if (payload.size >= 17) {
                    serialNumber =
                        payload.copyOfRange(1, 17).decodeToString().trim('\u0000').ifEmpty { null }
                }
            }

            0x06 -> { // versions
                parseVersionEntries(payload.copyOfRange(1, payload.size))
                    ?.let { firmwareVersion = it }
            }
        }
    }

    /**
     * Dispatch an extended-routing response body `02 <sub|0x80> <data>` (the
     * official-app dialect; the routing marker 0x02 and the echoed sub-command
     * with 0x80 already validated by the caller).
     *
     * Sub-commands verified against a real V9 capture (screenshots + btsnoop):
     *  - 0x86: identification bundle — `01 00 01 <16-byte ASCII serial>`, then
     *    the car-type page `02 <series> <type>` and a component-versions list.
     *  - 0x87: realtime telemetry in the P6 layout (eucplanet
     *    InMotionV2Parser.parseP6Telemetry) with the V9's temperature block at
     *    body[30] MOS / body[31] motor / body[32] board.
     */
    private fun parseExtendedRoutingResponse(payload: ByteArray): EUCData? {
        if (payload.size < 2) return null
        if (payload[0].toInt() != EXTENDED_ROUTING_QUERY) return null
        val sub = payload[1].toInt() and 0x7F
        val body = payload.copyOfRange(2, payload.size)
        return when (sub) {
            EXTENDED_SUB_INFO -> {
                parseExtendedIdentity(body)
                null
            }
            EXTENDED_SUB_REALTIME -> {
                // Realtime bodies carry an extra `01 00` record prefix before the
                // data block (eucplanet: "skip the 02 87 01 00 prefix").
                val data = if (body.size >= 2 &&
                    body[0].toInt() and 0xFF == 0x01 &&
                    body[1].toInt() and 0xFF == 0x00
                ) body.copyOfRange(2, body.size) else body
                parseExtendedRealtime(data)?.also { hasSeenV2Realtime = true }
            }
            else -> null
        }
    }

    /**
     * Identification bundle body: `01 00 01 <16-byte ASCII serial> ... car-type
     * page `02 <series> <type>` ... version entries `[id][u16le patch][minor][major]`.
     * Verified against a real V9 capture: serial `A1421911600183A4`, car type
     * series 12/type 1 (InMotion V9), BMS-pack version entries with
     * minor=14 (screenshot `V0.0.14`).
     */
    private fun parseExtendedIdentity(body: ByteArray) {
        if (body.size < 20) return
        // Body starts `01 00 01 <16-byte ASCII serial>` — the 0x01 record marker
        // precedes the serial (eucplanet parseP6Serial uses the same layout).
        if (body[0].toInt() and 0xFF == 0x01) {
            val serialEnd = minOf(19, body.size)
            val serial = body.copyOfRange(3, serialEnd)
                .decodeToString().trim { it == '\u0000' || it == ' ' }.ifEmpty { null }
            if (serial != null) serialNumber = serial
        }
        // Car-type page: find the `02 <series> <type>` marker with a known series.
        var i = 18
        while (i + 2 < body.size) {
            if (body[i].toInt() and 0xFF == EXTENDED_ROUTING_QUERY) {
                val series = body[i + 1].toInt() and 0xFF
                val type = body[i + 2].toInt() and 0xFF
                val mapped = mapCarType(series, type)
                if (mapped != null) {
                    modelName = mapped
                    enqueueP6StatsQueryIfDue(force = true)
                    // See parseMainInfo: gate control commands behind the runtime
                    // auth handshake for extended-routing wheels.
                    if (supportsExtendedRoutingPacks()) enqueueControlAuthRequest()
                    break
                }
            }
            i++
        }
        // Component-version entries follow the car-type page (see
        // parseVersionEntries). A real V9 identity bundle carries them in the
        // same frame (October capture: Main:1.8.50 Drv:7.4.38 BLE:1.4.16).
        val versionListStart = findVersionEntriesStart(body)
        if (versionListStart != null) {
            parseVersionEntries(body.copyOfRange(versionListStart, body.size))
                ?.let { firmwareVersion = it }
        }
        hasSeenV2MainInfo = true
    }

    /**
     * Locate the version-entry list inside the identity bundle. Entries start
     * with a component id (0x21 main / 0x22 driver / 0x23 BLE) followed by a
     * u16le patch whose high byte is zero, a minor and a major in 1..99.
     * Returns the offset of the first matching entry id byte, or null.
     */
    private fun findVersionEntriesStart(body: ByteArray): Int? {
        for (i in 0 until body.size - 5) {
            val id = body[i].toInt() and 0xFF
            if (id in 0x21..0x23 &&
                body[i + 2].toInt() and 0xFF == 0x00 &&
                body[i + 4].toInt() and 0xFF in 0x01..0x63
            ) {
                return i
            }
        }
        return null
    }

    /** Map an InMotion car-type pair to a model name, or null when unknown. */
    private fun mapCarType(series: Int, type: Int): String? = when {
        series == 6 && type == 1 -> V11
        series == 6 && type == 2 -> V11Y
        series == 7 && type == 1 -> "$V12 HS"
        series == 7 && type == 2 -> "$V12 HT"
        series == 7 && type == 3 -> "$V12 PRO"
        series == 8 && type == 1 -> V13
        series == 8 && type == 2 -> "$V13 PRO"
        series == 9 && type == 1 -> "$V14 50GB"
        series == 9 && type == 2 -> "$V14 50S"
        series == 11 && type == 1 -> V12S
        series == 12 && type == 1 -> V9
        series == 13 && type == 1 -> P6
        else -> null
    }

    /**
     * V9/P6 extended realtime body (after `02 87`), offsets cross-checked against
     * eucplanet's parseP6Telemetry and a labelled V9 capture (InMotion app
     * screenshots matching to the frame):
     *   [0]  voltage u16 LE / 100        [2]  current i16 LE / 100
     *   [8]  speed i16 LE / 100           [12] torque i16 LE / 100 (N·m)
     *   [14] pwm i16 LE / 100 (%)         [20] battery1 u16 LE / 100
     *   [22] battery2 u16 LE / 100
     *   [30] MOS temp (offset-80)         [31] motor temp (offset-80)
     *   [32] board temp (offset-80)
     * Speed@8, PWM@14, torque@12, battery@20/22 confirmed by eucplanet against
     * labelled P6 captures; temperatures verified on the V9 against the
     * InMotion app's own screen (MOS 27°C / Motor 35°C / Board 24°C).
     */
    private fun parseExtendedRealtime(body: ByteArray): EUCData? {
        if (body.size < 33) return null
        val voltage = ByteUtils.getUnsignedShortLE(body, 0) / 100.0
        val current = ByteUtils.getSignedShortLE(body, 2) / 100.0
        val speed = ByteUtils.getSignedShortLE(body, 8) / 100.0
        val torque = ByteUtils.getSignedShortLE(body, 12) / 100.0
        val pwm = ByteUtils.getSignedShortLE(body, 14) / 100.0
        val battery1 = ByteUtils.getUnsignedShortLE(body, 20) / 100.0
        val battery2 = ByteUtils.getUnsignedShortLE(body, 22) / 100.0
        val battery = ((battery1 + battery2) / 2.0).roundToInt().coerceIn(0, 100)

        val mosTemp = decodeTemperature(body[30])
        val motorTemp = decodeTemperature(body[31])
        val boardTemp = decodeTemperature(body[32])
        val imuTemp = ByteUtils.tryGetUnsignedByte(body, 28)
            ?.let { decodeTemperature(it.toByte()) }

        val now = System.currentTimeMillis()
        lastKnownIsCharging = current < 0
        hasSeenV2Realtime = true

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = mosTemp.toDouble(),
            batteryLevel = battery,
            distance = 0.0,
            wheelDistance = 0.0,
            power = voltage * current,
            pwm = pwm,
            torque = torque,
            timestamp = now,
            rawData = body,
            manufacturer = manufacturer,
            model = modelName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            isCharging = current < 0,
            rideTime = deriveV2RideTimeSeconds(now),
            motorTemperature = motorTemp.toDouble(),
            mosfetTemperature = mosTemp.toDouble(),
            boardTemperature = boardTemp.toDouble(),
            imuTemperature = imuTemp?.toDouble(),
            totalDistance = totalDistanceKm,
            totalRideTimeSeconds = totalRideTimeSeconds,
            totalPowerOnTimeSeconds = totalPowerOnTimeSeconds,
            angle = null,
            roll = null,
            speedLimit = null,
            mode = if (current < 0) "charging" else if (speed != 0.0) "active" else "idle",
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

    /**
     * Parse the component-version entries shared by the MAIN_INFO versions page
     * (sub 0x06) and the extended-routing identity bundle. Entries are 9-byte
     * `[id][u16le patch][minor][major][4 unknown bytes]` records:
     *   0x21 main board, 0x22 driver board, 0x23 BLE, 0x24/0x25 BMS packs.
     * Verified against two captures of the same V9 taken seven months apart
     * (March WheelLog versions page: Drv 7.4.40 / Main 1.8.38 / BLE 1.4.10;
     * October InMotion-app identity bundle: Main 1.8.50 / Drv 7.4.38 /
     * BLE 1.4.16 / BMS packs 0.0.14, matching the app screenshot `V0.0.14`).
     * Components are located by id, not by fixed offset, so the different
     * entry order between the two frames (drv-first vs main-first) is handled.
     */
    private fun parseVersionEntries(payload: ByteArray): String? {
        if (payload.size < 5) return null
        var main: Triple<Int, Int, Int>? = null
        var drv: Triple<Int, Int, Int>? = null
        var ble: Triple<Int, Int, Int>? = null
        var i = 0
        while (i + 5 <= payload.size) {
            val id = payload[i].toInt() and 0xFF
            val patch = ByteUtils.getUnsignedShortLE(payload, i + 1)
            val minor = payload[i + 3].toInt() and 0xFF
            val major = payload[i + 4].toInt() and 0xFF
            when (id) {
                0x21 -> main = Triple(major, minor, patch)
                0x22 -> drv = Triple(major, minor, patch)
                0x23 -> ble = Triple(major, minor, patch)
            }
            i += 9
        }
        val mainStr = main?.let { "Main:${it.first}.${it.second}.${it.third}" }
        val drvStr = drv?.let { "Drv:${it.first}.${it.second}.${it.third}" }
        val bleStr = ble?.let { "BLE:${it.first}.${it.second}.${it.third}" }
        return listOfNotNull(mainStr, drvStr, bleStr).joinToString(" ").ifEmpty { null }
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

    private fun supportsV14PackCellQueries(): Boolean =
        modelName == "$V14 50GB" ||
                modelName == "$V14 50S"

    /**
     * Wheels that answer the P6-style extended-routing pack queries
     * (`02 24/25 <sub>` → `24/25 02 <sub|0x80> <body>`): the P6 (56 cells/pack,
     * 2 packs) and the V9 (20 cells/pack, 2 packs). Verified against real
     * captures of both; the page layouts are identical except cell count.
     */
    private fun supportsExtendedRoutingPacks(): Boolean =
        modelName == P6 || modelName == V9

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
        lastKnownIsCharging = isCharging

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
            wheelDistance = tripKm,
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

        val isP6 = modelName == P6
        val tirePressureKpa = if (isP6) {
            ByteUtils.tryGetUnsignedShortLE(payload, 78)
                ?.takeIf { it in 50..1_500 }
                ?.toDouble()
        } else {
            null
        }
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
        val phaseCurrent = if (modelName == P6) torque?.div(P6_KT_NM_PER_A) else null
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
        val imuTemp =
            ByteUtils.tryGetUnsignedByte(payload, 63)?.let { decodeTemperature(it.toByte()) }
        val p6MotorTemp = if (isP6) {
            ByteUtils.tryGetSignedByte(payload, 31)?.let { decodeP6SignedOffset80Temperature(it) }
        } else {
            null
        }
        val telemetryTemp = if (isP6) boardTemp.toDouble() else mosTemp.toDouble()
        val telemetryMotorTemp = p6MotorTemp ?: boardTemp.toDouble()
        val stateByte = payload[74].toInt() and 0xFF
        val isCharging = ((stateByte shr 7) and 0x01) == 1
        lastKnownIsCharging = isCharging
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
            wheelDistance = distanceKm,
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
            tirePressureKpa = tirePressureKpa,
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
        if (lastDetectedDialect != Dialect.V2 || modelName != P6) return
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

    private fun isP6PackPayload(command: Int, payload: ByteArray): Boolean {
        if (command !in 0x24..0x25 || payload.size < 2 || payload[0] != 0x02.toByte()) return false
        return when (payload[1].toInt() and 0xFF) {
            // Summary page: 60 bytes on the P6, 56 on the V9 (real captures).
            0x81 -> payload.size == 60 || payload.size == 56
            // Cell page: 56 cells on the P6 (114 bytes), 20 cells on the V9
            // (42 bytes). Only the two observed pack sizes are accepted — a
            // 32-cell V14 page (66 bytes) must NOT match.
            0x82 -> payload.size == 114 || payload.size == 42
            // Identity page is 62 bytes on both (real captures).
            0x84 -> payload.size == 62
            else -> false
        }
    }

    /**
     * Extract the pack serial from a 62-byte identity page. Two layouts exist
     * (both verified against real captures):
     *  - P6: 20-byte default message at [22..42] ("this is default mess"),
     *    serial at [42..62] (`Z100E5A00319`).
     *  - V9: serial directly at [22..42] (`BW268Y00720S103A624`), 0xFF padding
     *    after.
     * The classic [42..62] position wins when it holds a printable serial;
     * otherwise the [22..42] run is used. Printable-ASCII gate rejects the
     * message text only when the P6 position is empty, so no model gate is
     * needed.
     */
    private fun decodePackSerial(payload: ByteArray): String? {
        if (payload.size < 42) return null
        fun run(from: Int, to: Int): String? {
            if (payload.size < to) return null
            val text = payload.copyOfRange(from, to)
                .decodeToString()
                .trim { it == '\u0000' || it == ' ' }
            // Reject runs containing non-printable bytes (e.g. the V9's 0xFF
            // padding) and board-message filler that is shorter than a serial.
            return text.takeIf { it.length >= 8 && it.all { c -> c.code in 0x20..0x7E } }
        }
        return run(42, 62) ?: run(22, 42)
    }

    private fun parseP6Pack(command: Int, payload: ByteArray) {
        if (!isP6PackPayload(command, payload)) return
        synchronized(parseLock) {
            val index = command - 0x23
            val previous = p6Packs[index] ?: BMSData(
                index, null, null, null, null, null, null, null
            )
            val updated = when (payload[1].toInt() and 0xFF) {
                0x81 -> {
                    // Offsets are relative to the data AFTER the 02 81 prefix.
                    // The charging capture includes -21 cA on the idle discharge
                    // sensor. Negative channel noise must not become 655.15 A.
                    val charge = ByteUtils.getSignedShortLE(payload, 10).coerceAtLeast(0) / 100.0
                    val discharge = ByteUtils.getSignedShortLE(payload, 12).coerceAtLeast(0) / 100.0
                    previous.copy(
                        voltage = ByteUtils.getUnsignedShortLE(payload, 8) / 100.0,
                        chargeCurrent = charge,
                        dischargeCurrent = discharge,
                        current = discharge - charge,
                        isCharging = charge > 0.1
                    )
                }

                // Cell page size varies per pack: 56 cells (P6, 114-byte payload)
                // or 20 cells (V9, 42-byte payload). Verified against real captures.
                0x82 -> previous.copy(cellVoltages = List((payload.size - 2) / 2) {
                    ByteUtils.getUnsignedShortLE(payload, 2 + it * 2) / 1000.0
                })

                else -> {
                    val serial = decodePackSerial(payload)
                    previous.copy(
                        firmwareVersion = "${payload[13].toInt() and 0xFF}.${payload[12].toInt() and 0xFF}.${
                            ByteUtils.getUnsignedShortLE(
                                payload,
                                10
                            )
                        }",
                        serialNumber = serial
                    )
                }
            }
            p6Packs = p6Packs + (index to updated)
        }
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
                temperatures = (temperatures
                    ?: currentSnapshot.temperatures)?.takeIf { it.isNotEmpty() },
                packVoltages = (packVoltages
                    ?: currentSnapshot.packVoltages)?.takeIf { it.isNotEmpty() },
                cellVoltages = (cellVoltages
                    ?: currentSnapshot.cellVoltages)?.takeIf { it.isNotEmpty() },
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
            commandType != CommandType.CUSTOM
        ) {
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

            CommandType.BEEP -> if (supportsExtendedRoutingPacks()) {
                // Extended-routing horn: `02 21 60 51 18 01` — PLAY_SOUND 0x51,
                // sound id 0x18, volume 0x01 (eucplanet hornP6/playSoundHorn,
                // taken verbatim from an InMotion app capture).
                buildExtendedRoutingCommand(0x51, byteArrayOf(0x18, 0x01))
            } else {
                buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x18, 0x00))
            }
            CommandType.LOCK -> if (supportsExtendedRoutingPacks()) {
                // Verified against a real V9: `AA AA 16 05 02 21 60 31 01`.
                buildExtendedRoutingCommand(0x31, byteArrayOf(0x01))
            } else {
                buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x31, 0x01))
            }
            CommandType.UNLOCK -> if (supportsExtendedRoutingPacks()) {
                buildExtendedRoutingCommand(0x31, byteArrayOf(0x00))
            } else {
                buildMessage(
                    FLAG_DEFAULT,
                    COMMAND_CONTROL,
                    byteArrayOf(0x31, 0x00)
                )
            }

            CommandType.SET_SPEED_LIMIT -> if (supportsExtendedRoutingPacks()) {
                // Extended-routing wheels take a 2-byte u16 LE value in 0.01 km/h
                // units (the P6 form, per eucplanet; NOT the V14 4-byte packet).
                // Verified against a real V9: 25 km/h -> `02 21 60 21 c4 09`,
                // 35 km/h -> `02 21 60 21 ac 0d`.
                val maxSpeedKmh = (value as? Int)?.coerceIn(0, 999) ?: return byteArrayOf()
                val raw = (maxSpeedKmh * 100).coerceAtMost(0xFFFF)
                buildExtendedRoutingCommand(
                    0x21,
                    byteArrayOf((raw and 0xFF).toByte(), (raw shr 8).toByte())
                )
            } else {
                byteArrayOf()
            }

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
        // When the GATT fingerprint already resolved the dialect to V2 (the BLEManager
        // calls setDialect(2) before polling starts), the V1 handshake chain is pure
        // dead time: a V2-fingerprint wheel never answers the V1 password/PIN/slow-info
        // CAN frames, and the serialized retries cost ~6 s before the first telemetry
        // poll (measured on a real V9). Skip straight to identification + realtime.
        val dialectAlreadyV2 = lastDetectedDialect == Dialect.V2
        val v1HandshakeQueries = if (dialectAlreadyV2) emptyList() else listOf(
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
        )
        val v1SlowInfoQuery = if (dialectAlreadyV2) emptyList() else listOf(
            ProtocolQuerySpec(
                id = "inmotion.v1-slow-info",
                commandType = CommandType.CUSTOM,
                value = buildV1SlowInfoFrame(),
                initialDelayMs = 250L,
                responseTimeoutMs = 1_200L,
                maxRetries = 2
            )
        )
        return ProtocolPollingPlan(
            enabled = true,
            startupQueries = v1HandshakeQueries + listOf(
                // Extended-routing identification (official-app dialect): the
                // same MAIN_INFO builder addressed to the main MCU (0x21), sub 0x06.
                // A real V9 answers with its serial, car type and component versions
                // in a single ~100 ms round-trip — unlike the V2 probe and the V1
                // slow-info frame, which this wheel never answers.
                ProtocolQuerySpec(
                    id = "inmotion.extended-identification",
                    commandType = CommandType.CUSTOM,
                    value = buildExtendedRoutingQuery(EXTENDED_SUB_INFO),
                    initialDelayMs = if (dialectAlreadyV2) 0L else 200L,
                    responseTimeoutMs = 800L,
                    maxRetries = 2
                )
            ) + v1SlowInfoQuery,
            periodicQueries = listOf(
                ProtocolQuerySpec(
                    id = "inmotion.realtime",
                    commandType = CommandType.REQUEST_BATTERY_INFO,
                    intervalMs = 200L,
                    responseTimeoutMs = 400L,
                    maxRetries = 1
                ),
                // Extended-routing realtime (sub 0x07): carries the motor
                // temperature (body[31]) and per-pack battery percentages, which
                // the plain 0x04 realtime frame does not expose on the V9.
                ProtocolQuerySpec(
                    id = "inmotion.extended-realtime",
                    commandType = CommandType.CUSTOM,
                    value = buildExtendedRoutingQuery(EXTENDED_SUB_REALTIME),
                    intervalMs = 1_000L,
                    responseTimeoutMs = 800L,
                    maxRetries = 1
                ),
            ) + (
                if (supportsExtendedRoutingPacks()) listOf(
                    // Periodic control-auth refresh. The capture shows the official
                    // app runs the handshake before every control command, and the V6
                    // needs it repeated roughly every 6 s to keep a session at all
                    // (eucplanet requiresConnectAuth). Re-requesting the token keeps
                    // the control endpoint unlocked without any stored secret — the
                    // wheel re-issues the token each round and the echo follows.
                    ProtocolQuerySpec(
                        id = "inmotion.control-auth",
                        commandType = CommandType.CUSTOM,
                        value = buildControlAuthRequest(),
                        initialDelayMs = 6_000L,
                        intervalMs = 6_000L,
                        responseTimeoutMs = 1_200L,
                        maxRetries = 1
                    )
                ) else emptyList()
                ) + if (modelName == manufacturer) listOf(
                ProtocolQuerySpec(
                    id = "inmotion.identification",
                    commandType = CommandType.REQUEST_FIRMWARE,
                    intervalMs = 4_000L,
                    responseTimeoutMs = 800L,
                    maxRetries = 1
                ),
                // Identification retries also refresh through the extended query —
                // the V2-style probe is only answered with the board string.
                ProtocolQuerySpec(
                    id = "inmotion.extended-identification",
                    commandType = CommandType.CUSTOM,
                    value = buildExtendedRoutingQuery(EXTENDED_SUB_INFO),
                    intervalMs = 4_000L,
                    responseTimeoutMs = 800L,
                    maxRetries = 1
                )
            ) else if (supportsExtendedRoutingPacks()) p6PollingQueries() else if (supportsV14PackCellQueries()) listOf(
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
            ) else emptyList()
        )
    }

    /**
     * Extracts the model name from a legacy identification payload such as
     * "\u0000Form V11 HMIC(0x21)". E.g. maps to [V11] when known, otherwise keeps the
     * trimmed ASCII name so consumers at least see what the wheel reported.
     */
    private fun parseLegacyIdentification(payload: ByteArray) {
        val text = payload.dropWhile { it.toInt() == 0x00 }.toByteArray().decodeToString().trim()
        if (text.isEmpty()) return
        // The board form-factor string (e.g. a V9 reports "Form V11 HMIC(0x21)")
        // is NOT firmware. It used to overwrite the real version latched from
        // MAIN_INFO, replacing it in the stats screen every time the 4-second
        // identification poll was answered. Keep it only as a last-resort
        // board hint when nothing better has arrived.
        if (firmwareVersion == null) firmwareVersion = text
    }

    /**
     * Legacy (V1-dialect) wheels answer the identification query with a frame whose
     * command byte is 0x00 and whose payload is the ASCII model name, e.g.
     * "\u0000Form V11 HMIC(0x21)". V2 wheels use COMMAND_MAIN_INFO instead. Without
     * this match the identification query retried forever and the model never latched.
     */
    private fun isLegacyIdentification(command: Int, frame: ByteArray): Boolean {
        if (command != 0x00) return false
        if (frame.size < 8) return false
        val len = frame[3].toInt() and 0xFF
        val payload =
            if (len > 1) frame.copyOfRange(5, minOf(5 + (len - 1), frame.size)) else ByteArray(0)
        val text = payload.dropWhile { it.toInt() == 0x00 }.toByteArray().decodeToString()
        return text.startsWith("Form ") || text.contains("HMIC") || text.contains("InMotion")
    }

    override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean {
        return validatedV2Frames(data).any { frame ->
            val command = frame[4].toInt() and 0x7F
            when (query.commandType) {
                CommandType.REQUEST_SERIAL,
                CommandType.REQUEST_FIRMWARE ->
                    command == COMMAND_MAIN_INFO || isLegacyIdentification(command, frame)

                CommandType.REQUEST_BATTERY_INFO ->
                    command == COMMAND_REAL_TIME_INFO || command == COMMAND_TOTAL_STATS || command == COMMAND_BATTERY_INFO

                CommandType.CUSTOM -> matchesCustomQueryResponse(query, command, frame)
                else -> false
            }
        }
    }

    private fun matchesCustomQueryResponse(
        query: ProtocolQuerySpec,
        command: Int,
        data: ByteArray
    ): Boolean {
        val request = query.value as? ByteArray ?: return false
// V1 slow-info request (a raw V1 CAN frame, not a V2 message): V1 frames are
// wrapped AA AA ... 55 55 while V2 messages end with a checksum byte. The wheel
// answers the slow-info request with MAIN_INFO pages (cmd 0x82: car type, serial,
// versions).
        if (request.size >= 4 && request[0] == V1_HEADER && request[1] == V1_HEADER &&
            request[request.size - 2] == V1_TRAILER && request[request.size - 1] == V1_TRAILER
        ) {
            return command == COMMAND_MAIN_INFO
        }
        // Control-auth request: the wheel answers with its 16-byte token
        // (`12 13 02 80 02 <16B>`, capture-verified). The echo + ack follow
        // automatically via handleControlAuth.
        if (request.size >= 7 &&
            request[2].toInt() and 0xFF == AUTH_FLAG &&
            request[4].toInt() and 0xFF == COMMAND_MAIN_INFO &&
            request[7].toInt() and 0xFF == AUTH_SUB_REQUEST
        ) {
            if (data.size < 8 || data[2].toInt() and 0xFF != AUTH_RESPONSE_FLAG) return false
            val len = data[3].toInt() and 0xFF
            if (data.size < len + 5) return false
            if (command != COMMAND_MAIN_INFO) return false
            // Response payload: `80 02 <16B token>`.
            if (data[5].toInt() and 0xFF != EXTENDED_RESPONSE_MARKER) return false
            if (data[6].toInt() and 0xFF != AUTH_SUB_REQUEST) return false
            return data.size >= 5 + 2 + AUTH_TOKEN_SIZE
        }
        // Extended-routing query to the main MCU (address 0x21): the response
        // echoes 0x21 as the command with `02 <sub|0x80> <body>` as payload.
        // Verified against a real V9 capture (sub 0x06 identification, sub 0x07
        // realtime, each answered in ~35 ms).
        if (request.size >= 7 &&
            request[2].toInt() and 0xFF == FLAG_EXTENDED &&
            request[4].toInt() and 0xFF == COMMAND_MAIN_INFO &&
            request[5].toInt() and 0xFF == EXTENDED_ROUTING_MCU_ADDRESS
        ) {
            if (command != EXTENDED_ROUTING_MCU_ADDRESS) return false
            if (data.size < 8 || data[2].toInt() and 0xFF != FLAG_EXTENDED) return false
            // Unescaped frame = AA AA + flag + len + (cmd + data: len bytes)
            // + checksum = len + 5 bytes total.
            val len = data[3].toInt() and 0xFF
            if (data.size < len + 5) return false
            if (data[5].toInt() and 0xFF != EXTENDED_ROUTING_QUERY) return false
            val subCommand = request[6].toInt() and 0xFF
            val echoed = data[6].toInt() and 0xFF
            return (echoed and 0x7F) == subCommand && (echoed and EXTENDED_RESPONSE_MARKER) != 0
        }
        if (request.size < 7 || request[4].toInt() and 0xFF != COMMAND_MAIN_INFO) return false
        val packAddress = request[5].toInt() and 0xFF
        val subCommand = request[6].toInt() and 0xFF
        if (command != packAddress) return false
        val payload = data.copyOfRange(5, data.lastIndex)
        if (supportsExtendedRoutingPacks()) {
            return data[2].toInt() and 0xFF == FLAG_EXTENDED &&
                    isP6PackPayload(command, payload) &&
                    payload[1].toInt() and 0xFF == (subCommand or 0x80)
        }
        return packAddress in V14_BMS_PACK_ADDRESSES && subCommand == 0x02 &&
                parseV14PackCells(payload) != null
    }

    /** Pack queries shared by the P6 (56 cells/pack) and the V9 (20 cells/pack). */
    private fun p6PollingQueries(): List<ProtocolQuerySpec> =
        (0x24..0x25).flatMap { pack ->
            listOf(1, 2, 4).map { subtype ->
                ProtocolQuerySpec(
                    id = "inmotion.p6-pack-${pack - 0x23}-$subtype",
                    commandType = CommandType.CUSTOM,
                    value = buildMessage(
                        FLAG_EXTENDED, COMMAND_MAIN_INFO,
                        byteArrayOf(pack.toByte(), subtype.toByte())
                    ),
                    initialDelayMs = ((pack - 0x24) * 3 + subtype) * 250L,
                    intervalMs = if (subtype == 4) 0L else 4_000L,
                    responseTimeoutMs = 1_200L,
                    maxRetries = 1
                )
            }
        }

    private fun buildV14PackCellsQuery(packAddress: Int): ByteArray =
        buildMessage(FLAG_EXTENDED, COMMAND_MAIN_INFO, byteArrayOf(packAddress.toByte(), 0x02))

    /**
     * Extended-routing query of the official InMotion app (eucplanet
     * InMotionV2Commands.getP6Info/getP6RealTimeData). Wire form:
     * `AA AA 16 03 02 21 <sub> <checksum>` — MAIN_INFO addressed to subdevice
     * 0x21 (main MCU), the exact same builder and shape as the pack queries
     * (which use addresses 0x24/0x25 with a subtype byte). The wheel answers
     * with `21 02 <sub|0x80> <body>` — verified against a real V9 capture where
     * the identification (sub 0x06) and realtime (sub 0x07) queries were each
     * answered in ~35 ms.
     */
    private fun buildExtendedRoutingQuery(sub: Int): ByteArray =
        buildMessage(
            FLAG_EXTENDED,
            COMMAND_MAIN_INFO,
            byteArrayOf(
                EXTENDED_ROUTING_MCU_ADDRESS.toByte(),
                sub.toByte()
            )
        )

    /**
     * Handle a control-endpoint auth reply (payload `[80 <sub> <body>]` on
     * MAIN_INFO):
     *  - `[80 02 <16B token>]`: the wheel issued its control token. Echo it back
     *    verbatim — nothing is derived, stored on disk or hardcoded; the token
     *    lives only in this session's memory.
     *  - `[80 82 01]`: the wheel accepted the echoed token; control commands are
     *    unlocked for the session.
     * Verified against a real V9 capture (session 2026-10-26).
     */
    private fun handleControlAuth(payload: ByteArray) {
        when (payload.getOrNull(1)?.toInt()?.and(0xFF)) {
            AUTH_SUB_REQUEST -> {
                val token = payload.copyOfRange(2, minOf(2 + AUTH_TOKEN_SIZE, payload.size))
                if (token.size != AUTH_TOKEN_SIZE) return
                controlAuthToken = token
                _securityStateFlow.value = _securityStateFlow.value.copy(
                    authentication = AuthenticationState.REQUIRED
                )
                // Echo the wheel-issued token back (capture: `13 14 02 00 00 82 <token>`).
                _writeChannel.trySend(
                    buildMessage(
                        AUTH_FLAG,
                        COMMAND_MAIN_INFO,
                        byteArrayOf(0x00, 0x00, AUTH_SUB_VERIFY.toByte()) + token
                    )
                )
            }

            AUTH_SUB_VERIFY -> {
                // `80 82 01` = token accepted. Reported by the wheel, not inferred
                // from a successful write.
                if (payload.getOrNull(2)?.toInt() == 0x01) {
                    controlAuthVerified = true
                    _securityStateFlow.value = _securityStateFlow.value.copy(
                        authentication = AuthenticationState.AUTHENTICATED
                    )
                }
            }
        }
    }

    /**
     * Ask the wheel to issue its control token (capture: `13 04 02 00 00 02`).
     * The reply is handled by [handleControlAuth], which echoes the token back.
     * No secret is embedded anywhere — the token originates from the wheel.
     */
    /**
     * Auth-key request frame (capture: `AA AA 13 04 02 00 00 02 17`). The wheel
     * answers with its 16-byte control token, which [handleControlAuth] echoes
     * back. No secret is embedded anywhere — the token originates from the wheel.
     */
    private fun buildControlAuthRequest(): ByteArray =
        buildMessage(
            AUTH_FLAG,
            COMMAND_MAIN_INFO,
            byteArrayOf(0x00, 0x00, AUTH_SUB_REQUEST.toByte())
        )

    private fun enqueueControlAuthRequest() {
        // Only until the wheel issues its token: a periodically re-broadcast
        // car-type frame must not re-trigger the handshake once it has run.
        if (controlAuthToken != null) return
        _writeChannel.trySend(buildControlAuthRequest())
    }

    /**
     * Extended-routing control command of the official InMotion app, used by the
     * P6/V9 dialect: `AA AA 16 <len> 02 21 60 <sub> <args> <checksum>`. Verified
     * against a real V9 capture:
     *  - lock/unlock: sub 0x31, args 01/00
     *  - max speed:   sub 0x21, args = u16 LE in 0.01 km/h (25 km/h -> c4 09)
     * The wheel acks with `AA AA 16 <len> 21 02 e0 <sub> 00 <checksum>`.
     */
    private fun buildExtendedRoutingCommand(sub: Int, args: ByteArray): ByteArray =
        buildMessage(
            FLAG_EXTENDED,
            COMMAND_MAIN_INFO,
            byteArrayOf(EXTENDED_ROUTING_MCU_ADDRESS.toByte()) +
                    byteArrayOf(COMMAND_CONTROL.toByte()) +
                    byteArrayOf(sub.toByte()) +
                    args
        )

    // --- InMotion V1 frame builder -----------------------------------------

    private fun buildV1Frame(canId: Int, data: ByteArray): ByteArray =
        buildV1Frame(canId, data, V1_TYPE_DATA)

    /**
     * Build a V1 BLE frame from a 32-bit CAN ID and an 8-byte payload:
     * `AA AA <escaped 16-byte CAN prefix> <escaped checksum> 55 55`.
     *
     * Ported from eucplanet InMotionV1Protocol.buildFrame() / wrap().
     */
    private fun buildV1Frame(canId: Int, data: ByteArray, frameType: Byte): ByteArray {
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
        can[15] = frameType

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

    /**
     * Slow-info request for V1 wheels (WheelLog InMotionAdapter.CANMessage.getSlowData):
     * CAN ID 0x0F550114 remote frame with an all-0xFF payload. The wheel answers with
     * MAIN_INFO pages carrying the car type (authoritative model), serial number and
     * firmware version. Verified against a V9 capture: series 12/type 1 -> V9.
     */
    private fun buildV1SlowInfoFrame(): ByteArray =
        buildV1Frame(V1_CAN_SLOW_INFO, ByteArray(8) { 0xFF.toByte() }, V1_TYPE_REMOTE)

    /** Headlight on/off command for V1 wheels. */
    private fun buildV1LightFrame(on: Boolean): ByteArray =
        buildV1Frame(V1_CAN_HEADLIGHT, byteArrayOf(if (on) 0x01 else 0x00, 0, 0, 0, 0, 0, 0, 0))

    /** Horn / beep command for V1 wheels using the dedicated opcode (V8F / V8S / V10 / Glide 3).
     *  Wheels without the dedicated horn opcode silently ignore it. */
    private fun buildV1HornFrame(): ByteArray =
        buildV1Frame(V1_CAN_REMOTE_CTRL, byteArrayOf(0xB2.toByte(), 0, 0, 0, 0x11, 0, 0, 0))

    /** Software lock command for V1 wheels (sub-commands 0x03 / 0x04 of the remote-control group). */
    private fun buildV1LockFrame(locked: Boolean): ByteArray =
        buildV1Frame(
            V1_CAN_REMOTE_CTRL,
            byteArrayOf(0xB2.toByte(), 0, 0, 0, if (locked) 0x03 else 0x04, 0, 0, 0)
        )

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
        // Extended-routing packs are richer than the wheel-level snapshot. The P6
        // keeps the strict behavior (wheel realtime must not fill pack fields —
        // see InMotionP6BmsTest); the V9 preserves its historical wheel-level
        // fallback until the first pack page arrives.
        if (supportsExtendedRoutingPacks()) {
            val packs = p6Packs.toSortedMap().values.toList()
            if (packs.isNotEmpty()) return packs
            if (modelName == P6) return null
        }
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
                    cellVoltages = cells,
                    isCharging = resolveBmsChargingState(
                        if (index == 1) current else null,
                        lastKnownIsCharging
                    )
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
                    cellVoltages = cellVoltages,
                    isCharging = resolveBmsChargingState(current, lastKnownIsCharging)
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
                    cellVoltages = null,
                    isCharging = resolveBmsChargingState(
                        if (index == 0) current else null,
                        lastKnownIsCharging
                    )
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
                cellVoltages = null,
                isCharging = resolveBmsChargingState(current, lastKnownIsCharging)
            )
        )
    }

    override fun resetSession() = synchronized(parseLock) {
        v2Buffer.clear()
        legacyBuffer.clear()
        lastV2Chunk = null
        lastV2ChunkSnapshot = null
        lastV2Frames = emptyList()
        p6Packs = emptyMap()
        lastDetectedDialect = Dialect.UNKNOWN
        lastKnownIsCharging = null
        controlAuthToken = null
        controlAuthVerified = false
        _securityStateFlow.value = WheelSecurityState(authentication = AuthenticationState.UNKNOWN)
        modelName = manufacturer
        serialNumber = null
        firmwareVersion = null
        totalDistanceKm = null
        v2SessionStartTimestampMs = null
        hasSeenV2MainInfo = false
        hasSeenV2Realtime = false
        hasSeenLegacyRealtime = false
        lastKnownBmsSnapshot = InMotionBmsSnapshot()
        totalRideTimeSeconds = null
        totalPowerOnTimeSeconds = null
        lastP6StatsQueryAtMs = 0L
        while (_writeChannel.tryReceive().isSuccess) { /* discard previous-session writes */
        }
        while (_channel.tryReceive().isSuccess) { /* discard previous-session telemetry */
        }
    }

    /** Currently latched model name (from V2 main-info or legacy identification frames). */
    fun lastDecodedModel(): String = modelName

    /** Currently latched firmware version, from MAIN_INFO pages or identity bundles. */
    fun lastDecodedFirmware(): String? = firmwareVersion

    override fun close() {
        // No resources to clean up
    }
}
