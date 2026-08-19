package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.frames.ByteByByteFrameParser
import io.github.tritbool.euc.ble.frames.FrameReassembler
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.roundToInt

/**
 * Leaperkim/Veteran protocol implementation based on WheelLog raw frame behavior.
 *
 * Stream framing:
 * - Header: DC 5A 5C
 * - Byte 3: frame length marker (len)
 * - Full frame size: len + 4 bytes
 * - For long frames (len > 38), trailing CRC32 is expected
 */
open class LeaperkimProtocol(internal val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) :
    EUCProtocol {
    companion object {
        private const val LEAPERKIM_MAX_BMS_CELLS = 42
        private val LKAP = byteArrayOf(0x4C, 0x6B, 0x41, 0x70)  // "LkAp"
        private val LDAP = byteArrayOf(0x4C, 0x64, 0x41, 0x70)  // "LdAp"
    }

    override val manufacturer: String = "Leaperkim"
    override val supportedCommandTypes: Set<CommandType> = setOf(
        CommandType.LIGHT_ON,
        CommandType.LIGHT_OFF,
        CommandType.SET_HIGH_BEAM,
        CommandType.BEEP,
        CommandType.LOCK,
        CommandType.UNLOCK,
        CommandType.SET_PEDALS_MODE,
        CommandType.SET_PEDAL_ANGLE,
        CommandType.SET_RIDE_MODE,
        CommandType.SET_PWM_LIMIT,
        CommandType.RESET_TRIP,
        CommandType.CUSTOM
    )

    override fun matchesDeviceName(deviceName: String): Boolean {
        val lower = deviceName.lowercase()
        return lower.contains("sherman") || lower.contains("lynx") ||
                lower.contains("patton") || lower.contains("oryx") || lower.contains("abrams")
    }

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.LEAPERKIM_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID =
        UUID.fromString(BLEConstants.LEAPERKIM_READ_CHARACTERISTIC)

    override fun getWriteCharacteristicUUID(): UUID =
        UUID.fromString(BLEConstants.LEAPERKIM_WRITE_CHARACTERISTIC)

    private enum class ParseState {
        UNKNOWN,
        LENGTH_SEARCH,
        COLLECTING
    }

    private var parseState: ParseState = ParseState.UNKNOWN
    private var old1: Int = 0
    private var old2: Int = 0
    private var expectedLen: Int = 0
    private val streamBuffer = ArrayList<Byte>()

    private val unpacker: (Byte) -> List<ByteArray> = unpacker@{ next ->
        val out = mutableListOf<ByteArray>()
        val c = next.toInt() and 0xFF
        when (parseState) {
            ParseState.COLLECTING -> {
                val currentSize = streamBuffer.size
                if ((currentSize == 22 && c != 0x00) ||
                    (currentSize == 30 && c != 0x00 && c != 0x07) ||
                    (currentSize == 23 && (c and 0xFE) != 0x00)
                ) {
                    resetUnpacker()
                    return@unpacker out
                }
                streamBuffer.add(c.toByte())
                if (currentSize == expectedLen + 3) {
                    out.add(streamBuffer.toByteArray())
                    resetUnpacker()
                }
            }

            ParseState.LENGTH_SEARCH -> {
                streamBuffer.add(c.toByte())
                expectedLen = c
                parseState = ParseState.COLLECTING
                old2 = old1
                old1 = c
            }

            ParseState.UNKNOWN -> {
                if (c == 0x5C && old1 == 0x5A && old2 == 0xDC) {
                    streamBuffer.clear()
                    streamBuffer.add(0xDC.toByte())
                    streamBuffer.add(0x5A.toByte())
                    streamBuffer.add(0x5C.toByte())
                    parseState = ParseState.LENGTH_SEARCH
                } else if (c == 0x5A && old1 == 0xDC) {
                    old2 = old1
                } else {
                    old2 = 0
                }
                old1 = c
            }
        }
        out
    }

    private fun resetUnpacker() {
        old1 = 0
        old2 = 0
        expectedLen = 0
        parseState = ParseState.UNKNOWN
        streamBuffer.clear()
    }

    @Volatile
    var debugFramesObserved: Int = 0
        private set

    @Volatile
    var debugFramesParsed: Int = 0
        private set

    @Volatile
    var debugFramesSent: Int = 0
        private set

    @Volatile
    var debugSendFailures: Int = 0
        private set

    private val frameParser = ByteByByteFrameParser(unpacker, resetUnpacker = { resetUnpacker() })
    private val frameReassembler = FrameReassembler(frameParser)

    private val _channel = Channel<EUCData>(capacity = Channel.UNLIMITED)
    override val dataFlow: Flow<EUCData> = _channel.receiveAsFlow()

    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val rawFrameFlow: Flow<ByteArray> = _rawFrameFlow.asSharedFlow()

    //private val scope = CoroutineScope(Dispatchers.IO)
    private var sessionStartTimestampMs: Long? = null

    @Volatile
    private var lastMajorVersion: Int? = null
    // Last Oryx BMS state-of-charge from a page-2 sub-frame (frame[50]).
    // Cached and stamped onto every EUCData so battery doesn't flicker back to the
    // voltage-curve estimate on the other 8 pages (mVer 8 / Oryx only).
    @Volatile
    private var lastOryxBatterySoc: Int = -1
    private val bmsCellPages: MutableMap<Int, DoubleArray> = mutableMapOf()
    private val bmsTemperatures: MutableMap<Int, List<Double>> = mutableMapOf()
    private val bmsCurrents: MutableMap<Int, Double> = mutableMapOf()

    init {
        scope.launch {
            frameReassembler.observeFrames().collectLatest { frame ->
                processFrame(frame)
            }
        }
    }

    override fun decode(data: ByteArray): EUCData? {
        if (data.isEmpty()) return null
        _rawFrameFlow.tryEmit(data.clone())
        updateLastKnownVersionFromRawChunk(data)
        scope.launch {
            frameReassembler.processIncomingBytes(data)
        }
        return null
    }

    private fun updateLastKnownVersionFromRawChunk(data: ByteArray) {
        if (data.size < 30) return
        if (data[0] != 0xDC.toByte() || data[1] != 0x5A.toByte() || data[2] != 0x5C.toByte()) return

        val len = ByteUtils.getUnsignedByte(data, 3)
        if (data.size != len + 4) return
        // WheelLog-compatible Leaperkim frames longer than 38 bytes carry trailing CRC32.
        if (len > 38 && !isCrcValid(data, len)) return

        val versionRaw = ByteUtils.tryGetUnsignedShortBE(data, 28) ?: return
        if (versionRaw > 0) {
            lastMajorVersion = extractMajorVersion(versionRaw)
        }
    }

    private fun processFrame(frame: ByteArray) {
        debugFramesObserved++
        // Smart-BMS equipped wheels (Lynx S, Patton, Oryx) cycle through four frame layouts.
        // Frames with LEN == 0x5F carry BMS ADC readings at bytes 4..5, NOT pack voltage.
        // Parsing them as regular telemetry produces ~3–7 V voltage spikes every ~200 ms.
        // Skip the telemetry parse and only extract BMS cell data from these frames.
        if (frame.size > 3 && frame[3] == 0x5F.toByte()) {
            if (frame.size >= 47) parseSmartBms(frame)
            return
        }
        val parsed = parseFrame(frame)
        if (parsed != null) {
            debugFramesParsed++
            val result = _channel.trySend(parsed)
            if (result.isSuccess) {
                debugFramesSent++
            } else {
                debugSendFailures++
            }
        }
    }

    private fun parseFrame(frame: ByteArray): EUCData? {
        if (frame.size < 36) return null
        if (frame[0] != 0xDC.toByte() || frame[1] != 0x5A.toByte() || frame[2] != 0x5C.toByte()) return null

        val len = ByteUtils.getUnsignedByte(frame, 3)
        if (frame.size != len + 4) return null
        // WheelLog-compatible Leaperkim frames longer than 38 bytes carry trailing CRC32.
        if (len > 38 && !isCrcValid(frame, len)) return null

        val voltageRaw = ByteUtils.tryGetUnsignedShortBE(frame, 4) ?: return null
        val speedRaw = ByteUtils.tryGetSignedShortBE(frame, 6) ?: return null
        val distanceRaw = ByteUtils.tryGetUnsignedIntLE(frame, 8) ?: return null
        val totalDistanceRaw = ByteUtils.tryGetUnsignedIntLE(frame, 12) ?: return null
        val currentRaw = ByteUtils.tryGetSignedShortBE(frame, 16) ?: return null
        val tempRaw = ByteUtils.tryGetSignedShortBE(frame, 18) ?: return null
        val angleRaw = ByteUtils.tryGetSignedShortBE(frame, 32)
        val pwmRaw = ByteUtils.tryGetUnsignedShortBE(frame, 34) ?: 0
        val chargeMode = ByteUtils.tryGetUnsignedShortBE(frame, 22) ?: 0
        val autoOffSeconds = ByteUtils.tryGetUnsignedShortBE(frame, 20) ?: 0
        val speedAlertRaw = ByteUtils.tryGetUnsignedShortBE(frame, 24) ?: 0
        val speedTiltBackRaw = ByteUtils.tryGetUnsignedShortBE(frame, 26) ?: 0
        val pedalsMode = ByteUtils.tryGetUnsignedShortBE(frame, 30)
        val versionRaw = ByteUtils.tryGetUnsignedShortBE(frame, 28) ?: 0

        val voltage = voltageRaw / 100.0
        val speed = speedRaw / 100.0
        val current = currentRaw / 100.0
        val temperature = tempRaw / 100.0
        val pwm = pwmRaw / 100.0
        val angle = angleRaw?.let { it / 100.0 }

        if (voltage !in 20.0..180.0) return null
        if (speed !in -120.0..120.0) return null
        if (current !in -300.0..300.0) return null
        if (temperature !in -50.0..130.0) return null

        val majorVersion = extractMajorVersion(versionRaw)
        lastMajorVersion = majorVersion
        if (majorVersion >= 5) {
            parseSmartBms(frame)
        }
        // Oryx (mVer 8) carries its own BMS state-of-charge at byte 50 of page-2 sub-frames
        // (page identifier at byte 46, cycling 0..8). Cache it and stamp onto every frame so
        // battery stays steady between page-2 frames instead of flickering to the voltage curve.
        val pageId = if (frame.size >= 47) ByteUtils.getUnsignedByte(frame, 46) else -1
        if (majorVersion == 8 && pageId == 2 && frame.size >= 51) {
            val soc = ByteUtils.getUnsignedByte(frame, 50)
            if (soc in 0..100) lastOryxBatterySoc = soc
        }
        val model = modelByMajorVersion(majorVersion)
        val battery = if (majorVersion == 8 && lastOryxBatterySoc in 0..100) {
            lastOryxBatterySoc
        } else {
            estimateBatteryPercent(voltageRaw, majorVersion)
        }

        val tripDistanceKm = decodeDistanceKm(distanceRaw)
        val totalDistanceKm = decodeDistanceKm(totalDistanceRaw)
        val now = System.currentTimeMillis()
        val rideTimeSeconds = deriveRideTimeSeconds(now)

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = battery,
            distance = tripDistanceKm,
            power = voltage * current,
            pwm = pwm,
            timestamp = now,
            rawData = frame,
            manufacturer = manufacturer,
            model = model,
            serialNumber = null,
            firmwareVersion = if (versionRaw > 0) formatVersion(versionRaw) else null,
            isCharging = chargeMode > 0,
            rideTime = rideTimeSeconds,
            cellVoltages = getCombinedCellVoltages(),
            motorTemperature = null,
            totalDistance = totalDistanceKm,
            angle = angle,
            pedalsMode = pedalsMode,
            autoPowerOffMinutes = autoOffSeconds.takeIf { it > 0 }?.let { it / 60 },
            tiltBackSpeed = speedTiltBackRaw.takeIf { it > 0 }?.let { it * 10 },
            alarm1Speed = speedAlertRaw.takeIf { it > 0 }?.let { it * 10 }
        )
    }

    private fun parseSmartBms(frame: ByteArray) {
        val packetNum = ByteUtils.tryGetUnsignedByte(frame, 46) ?: return
        val bmsIndex = if (packetNum < 4) 1 else 2
        val cells = bmsCellPages.getOrPut(bmsIndex) { DoubleArray(LEAPERKIM_MAX_BMS_CELLS) }
        when (packetNum) {
            0, 4 -> {
                val bms1CurrentRaw = ByteUtils.tryGetSignedShortBE(frame, 69)
                val bms2CurrentRaw = ByteUtils.tryGetSignedShortBE(frame, 71)
                bms1CurrentRaw?.let { bmsCurrents[1] = it / 100.0 }
                bms2CurrentRaw?.let { bmsCurrents[2] = it / 100.0 }
            }

            1, 5 -> {
                for (i in 0 until 15) {
                    val raw = ByteUtils.tryGetUnsignedShortBE(frame, 53 + i * 2) ?: continue
                    cells[i] = raw / 1000.0
                }
            }

            2, 6 -> {
                for (i in 0 until 15) {
                    val raw = ByteUtils.tryGetUnsignedShortBE(frame, 53 + i * 2) ?: continue
                    val index = i + 15
                    if (index in cells.indices) {
                        cells[index] = raw / 1000.0
                    }
                }
            }

            3, 7 -> {
                for (i in 0 until 12) {
                    val raw = ByteUtils.tryGetUnsignedShortBE(frame, 59 + i * 2) ?: continue
                    val index = i + 30
                    if (index in cells.indices) {
                        cells[index] = raw / 1000.0
                    }
                }
                val temps = buildList {
                    for (i in 0 until 6) {
                        val raw = ByteUtils.tryGetSignedShortBE(frame, 47 + i * 2) ?: continue
                        add(raw / 100.0)
                    }
                }
                if (temps.isNotEmpty()) {
                    bmsTemperatures[bmsIndex] = temps
                }
            }
        }
    }

    private fun getCombinedCellVoltages(): List<Double>? {
        if (bmsCellPages.isEmpty()) return null
        val combined = bmsCellPages.values
            .flatMap { it.asList() }
            .filter { it > 0.0 }
        return combined.ifEmpty { null }
    }

    override fun getBMSData(): List<BMSData> {
        val allIndices =
            (bmsCellPages.keys + bmsTemperatures.keys + bmsCurrents.keys).distinct().sorted()
        return allIndices.map { index ->
            BMSData(
                bmsIndex = index,
                voltage = null,
                current = bmsCurrents[index],
                remainingCapacity = null,
                factoryCapacity = null,
                cycles = null,
                temperatures = bmsTemperatures[index],
                cellVoltages = bmsCellPages[index]?.asList()?.filter { it > 0.0 }?.ifEmpty { null }
            )
        }
    }

    private fun isCrcValid(frame: ByteArray, len: Int): Boolean {
        if (frame.size < len + 4) return false
        val crc = CRC32()
        crc.update(frame, 0, len)
        val calc = crc.value
        val provided = ByteUtils.getUnsignedIntBE(frame, len).toLong()
        return calc == provided
    }

    private fun decodeDistanceKm(raw: Long): Double {
        val masked = raw and 0x00FF_FFFFL
        return masked / 1000.0
    }

    protected open fun modelByMajorVersion(version: Int): String {
        return when (version) {
            0, 1 -> "Sherman"
            2 -> "Abrams"
            3 -> "Sherman S"
            4 -> "Patton"
            5 -> "Lynx"
            6 -> "Sherman L"
            7 -> "Patton S"
            8 -> "Oryx"
            9 -> "Lynx S"
            else -> "Leaperkim"
        }
    }

    protected open fun estimateBatteryPercent(voltageRaw: Int, majorVersion: Int): Int {
        val battery = when (majorVersion) {
            4, 7 -> ((voltageRaw - 9600) / (12525.0 - 9600.0) * 100.0)
            5, 6, 9 -> ((voltageRaw - 11520) / (15030.0 - 11520.0) * 100.0)
            8 -> ((voltageRaw - 13886) / (17535.0 - 13886.0) * 100.0)
            else -> ((voltageRaw - 7935) / (10020.0 - 7935.0) * 100.0)
        }
        return battery.roundToInt().coerceIn(0, 100)
    }

    protected open fun extractMajorVersion(versionRaw: Int): Int = versionRaw / 1000

    protected open fun formatVersion(versionRaw: Int): String {
        val major = versionRaw / 1000
        val minor = (versionRaw % 1000) / 100
        val patch = versionRaw % 100
        return "%03d.%01d.%02d".format(major, minor, patch)
    }

    private fun deriveRideTimeSeconds(nowMs: Long): Long {
        val start = sessionStartTimestampMs ?: nowMs.also { sessionStartTimestampMs = it }
        return ((nowMs - start) / 1000L).coerceAtLeast(0L)
    }

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        return when (commandType) {
            CommandType.LIGHT_ON -> "SetLightON".encodeToByteArray()
            CommandType.LIGHT_OFF -> "SetLightOFF".encodeToByteArray()

            CommandType.SET_HIGH_BEAM -> {
                // High beam is a separate light circuit from the ASCII low-beam toggle.
                // Two vendor frames must be written back-to-back: the LkAp frame followed
                // immediately by the LdAp companion. We concatenate them here; the BLE
                // layer splits at the 20-byte ATT boundary before writing.
                val on = value as? Boolean ?: return byteArrayOf()
                buildVendorFrame(LKAP, 13, byteArrayOf(0x01, 0x80.toByte(), 0x80.toByte()), (if (on) 0x01 else 0x00).toByte()) +
                        buildVendorFrame(LDAP, 13, byteArrayOf(0x01, 0x00, 0x80.toByte()), (if (on) 0x01 else 0x00).toByte())
            }

            CommandType.BEEP -> {
                if ((lastMajorVersion ?: 0) < 3) {
                    "b".encodeToByteArray()
                } else {
                    // Modern beep is a two-frame sequence: LkAp + LdAp companion.
                    // Both frames are returned concatenated; the BLE layer splits at
                    // the 20-byte ATT boundary.
                    buildVendorFrame(LKAP, 14, byteArrayOf(0x00, 0x80.toByte(), 0x80.toByte(), 0x80.toByte()), 0x01) +
                            buildVendorFrame(LDAP, 14, byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x80.toByte()), 0x01)
                }
            }

            CommandType.LOCK -> buildLockFrame(locked = true)
            CommandType.UNLOCK -> buildLockFrame(locked = false)

            CommandType.SET_PEDALS_MODE -> {
                when ((value as? Int)?.coerceIn(0, 2)) {
                    0 -> "SETh".encodeToByteArray()
                    1 -> "SETm".encodeToByteArray()
                    2 -> "SETs".encodeToByteArray()
                    else -> byteArrayOf()
                }
            }

            CommandType.SET_PEDAL_ANGLE -> {
                // Signed tenths-of-a-degree, encoded as a signed i8.
                // Example: -36 → 0xDC (confirmed from Lynx S capture, slider -3.6°).
                val tenths = (value as? Int) ?: return byteArrayOf()
                val clamped = tenths.coerceIn(-128, 127)
                buildVendorFrame(
                    LKAP, 16,
                    byteArrayOf(0x01, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
                    clamped.toByte()
                )
            }

            CommandType.SET_RIDE_MODE -> {
                // Ride-mode scalar 0..100.
                val scalar = (value as? Int)?.coerceIn(0, 100) ?: return byteArrayOf()
                buildVendorFrame(
                    LDAP, 15,
                    byteArrayOf(0x01, 0x02, 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
                    scalar.toByte()
                )
            }

            CommandType.SET_PWM_LIMIT -> {
                // PWM percentage 0..100.
                val percent = (value as? Int)?.coerceIn(0, 100) ?: return byteArrayOf()
                buildVendorFrame(
                    LDAP, 18,
                    byteArrayOf(
                        0x01, 0x02,
                        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()
                    ),
                    percent.toByte()
                )
            }

            CommandType.RESET_TRIP -> "CLEARMETER".encodeToByteArray()
            CommandType.CUSTOM -> {
                when (value) {
                    is ByteArray -> value.clone()
                    is String -> value.encodeToByteArray()
                    else -> byteArrayOf()
                }
            }

            else -> byteArrayOf()
        }
    }

    /**
     * Build a lock/unlock command frame (25-byte LdAp vendor frame).
     *
     * The payload encodes the current wall-clock timestamp (day/hour/minute/second)
     * followed by the lock state byte. The timestamp must reflect the actual moment
     * of the write — the wheel validates freshness and rejects frozen timestamps.
     *
     * Wire format verified from a Lynx S btsnoop: twenty paired lock/unlock writes
     * all match within one second of the HCI packet timestamp.
     */
    private fun buildLockFrame(locked: Boolean): ByteArray = buildLockFrame(locked, java.util.Calendar.getInstance())

    internal fun buildLockFrame(locked: Boolean, now: java.util.Calendar): ByteArray {
        val state: Byte = if (locked) 0x01 else 0x00
        val day = now.get(java.util.Calendar.DAY_OF_MONTH).toByte()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY).toByte()
        val minute = now.get(java.util.Calendar.MINUTE).toByte()
        val second = now.get(java.util.Calendar.SECOND).toByte()
        return buildVendorFrame(
            LDAP, 25,
            byteArrayOf(
                0x00, 0x05, 0x1A, 0x06,
                day, hour, minute, second,
                0x02, 0x04, 0x0C, 0xAB.toByte(),
                state, 0x00, 0x00,
            ),
            0x00
        )
    }

    /**
     * Build a LeaperKim vendor frame.
     *
     * Layout: [magic 4] [totalLen 1] [payloadHead N] [valueByte 1] [CRC32-BE 4]
     * where totalLen = 4 + 1 + payloadHead.size + 1 + 4.
     * CRC32 covers everything from magic[0] through the last non-CRC byte.
     */
    private fun buildVendorFrame(
        magic: ByteArray,
        totalLen: Int,
        payloadHead: ByteArray,
        valueByte: Byte
    ): ByteArray {
        val out = ByteArray(totalLen)
        magic.copyInto(out, 0)
        out[4] = totalLen.toByte()
        payloadHead.copyInto(out, 5)
        out[5 + payloadHead.size] = valueByte
        val crcEnd = totalLen - 4
        val crc = CRC32().apply { update(out, 0, crcEnd) }.value.toInt()
        out[crcEnd]     = ((crc ushr 24) and 0xFF).toByte()
        out[crcEnd + 1] = ((crc ushr 16) and 0xFF).toByte()
        out[crcEnd + 2] = ((crc ushr 8)  and 0xFF).toByte()
        out[crcEnd + 3] = (crc           and 0xFF).toByte()
        return out
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        return data.voltage > 40.0 && data.model.isNotBlank()
    }

    override fun close() {
        scope.cancel()
        bmsCellPages.clear()
        bmsTemperatures.clear()
        bmsCurrents.clear()
        _channel.close()
    }
}
