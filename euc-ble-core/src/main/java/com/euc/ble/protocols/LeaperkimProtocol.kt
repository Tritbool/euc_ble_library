package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.frames.ByteByByteFrameParser
import com.euc.ble.frames.FrameReassembler
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
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
import kotlinx.coroutines.runBlocking
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
open class LeaperkimProtocol : EUCProtocol {

    override val manufacturer: String = "Leaperkim"
    override val supportedModels: List<String> = listOf(
        "Patton", "Patton S", "Sherman", "Sherman S", "Sherman L",
        "Lynx", "Lynx S", "Abrams", "Oryx"
    )

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.LEAPERKIM_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.LEAPERKIM_READ_CHARACTERISTIC)
    override fun getWriteCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.LEAPERKIM_WRITE_CHARACTERISTIC)

    override open fun canHandle(device: EUCDevice): Boolean {
        val name = device.name
        return device.manufacturerId == BLEConstants.MANUFACTURER_LEAPERKIM ||
                device.manufacturerId == BLEConstants.MANUFACTURER_VETERAN ||
                name.contains("Leaper", ignoreCase = true) ||
                name.contains("Veteran", ignoreCase = true) ||
                name.contains("Patton", ignoreCase = true) ||
                name.contains("Sherman", ignoreCase = true) ||
                name.contains("Lynx", ignoreCase = true)
    }

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

    private val frameParser = ByteByByteFrameParser(unpacker, resetUnpacker = { resetUnpacker() })
    private val frameReassembler = FrameReassembler(frameParser)

    private val _channel = Channel<EUCData>(capacity = Channel.UNLIMITED)
    override val dataFlow: Flow<EUCData> = _channel.receiveAsFlow()

    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val rawFrameFlow: Flow<ByteArray> = _rawFrameFlow.asSharedFlow()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var sessionStartTimestampMs: Long? = null
    @Volatile private var lastMajorVersion: Int? = null

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
        runBlocking(Dispatchers.IO) {
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
        parseFrame(frame)?.let { _channel.trySend(it) }
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
        val pwmRaw = ByteUtils.tryGetUnsignedShortBE(frame, 34) ?: 0
        val chargeMode = ByteUtils.tryGetUnsignedShortBE(frame, 22) ?: 0
        val versionRaw = ByteUtils.tryGetUnsignedShortBE(frame, 28) ?: 0

        val voltage = voltageRaw / 100.0
        val speed = speedRaw / 100.0
        val current = currentRaw / 100.0
        val temperature = tempRaw / 100.0
        val pwm = pwmRaw / 100.0

        if (voltage !in 20.0..180.0) return null
        if (speed !in -120.0..120.0) return null
        if (current !in -300.0..300.0) return null
        if (temperature !in -50.0..130.0) return null

        val majorVersion = extractMajorVersion(versionRaw)
        lastMajorVersion = majorVersion
        val model = modelByMajorVersion(majorVersion)
        val battery = estimateBatteryPercent(voltageRaw, majorVersion)

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
            cellVoltages = null,
            motorTemperature = null,
            totalDistance = totalDistanceKm
        )
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
            CommandType.BEEP -> {
                if ((lastMajorVersion ?: 0) < 3) {
                    "b".encodeToByteArray()
                } else {
                    byteArrayOf(
                        0x4c, 0x6b, 0x41, 0x70, 0x0e, 0x00,
                        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01,
                        0xca.toByte(), 0x87.toByte(), 0xe6.toByte(), 0x6f
                    )
                }
            }
            CommandType.SET_PEDALS_MODE -> {
                when ((value as? Int)?.coerceIn(0, 2)) {
                    0 -> "SETh".encodeToByteArray()
                    1 -> "SETm".encodeToByteArray()
                    2 -> "SETs".encodeToByteArray()
                    else -> byteArrayOf()
                }
            }
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

    override fun isDeviceReady(data: EUCData): Boolean {
        return data.voltage > 40.0 && data.model.isNotBlank()
    }

    override fun close() {
        scope.cancel()
        _channel.close()
    }
}
