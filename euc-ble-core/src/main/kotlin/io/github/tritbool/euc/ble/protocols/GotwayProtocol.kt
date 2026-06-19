package io.github.tritbool.euc.ble.protocols

import androidx.annotation.VisibleForTesting
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.frames.FixedSizeFrameParser
import io.github.tritbool.euc.ble.frames.FrameReassembler
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.EUCDevice
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
import kotlin.math.abs

/**
 * Gotway EUC Protocol Implementation
 * Supports Gotway series electric unicycles
 */


/**
 * Gotway/Begode reverse-engineered protocol (updated)
 *
 * This comment summarizes the observed variants:
 *  - Raw "A" / "B" frames (controller serial stream, header 0x55 0xAA)
 *  - Short "legacy" packets (e.g., 0x01 / 0x02) re-emitted by some adapters
 *  - Type 0xA5 packets (compressed commands/status emitted by firmware/adapters)
 *
 * General observations:
 *  - Raw A/B frames observed on the serial port typically use header 0x55 0xAA
 *    and Big Endian (BE) fields for 16/32 bit integers.
 *  - Legacy packets (0x01/0x02) and some 0xA5 packets often use Little Endian (LE)
 *    encoding and a more compact format.
 *  - Current/temperature fields can be signed; must be converted correctly.
 *  - Many firmware/adapters do not add checksum. The stream can be fragmented,
 *    delayed, or lose bytes on the BLE side (no flow control).
 *  - Some packets include BMS cell voltages at the end, encoded as pairs of 2 bytes (LE)
 *    or in mV/centivolts depending on the variant.
 *
 * Example (observed):
 *   A: 55 AA 19 F0 00 00 00 00 00 00 01 2C FD CA 00 01 FF F8 00 18 5A 5A 5A 5A
 *   B: 55 AA 00 0A 4A 12 48 00 1C 20 00 2A 00 03 00 07 00 08 04 18 5A 5A 5A 5A
 *
 * Summary format (adjust according to firmware/model):
 *  - Frame A (header 0x55 0xAA):
 *      Bytes 0-1:  0x55 0xAA
 *      Bytes 2-3:  BE voltage (fixed point, e.g., 1/100)
 *      Bytes 4-5:  BE speed (fixed point, e.g., 3.6 * value / 100 -> km/h)
 *      Bytes 6-9:  BE distance (uint32, meters)
 *      Bytes 10-11: BE current (signed, fixed point)
 *      Bytes 12-13: BE temperature (signed or raw MPU value)
 *      Bytes 14-17: unknown / flags
 *      Byte 18:    frame type (e.g., 0x00)
 *      Byte 19:    footer (0x18)
 *      Bytes 20-..: footer 0x5A 0x5A 0x5A 0x5A (or variants) + optional BMS trailing
 *
 *  - Frame B (header 0x55 0xAA):
 *      Bytes 2-5:  BE total distance (uint32)
 *      Byte 6:     pedals mode / alarms (nibbles)
 *      Bytes 7-12: additional unknown fields
 *      Byte 13:    LED / mode
 *      Bytes 14-17: unknown
 *      Byte 18:    frame type (e.g., 0x04)
 *      Footer same
 *
 *  - Legacy packets (e.g., 0x01 / 0x02):
 *      - Often sent by Serial->BLE adapter or alternative firmware.
 *      - LE fields, more compact formats; can represent voltage/speed/etc.
 *
 *  - 0xA5 packets:
 *      - Used for commands (LIGHT_ON/OFF, BEEP, POWER_OFF) and sometimes for compressed states.
 *        Different structure (header 0xA5 ...).
 *
 * Parsing recommendations:
 *  - Dispatch by first byte/header: 0x55 (A/B raw), 0x01/0x02 (legacy), 0xA5 (command/status),
 *    or by type byte in the frame if present.
 *  - For A/B: process integers as BE. For legacy/0xA5: try LE.
 *  - Handle fragmentation: tolerate variable sizes, ignore too short frames,
 *    attempt re-synchronization on 0x55 0xAA or adapter headers.
 *  - Dynamically extract cell voltages from the queue if present:
 *      read pairs of 2 bytes (LE) and convert to V (mV -> V or /100 -> V depending on ranges).
 *  - Correctly convert signed values (current, motor temperatures).
 *  - Stay defensive: validate plausible ranges (voltage, current, temperature).
 *
 * Why were these variants not in the old comment?
 *  - The original comment described the raw serial stream observed on a specific controller/firmware.
 *    Other firmware/adapters (Serial->BLE) re-emit or transform these bytes
 *    (different headers, different endianness) — these variants were not necessarily
 *    present during the initial reverse engineering.
 */
open class GotwayProtocol(internal val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) :
    EUCProtocol {

    companion object {
        const val FRAME_SIZE = 24
        val HEADER: ByteArray = BLEConstants.GOTWAY_FRAME_HEADER
        val FOOTER: ByteArray = BLEConstants.GOTWAY_FRAME_FOOTER
        private const val MIN_BATTERY_VOLTAGE = 52.0
        private const val MAX_BATTERY_VOLTAGE = 134.4
        private const val MAX_BMS_CELL_SLOTS = 56
    }

    private val frameParser = FixedSizeFrameParser(FRAME_SIZE, HEADER, FOOTER)
    private val frameReassembler: FrameReassembler = FrameReassembler(frameParser)
    private val _channel = Channel<EUCData>(capacity = Channel.UNLIMITED)
    override val dataFlow: Flow<EUCData> = _channel.receiveAsFlow()

    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val rawFrameFlow: Flow<ByteArray> = _rawFrameFlow.asSharedFlow()

    //private val scope = CoroutineScope(Dispatchers.IO)
    private var lastKnownVoltage: Double? = null
    private var lastKnownCurrent: Double? = null
    private var hasType1Voltage = false
    private var hasType7Current = false
    private var lastKnownSpeed = 0.0
    private var lastKnownTemperature = 0.0
    private var lastKnownTripDistance = 0.0
    private var lastKnownTotalDistance: Double? = null
    private var lastKnownMotorTemperature: Double? = null
    private var lastKnownPwm: Double? = null
    private var lastKnownModel: String? = null
    private var lastKnownFirmwareVersion: String? = null
    private var gotwayFirmwareVariant: String? = null
    private var useHwPwm = false
    private val smartBmsCellPages: MutableMap<Int, DoubleArray> = mutableMapOf()

    init {
        // Start observing frames asynchronously
        scope.launch {
            frameReassembler.observeFrames().collectLatest { frame ->
                processFrame(frame)
            }
        }
    }

    override val manufacturer: String = "Gotway"
    override val supportedModels: List<String> = listOf(
        "MSuper", "MSX", "MSX Pro", "Mten3", "Mten4", "MTen5",
        "Nikola", "Nikola Plus", "Tesla", "Monster", "Monster Pro",
        "Begode", "Begode RS", "Begode Master", "Begode Hero", "Master Pro",
        "blitz", "blitz pro", "mten3", "mten4", "mten5", "A1", "A2", "race",
        "Extreme"
    )
    override val supportedCommandTypes: Set<CommandType> = setOf(
        CommandType.LIGHT_ON,
        CommandType.LIGHT_OFF,
        CommandType.BEEP,
        CommandType.POWER_OFF,
        CommandType.LIGHT_BRIGHTNESS,
        CommandType.REQUEST_SERIAL,
        CommandType.REQUEST_FIRMWARE
    )

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.GOTWAY_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID =
        UUID.fromString(BLEConstants.GOTWAY_READ_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        val name = device.name
        return device.manufacturerId == BLEConstants.MANUFACTURER_GOTWAY ||
                supportedModels.map { model -> model.contains(name, ignoreCase = true) }
                    .reduce { a, b -> a || b }
    }

    override fun looksLikeMyFrames(chunk: ByteArray): Boolean {
        if (chunk.size < 2) return false
        val hasHeader = (chunk[0].toInt() and 0xFF) == 0x55 && (chunk[1].toInt() and 0xFF) == 0xAA
        val hasFooter = chunk.size >= 4 &&
                (chunk[chunk.size - 4].toInt() and 0xFF) == 0x5A &&
                (chunk[chunk.size - 3].toInt() and 0xFF) == 0x5A &&
                (chunk[chunk.size - 2].toInt() and 0xFF) == 0x5A &&
                (chunk[chunk.size - 1].toInt() and 0xFF) == 0x5A
        return hasHeader || hasFooter
    }

    override fun close() {
        scope.cancel()
        smartBmsCellPages.clear()
        lastKnownVoltage = null
        lastKnownCurrent = null
        hasType1Voltage = false
        hasType7Current = false
        lastKnownSpeed = 0.0
        lastKnownTemperature = 0.0
        lastKnownTripDistance = 0.0
        lastKnownTotalDistance = null
        lastKnownMotorTemperature = null
        lastKnownPwm = null
        lastKnownModel = null
        lastKnownFirmwareVersion = null
        gotwayFirmwareVariant = null
        useHwPwm = false
        _channel.close()
    }

    override fun decode(data: ByteArray): EUCData? {
        _rawFrameFlow.tryEmit(data.clone())
        parseLegacyAsciiMetadata(data)
        // Let the reassembler handle the incoming bytes asynchronously
        scope.launch {
            frameReassembler.processIncomingBytes(data)
        }
        // Return null because data is emitted asynchronously via the dataFlow
        return null
    }

    @VisibleForTesting
    private fun processFrame(frame: ByteArray) {
        val eucData = when (frame.getOrNull(18)?.toInt()?.and(0xFF)) {
            0x00 -> parseTypeA(frame)
            0x01 -> {
                parseType1(frame)
                null
            }

            0x02 -> {
                parseType2or3(frame, bmsIndex = 0)
                null
            }

            0x03 -> {
                parseType2or3(frame, bmsIndex = 1)
                null
            }

            0x04 -> parseTypeB(frame)
            0x07 -> parseType7(frame)
            else -> null // Ignore unknown frame types from the reassembler
        }

        eucData?.let { _channel.trySend(it) }
    }

    @VisibleForTesting
    private fun parseTypeA(data: ByteArray): EUCData? {

        val speedRaw = ByteUtils.tryGetSignedShortBE(data, 4)?.toInt() ?: return null
        val speed = abs((speedRaw * 3.6) / 100.0)
        if (abs(speed) > 200.0) return null  // frame corrompue ou sentinel

        val voltageRaw = ByteUtils.tryGetUnsignedShortBE(data, 2) ?: return null
        val fallbackVoltage = voltageRaw / 100.0
        val voltage = lastKnownVoltage ?: fallbackVoltage
        if (!hasType1Voltage) {
            // Before the dedicated Type 1 battery-voltage frame is seen, keep tracking
            // voltage from Type A so Type B/Type 7 carry-forward telemetry remains usable.
            lastKnownVoltage = fallbackVoltage
        }
        if (voltage > 300.0) return null  // pareil pour voltage

        val frameVariant = ByteUtils.tryGetUnsignedByte(data, 19)
        val tripDistanceKm = when (frameVariant) {
            0x18 -> ByteUtils.tryGetUnsignedShortBE(data, 8)?.toDouble()?.div(1000.0)
            else -> ByteUtils.tryGetUnsignedIntBE(data, 6)?.toDouble()
        } ?: return null
        val currentRaw = ByteUtils.tryGetSignedShortBE(data, 10) ?: return null
        val tempRaw = ByteUtils.tryGetSignedShortBE(data, 12) ?: return null

        val currentFromTypeA = currentRaw / 100.0
        if (!hasType7Current) {
            // Before authoritative battery current from Type 7 is seen, keep Type A current
            // as the carry-forward source for Type B updates.
            lastKnownCurrent = currentFromTypeA
        }
        val current = lastKnownCurrent ?: currentFromTypeA
        val temperature = tempRaw / 100.0 // Assuming a 1/100 scale
        lastKnownSpeed = speed
        lastKnownTemperature = temperature
        val pwmFromTypeA = if (useHwPwm) {
            abs((ByteUtils.tryGetSignedShortBE(data, 14) ?: 0).toDouble())
        } else {
            abs((ByteUtils.tryGetSignedShortBE(data, 14) ?: 0).toDouble()) / 10.0
        }
        lastKnownPwm = pwmFromTypeA
        val power = voltage * current
        val batteryLevel = estimateBatteryLevel(voltage)
        lastKnownTripDistance = tripDistanceKm

        return EUCData(
            frameType = "Type A",
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = batteryLevel,
            distance = tripDistanceKm,
            power = power,
            pwm = lastKnownPwm,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = lastKnownModel ?: "Gotway (Type A)",
            serialNumber = null,
            firmwareVersion = lastKnownFirmwareVersion ?: gotwayFirmwareVariant,
            isCharging = false, // Not available in this frame
            rideTime = 0,
            cellVoltages = getCombinedCellVoltages(),
            motorTemperature = lastKnownMotorTemperature,
            totalDistance = lastKnownTotalDistance
        )
    }

    @VisibleForTesting
    private fun parseTypeB(data: ByteArray): EUCData? {
        val distanceRaw = ByteUtils.tryGetUnsignedIntBE(data, 2) ?: return null
        val settings = ByteUtils.tryGetUnsignedShortBE(data, 6)
        // Legacy WheelLog maps firmware pedals bits with "2 - raw" before exposing the
        // mode value to the app layer (typically mapped into 0..2); keep this behavior
        // for compatibility with existing consumers.
        val pedalsMode = settings?.let { 2 - ((it shr 13) and 0x03) }
        val alarmMode = settings?.let { (it shr 10) and 0x03 }
        val rollAngleMode = settings?.let { (it shr 7) and 0x03 }
        val usesMiles = settings?.let { (it and 0x01) == 1 }
        val autoPowerOffMinutes = ByteUtils.tryGetUnsignedShortBE(data, 8)
        // Legacy parser ignores values >= 100, treating them as unset/invalid in this field.
        val tiltBackSpeed = ByteUtils.tryGetUnsignedShortBE(data, 10)?.takeIf { it < 100 }
        val ledMode = ByteUtils.tryGetUnsignedByte(data, 13)
        val alertFlags = ByteUtils.tryGetUnsignedByte(data, 14)
        val lightMode = ByteUtils.tryGetUnsignedByte(data, 15)?.and(0x03)
        val wheelAlarm = alertFlags?.let { (it and 0x01) == 1 }

        lastKnownTotalDistance = distanceRaw.toDouble()
        val voltage = lastKnownVoltage ?: 0.0
        val current = lastKnownCurrent ?: 0.0
        val power = voltage * current
        val batteryLevel = estimateBatteryLevel(voltage)

        return EUCData(
            frameType = "Type B",
            speed = lastKnownSpeed,
            voltage = voltage,
            current = current,
            temperature = lastKnownTemperature,
            batteryLevel = batteryLevel,
            distance = lastKnownTripDistance,
            power = power,
            pwm = lastKnownPwm,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = lastKnownModel ?: "Gotway (Type B)",
            serialNumber = null,
            firmwareVersion = lastKnownFirmwareVersion ?: gotwayFirmwareVariant,
            isCharging = false,
            rideTime = 0,
            cellVoltages = null,
            motorTemperature = null,
            totalDistance = lastKnownTotalDistance,
            pedalsMode = pedalsMode,
            alarmMode = alarmMode,
            rollAngleMode = rollAngleMode,
            usesMiles = usesMiles,
            autoPowerOffMinutes = autoPowerOffMinutes,
            tiltBackSpeed = tiltBackSpeed,
            ledMode = ledMode,
            lightMode = lightMode,
            alertFlags = alertFlags,
            wheelAlarm = wheelAlarm
        )
    }

    @VisibleForTesting
    private fun parseType1(data: ByteArray) {
        val batteryVoltageTenth = ByteUtils.tryGetUnsignedShortBE(data, 6) ?: return
        lastKnownVoltage = batteryVoltageTenth / 10.0
        hasType1Voltage = true
    }

    @VisibleForTesting
    private fun parseType2or3(data: ByteArray, bmsIndex: Int) {
        val page = ByteUtils.tryGetUnsignedByte(data, 19) ?: return
        val cells = smartBmsCellPages.getOrPut(bmsIndex) { DoubleArray(MAX_BMS_CELL_SLOTS) }
        for (i in 0 until 8) {
            val cellRaw = ByteUtils.tryGetUnsignedShortBE(data, (i + 1) * 2) ?: continue
            val cellIndex = page * 8 + i
            if (cellIndex in cells.indices) {
                cells[cellIndex] = cellRaw / 1000.0
            }
        }
    }

    @VisibleForTesting
    private fun parseType7(data: ByteArray): EUCData? {
        val batteryCurrentRaw = ByteUtils.tryGetSignedShortBE(data, 2) ?: return null
        val motorTemperatureRaw = ByteUtils.tryGetSignedShortBE(data, 6) ?: return null
        val truePwmRaw = ByteUtils.tryGetSignedShortBE(data, 8)

        // WheelLog/Begode Type 7 convention: positive raw value means charge/regen, so
        // published battery current is inverted to match discharge-positive telemetry.
        lastKnownCurrent = (-batteryCurrentRaw) / 100.0
        hasType7Current = true
        lastKnownMotorTemperature = motorTemperatureRaw.toDouble()
        truePwmRaw?.let { raw ->
            // WheelLog maps Type 7 PWM raw values as whole-percent units (used as-is),
            // while Type A fallback values represent tenths of percent.
            val truePwm = abs(raw.toDouble())
            // WheelLog only switches to Type 7 as authoritative PWM once a non-zero value is
            // observed; keep the latest Type A fallback when Type 7 reports zero/unset.
            if (truePwm > 0.0) {
                lastKnownPwm = truePwm
            }
        }

        val voltage = lastKnownVoltage ?: 0.0
        val current = lastKnownCurrent ?: 0.0
        val power = voltage * current

        return EUCData(
            frameType = "Type 7",
            speed = lastKnownSpeed,
            voltage = voltage,
            current = current,
            temperature = lastKnownTemperature,
            batteryLevel = estimateBatteryLevel(voltage),
            distance = lastKnownTripDistance,
            power = power,
            pwm = lastKnownPwm,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = lastKnownModel ?: "Gotway (Type 7)",
            serialNumber = null,
            firmwareVersion = lastKnownFirmwareVersion ?: gotwayFirmwareVariant,
            isCharging = false,
            rideTime = 0,
            cellVoltages = getCombinedCellVoltages(),
            motorTemperature = lastKnownMotorTemperature,
            totalDistance = lastKnownTotalDistance
        )
    }

    private fun estimateBatteryLevel(voltage: Double): Int {
        if (voltage <= 0.0) return 0
        return (((voltage - MIN_BATTERY_VOLTAGE) / (MAX_BATTERY_VOLTAGE - MIN_BATTERY_VOLTAGE)) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun getCombinedCellVoltages(): List<Double>? {
        if (smartBmsCellPages.isEmpty()) return null
        val combined = smartBmsCellPages.values
            .flatMap { it.asList() }
            .filter { it > 0.0 }
        return combined.ifEmpty { null }
    }

    private fun parseLegacyAsciiMetadata(data: ByteArray) {
        if (data.isEmpty()) return
        if (data.size >= 2 && data[0] == HEADER[0] && data[1] == HEADER[1]) return
        val message = data.decodeToString().trim()
        if (message.isEmpty()) return
        when {
            message.startsWith("NAME", ignoreCase = true) -> {
                val name = message.substringAfter("NAME", "").trim()
                if (name.isNotEmpty()) {
                    lastKnownModel = name
                }
            }

            message.startsWith("GW", ignoreCase = true) -> {
                lastKnownFirmwareVersion = message.substring(2).trim().ifEmpty { null }
                gotwayFirmwareVariant = "Begode"
                useHwPwm = false
            }

            message.startsWith("JN", ignoreCase = true) -> {
                lastKnownFirmwareVersion = message.substring(2).trim().ifEmpty { null }
                gotwayFirmwareVariant = "ExtremeBull"
                useHwPwm = false
            }

            message.startsWith("CF", ignoreCase = true) -> {
                lastKnownFirmwareVersion = message.substring(2).trim().ifEmpty { null }
                gotwayFirmwareVariant = "Freestyl3r"
                useHwPwm = true
            }

            message.startsWith("BF", ignoreCase = true) -> {
                lastKnownFirmwareVersion = message.substring(2).trim().ifEmpty { null }
                gotwayFirmwareVariant = "SV"
                useHwPwm = true
            }
        }
    }

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        val header = BLEConstants.GOTWAY_COMMAND_HEADER
        return when (commandType) {
            CommandType.LIGHT_ON -> header + byteArrayOf(0x01, 0x01, 0x01)
            CommandType.LIGHT_OFF -> header + byteArrayOf(0x01, 0x01, 0x00)
            CommandType.BEEP -> header + byteArrayOf(0x02, 0x01)
            CommandType.POWER_OFF -> header + byteArrayOf(0x03, 0x01)
            CommandType.LIGHT_BRIGHTNESS -> {
                if (value is Int && value in 0..100) {
                    val brightness = (value * 255 / 100).toByte()
                    header + byteArrayOf(0x04, brightness)
                } else byteArrayOf()
            }

            CommandType.REQUEST_SERIAL -> "N".encodeToByteArray()
            CommandType.REQUEST_FIRMWARE -> "V".encodeToByteArray()
            else -> byteArrayOf()
        }
    }

    override fun getPollingPlan(): ProtocolPollingPlan {
        return ProtocolPollingPlan(
            enabled = true,
            startupQueries = listOf(
                ProtocolQuerySpec(
                    id = "gotway.request-model",
                    commandType = CommandType.REQUEST_SERIAL,
                    maxRetries = 3
                ),
                ProtocolQuerySpec(
                    id = "gotway.request-firmware",
                    commandType = CommandType.REQUEST_FIRMWARE,
                    initialDelayMs = 200L,
                    maxRetries = 3
                )
            ),
            periodicQueries = emptyList()
        )
    }

    override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean {
        // Gotway responds to "N" and "V" with ASCII strings (not framed telemetry)
        if (data.isEmpty()) return false
        // If the response starts with the standard frame header 0x55 0xAA, it's telemetry not a query response
        if (data.size >= 2 && data[0] == 0x55.toByte() && data[1] == 0xAA.toByte()) return false
        // ASCII responses to name/version queries
        return when (query.commandType) {
            CommandType.REQUEST_SERIAL, CommandType.REQUEST_FIRMWARE -> true
            else -> false
        }
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        // Conservative readiness checks: p
        return data.voltage > 0 && data.speed >= 0
    }
}
