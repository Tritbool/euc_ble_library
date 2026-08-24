package io.github.tritbool.euc.ble.protocols

import android.util.Log
import androidx.annotation.VisibleForTesting
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.frames.FixedSizeFrameParser
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
        /** Conversion factor for wheels that have been set to imperial units by the
         *  Begode app. When the imperial flag is set in a Type-B frame the wheel
         *  transmits speed in mph and trip distance in miles on every subsequent
         *  Type-A frame; we multiply by this constant to convert back to km/h and
         *  km so all downstream consumers always receive metric values. */
        private const val MILES_TO_KM = 1.60934
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

    private var hasSeenType7Pwm = false
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

    private var lastKnownPhaseCurrent: Double? = null
    private var gotwayFirmwareVariant: String? = null
    private var useHwPwm = false
    private val smartBmsCellPages: MutableMap<Int, DoubleArray> = mutableMapOf()
    /** True when the wheel has reported imperial-units mode via bit 0 of the Type-B
     *  settings word. Latched on every Type-B frame and applied in parseTypeA to
     *  convert mph→km/h and miles→km transparently. */
    private var wheelInMiles = false

    init {
        // Start observing frames asynchronously
        scope.launch {
            frameReassembler.observeFrames().collectLatest { frame ->
                processFrame(frame)
            }
        }
    }

    override val manufacturer: String = "Gotway"
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
        lastKnownPhaseCurrent = null
        gotwayFirmwareVariant = null
        useHwPwm = false
        hasSeenType7Pwm = false
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
            // SmirnoV firmware tuning frame (tag 0xFF): discard without parsing.
            // eucplanet explicitly discards this with a log line; our else branch
            // would silently drop it too, but an explicit case improves observability.
            0xFF -> null
            else -> null // Ignore unknown frame types from the reassembler
        }

        eucData?.let { _channel.trySend(it) }
    }

    @VisibleForTesting
    private fun parseTypeA(data: ByteArray): EUCData? {

        val speedRaw = ByteUtils.tryGetSignedShortBE(data, 4)?.toInt() ?: return null
        val rawSpeedKmh = abs((speedRaw * 3.6) / 100.0)
        // Apply imperial conversion: when the Begode app is set to mph the wheel
        // transmits speed in mph on the wire even though the spec says km/h. Multiply
        // by MILES_TO_KM so all downstream consumers always receive km/h.
        val speed = if (wheelInMiles) rawSpeedKmh * MILES_TO_KM else rawSpeedKmh
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
        val rawTripDistanceKm = when (frameVariant) {
            0x18 -> ByteUtils.tryGetUnsignedShortBE(data, 8)?.toDouble()?.div(1000.0)
            else -> ByteUtils.tryGetUnsignedIntBE(data, 6)?.toDouble()?.div(1000.0)
        } ?: return null
        // Apply imperial conversion to distance as well.
        val tripDistanceKm = if (wheelInMiles) rawTripDistanceKm * MILES_TO_KM else rawTripDistanceKm
        val currentRaw = ByteUtils.tryGetSignedShortBE(data, 10) ?: return null

        // phaseCurrent: courant moteur, toujours positif (valeur absolue en A)
        val phaseCurrent = abs(currentRaw / 100.0)
        lastKnownPhaseCurrent = phaseCurrent

        // current: courant batterie, signé (négatif = régén)
        // Reste l'offset 10 comme fallback jusqu'à ce que Type 7 arrive.
        // IMPORTANT: on met à jour lastKnownCurrent SANS abs, signe préservé.
        if (!hasType7Current) {
            lastKnownCurrent = currentRaw / 100.0
        }
        val current = lastKnownCurrent ?: (currentRaw / 100.0)
        val tempRaw = ByteUtils.tryGetSignedShortBE(data, 12) ?: return null
        val temperature = decodeBoardTemperature(tempRaw)
        lastKnownSpeed = speed
        lastKnownTemperature = temperature

        val rawPwmA = ByteUtils.tryGetSignedShortBE(data, 14) ?: 0
        val pwmFromTypeA = if (useHwPwm) {
            abs(rawPwmA.toDouble())
        } else {
            abs(rawPwmA.toDouble()) / 10.0
        }

// Fallback uniquement tant qu'aucune vraie PWM Type 7 n'a été observée.
        if (!hasSeenType7Pwm) {
            lastKnownPwm = if (pwmFromTypeA in 0.0..100.0) pwmFromTypeA else null
        }

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
            model = resolveModelName(),
            serialNumber = null,
            firmwareVersion = lastKnownFirmwareVersion ?: gotwayFirmwareVariant,
            isCharging = false, // Not available in this frame
            rideTime = 0,
            cellVoltages = getCombinedCellVoltages(),
            motorTemperature = lastKnownMotorTemperature,
            totalDistance = lastKnownTotalDistance,
            phaseCurrent = lastKnownPhaseCurrent
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
        // Latch the imperial flag so parseTypeA can convert speed/distance on every
        // subsequent frame without repeating the detection logic there.
        wheelInMiles = usesMiles == true
        val autoPowerOffMinutes = ByteUtils.tryGetUnsignedShortBE(data, 8)
        // Sentinel raised from 100 to 200: modern high-speed wheels (Master Pro, XWay,
        // MSX HS) legitimately set tiltback thresholds above 100 km/h, so the old
        // `< 100` guard incorrectly discarded valid settings for those models.
        val tiltBackSpeed = ByteUtils.tryGetUnsignedShortBE(data, 10)?.takeIf { it < 200 }
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
            model = resolveModelName(),
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
            wheelAlarm = wheelAlarm,
            phaseCurrent = lastKnownPhaseCurrent
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
            // Type 7 = vraie PWM entière en %, Begode partage le PWM réel (pas calculé).[web:23][file:16]
            val truePwmAbs = kotlin.math.abs(raw.toDouble())

            // 0 < PWM ≤ 100 => valeur valide.
            if (truePwmAbs in 0.0..100.0) {
                lastKnownPwm = truePwmAbs
                hasSeenType7Pwm = true
            }
            // Sinon (0 ou hors plage) => on ne change pas lastKnownPwm, et on NE met PAS PWM à 46 % random.
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
            model = resolveModelName(),
            serialNumber = null,
            firmwareVersion = lastKnownFirmwareVersion ?: gotwayFirmwareVariant,
            isCharging = false,
            rideTime = 0,
            cellVoltages = getCombinedCellVoltages(),
            motorTemperature = lastKnownMotorTemperature,
            totalDistance = lastKnownTotalDistance,
            phaseCurrent = lastKnownPhaseCurrent
        )
    }

    private fun estimateBatteryLevel(voltage: Double): Int {
        if (voltage <= 0.0) return 0
        return (((voltage - MIN_BATTERY_VOLTAGE) / (MAX_BATTERY_VOLTAGE - MIN_BATTERY_VOLTAGE)) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun decodeBoardTemperature(tempRaw: Int): Double {
        val legacyTenths = tempRaw / 10.0
        if (legacyTenths in -40.0..125.0) return legacyTenths

        // Newer Begode boards often expose MPU-like raw temperature values.
        val mpuConverted = (tempRaw / 340.0) + 36.53
        return if (mpuConverted in -40.0..125.0) mpuConverted else legacyTenths
    }

    private fun getCombinedCellVoltages(): List<Double>? {
        if (smartBmsCellPages.isEmpty()) return null
        val combined = smartBmsCellPages.values
            .flatMap { it.asList() }
            .filter { it > 0.0 }
        return combined.ifEmpty { null }
    }

    private fun decodeBegodeEncodedName(data: ByteArray): String {
        // Format Begode 0x10+char : UNIQUEMENT valide si le paquet est
        // constitué EXCLUSIVEMENT de paires 0x10+char (hors premier byte de longueur éventuel).
        // On vérifie d'abord que tous les bytes non-nuls suivent ce pattern.
        // Un paquet "EXTREME" = 7 chars = 14 bytes de paires 0x10+char,
        // éventuellement précédés d'un byte de taille.
        val start = if (data.size % 2 != 0 && data[0].toInt().and(0xFF) == data.size - 1) 1 else 0
        val slice = data.drop(start)
        // Valider que TOUS les bytes pairs sont 0x10 et impairs sont printable ASCII
        if (slice.size < 6) return ""
        if (slice.size % 2 != 0) return ""
        for (i in slice.indices step 2) {
            val prefix = slice[i].toInt().and(0xFF)
            val char = slice[i + 1].toInt().and(0xFF)
            if (prefix != 0x10) return ""
            if (char !in 0x20..0x7E) return ""
        }
        val result = slice.filterIndexed { i, _ -> i % 2 == 1 }
            .map { it.toInt().and(0xFF).toChar() }
            .joinToString("")
            .trim()
        android.util.Log.d("GotwayASCII", "decodeBegodeEncodedName: decoded='$result'")
        return result
    }

    private fun extractMetadataMessageFromAsciiPayload(data: ByteArray): String? {
        val asciiPayload = buildString(data.size) {
            data.forEach { byte ->
                val code = byte.toInt() and 0xFF
                append(if (code in 0x20..0x7E) code.toChar() else ' ')
            }
        }

        val nameMatch =
            Regex("(?i)\\bNAME\\s*[: ]?\\s*([A-Za-z0-9][A-Za-z0-9 _.-]{0,40})").find(asciiPayload)
        if (nameMatch != null) {
            return "NAME:${nameMatch.groupValues[1].trim()}"
        }

        val firmwareMatch =
            Regex("(?i)\\b(GW|JN|CF|BF)\\s*[: ]?\\s*([A-Za-z0-9._-]{1,32})").find(asciiPayload)
        if (firmwareMatch != null) {
            val prefix = firmwareMatch.groupValues[1].uppercase()
            val version = firmwareMatch.groupValues[2].trim()
            return "$prefix$version"
        }

        return null
    }

    private fun parseLegacyAsciiMetadata(data: ByteArray) {
        if (data.isEmpty()) return

        android.util.Log.d(
            "GotwayASCII",
            "parseLegacyAsciiMetadata: rawSize=${data.size} first5=${
                data.take(5).map { "%02X".format(it) }
            }"
        )

        // Tentative 1 : décodage ASCII direct (GW/JN/CF/BF et NAME standard)
        val directMessage = data.decodeToString().trim()

        val message: String = when {
            directMessage.startsWith("NAME", ignoreCase = true) -> directMessage
            directMessage.startsWith("GW", ignoreCase = true) -> directMessage
            directMessage.startsWith("JN", ignoreCase = true) -> directMessage
            directMessage.startsWith("CF", ignoreCase = true) -> directMessage
            directMessage.startsWith("BF", ignoreCase = true) -> directMessage
            extractMetadataMessageFromAsciiPayload(data) != null ->
                extractMetadataMessageFromAsciiPayload(data)!!
            else -> {
                // Tentative 2 : décodage format Begode 0x10+char
                // Seulement si le paquet est structurellement 100% du format 0x10+char
                val begodeDecoded = decodeBegodeEncodedName(data)
                if (begodeDecoded.isNotEmpty()) "NAME$begodeDecoded"
                else {
                    android.util.Log.d(
                        "GotwayASCII",
                        "parseLegacyAsciiMetadata: UNRECOGNIZED direct='$directMessage'"
                    )
                    return
                }
            }
        }

        android.util.Log.d("GotwayASCII", "parseLegacyAsciiMetadata: PARSED message='$message'")

        when {
            message.startsWith("NAME", ignoreCase = true) -> {
                val name = message.substringAfter("NAME", "").trimStart(':', ' ').trim()
                if (name.isNotEmpty()) {
                    android.util.Log.d(
                        "GotwayASCII",
                        "parseLegacyAsciiMetadata: MODEL found name='$name'"
                    )
                    lastKnownModel = name
                }
            }

            message.startsWith("GW", ignoreCase = true) -> {
                lastKnownFirmwareVersion = message.substring(2).trim().ifEmpty { null }
                gotwayFirmwareVariant = "Begode"
                useHwPwm = false
                android.util.Log.d(
                    "GotwayASCII",
                    "parseLegacyAsciiMetadata: FIRMWARE found variant=Begode version=$lastKnownFirmwareVersion"
                )
            }

            message.startsWith("JN", ignoreCase = true) -> {
                lastKnownFirmwareVersion = message.substring(2).trim().ifEmpty { null }
                gotwayFirmwareVariant = "ExtremeBull"
                useHwPwm = false
                android.util.Log.d(
                    "GotwayASCII",
                    "parseLegacyAsciiMetadata: FIRMWARE found variant=ExtremeBull version=$lastKnownFirmwareVersion"
                )
            }

            message.startsWith("CF", ignoreCase = true) -> {
                lastKnownFirmwareVersion = message.substring(2).trim().ifEmpty { null }
                gotwayFirmwareVariant = "Freestyl3r"
                useHwPwm = true
                android.util.Log.d(
                    "GotwayASCII",
                    "parseLegacyAsciiMetadata: FIRMWARE found variant=Freestyl3r version=$lastKnownFirmwareVersion"
                )
            }

            message.startsWith("BF", ignoreCase = true) -> {
                lastKnownFirmwareVersion = message.substring(2).trim().ifEmpty { null }
                gotwayFirmwareVariant = "SV"
                useHwPwm = true
                android.util.Log.d(
                    "GotwayASCII",
                    "parseLegacyAsciiMetadata: FIRMWARE found variant=SV version=$lastKnownFirmwareVersion"
                )
            }
        }
    }

    private fun resolveModelName(): String {
        lastKnownModel?.let { return it }
        return when (gotwayFirmwareVariant) {
            "Begode" -> "Begode"
            "ExtremeBull" -> "ExtremeBull"
            else -> "Gotway"
        }
    }

    override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean {
        if (data.isEmpty()) return false

        Log.d(
            "GotwayQueryMatch",
            "matchesQueryResponse: query=${query.commandType} size=${data.size} data=${
                data.take(20).map { "%02X".format(it) }
            }"
        )

        return when (query.commandType) {
            CommandType.REQUEST_SERIAL -> {
                val direct = data.decodeToString().trim()
                if (direct.startsWith("NAME", ignoreCase = true)) {
                    android.util.Log.d(
                        "GotwayQueryMatch",
                        "matchesQueryResponse: REQUEST_SERIAL matched direct NAME"
                    )
                    return true
                }
                val embedded = extractMetadataMessageFromAsciiPayload(data)
                if (embedded?.startsWith("NAME", ignoreCase = true) == true) {
                    android.util.Log.d(
                        "GotwayQueryMatch",
                        "matchesQueryResponse: REQUEST_SERIAL matched embedded NAME"
                    )
                    return true
                }
                // Begode 0x10+char : valide seulement si le paquet est structurellement pur
                val begodeDecoded = decodeBegodeEncodedName(data)
                val matched = begodeDecoded.isNotEmpty()
                android.util.Log.d(
                    "GotwayQueryMatch",
                    "matchesQueryResponse: REQUEST_SERIAL begodeDecoded='$begodeDecoded' matched=$matched"
                )
                matched
            }

            CommandType.REQUEST_FIRMWARE -> {
                val str = data.decodeToString().trim()
                val embedded = extractMetadataMessageFromAsciiPayload(data)
                val matched = str.startsWith("GW", ignoreCase = true)
                        || str.startsWith("JN", ignoreCase = true)
                        || str.startsWith("CF", ignoreCase = true)
                        || str.startsWith("BF", ignoreCase = true)
                        || embedded?.startsWith("GW", true) == true
                        || embedded?.startsWith("JN", true) == true
                        || embedded?.startsWith("CF", true) == true
                        || embedded?.startsWith("BF", true) == true
                android.util.Log.d(
                    "GotwayQueryMatch",
                    "matchesQueryResponse: REQUEST_FIRMWARE str='$str' matched=$matched"
                )
                matched
            }

            else -> false
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
                    initialDelayMs = 1000L,      // laisser la roue finir son burst initial
                    responseTimeoutMs = 2000L,   // les roues Gotway peuvent être lentes
                    maxRetries = 5,
                    retryBackoffMs = 500L
                ),
                ProtocolQuerySpec(
                    id = "gotway.request-firmware",
                    commandType = CommandType.REQUEST_FIRMWARE,
                    initialDelayMs = 1500L,
                    responseTimeoutMs = 2000L,
                    maxRetries = 3,
                    retryBackoffMs = 500L
                )
            ),
            periodicQueries = emptyList()
        )
    }


    override fun isDeviceReady(data: EUCData): Boolean {
        // Conservative readiness checks: p
        return data.voltage > 0 && data.speed >= 0
    }

    /**
     * Returns the current BMS data snapshots for all detected battery packs.
     * Each entry represents one BMS unit (typically 1 or 2 for dual-battery wheels).
     * Data is accumulated from Type 2/3 frames which contain smart BMS cell voltage pages.
     */
    override fun getBMSData(): List<BMSData> {
        val allIndices = smartBmsCellPages.keys.distinct().sorted()
        return allIndices.map { index ->
            val cells = smartBmsCellPages[index]?.asList()?.filter { it > 0.0 }?.ifEmpty { null }
            BMSData(
                bmsIndex = index,
                voltage = null, // Voltage not available in smart BMS pages
                current = null, // Current not available in smart BMS pages
                remainingCapacity = null, // Capacity not available in smart BMS pages
                factoryCapacity = null, // Factory capacity not available in smart BMS pages
                cycles = null, // Cycle count not available in smart BMS pages
                temperatures = null, // Temperatures not available in smart BMS pages
                cellVoltages = cells
            )
        }
    }
}
