// File: `euc-ble-core/src/main/java/com/euc/ble/protocols/GotwayProtocol.kt`
package com.euc.ble.protocols

import androidx.annotation.VisibleForTesting
import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.frames.FixedSizeFrameParser
import com.euc.ble.frames.FrameReassembler
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.math.abs

/**
 * Gotway EUC Protocol Implementation
 * Supports Gotway series electric unicycles
 */


/**
 * Gotway/Begode reverse‑engineered protocol (mise à jour)
 *
 * Ce commentaire rassemble les variantes observées :
 *  - Trames "A" / "B" brutes (flux série du contrôleur, header 0x55 0xAA)
 *  - Paquets "legacy" courts (ex. 0x01 / 0x02) réémis par certains adaptateurs
 *  - Paquets de type 0xA5 (commandes / statuts compressés émis par firmwares/adaptateurs)
 *
 * Observations générales
 *  - Les trames brutes A/B observées sur le port série utilisent typiquement un header
 *    0x55 0xAA et des champs en Big Endian (BE) pour les entiers 16/32 bits.
 *  - Les paquets "legacy" (0x01/0x02) et certains paquets 0xA5 utilisent souvent
 *    un encodage Little Endian (LE) et un format plus compact.
 *  - Les champs courant / température peuvent être signés ; il faut convertir correctement.
 *  - Beaucoup de firmwares/adaptateurs n'ajoutent pas de checksum. Le flux peut être
 *    fragmenté, retardé, ou perdre des octets côté BLE (pas de flow control).
 *  - Certains paquets incluent, à la fin, des tensions de cellules BMS encodées
 *    en paires de 2 octets (LE) ou en mV / centi‑volts selon la variante.
 *
 * Exemple (observé)
 *   A: 55 AA 19 F0 00 00 00 00 00 00 01 2C FD CA 00 01 FF F8 00 18 5A 5A 5A 5A
 *   B: 55 AA 00 0A 4A 12 48 00 1C 20 00 2A 00 03 00 07 00 08 04 18 5A 5A 5A 5A
 *
 * Format résumé (à ajuster selon firmware / modèle) :
 *  - Frame A (header 0x55 0xAA):
 *      Bytes 0-1:  0x55 0xAA
 *      Bytes 2-3:  BE voltage (fixed point, ex: 1/100)
 *      Bytes 4-5:  BE speed (fixed point, ex: 3.6 * value / 100 -> km/h)
 *      Bytes 6-9:  BE distance (uint32, mètres)
 *      Bytes 10-11: BE current (signed, fixed point)
 *      Bytes 12-13: BE temperature (signed or raw MPU value)
 *      Bytes 14-17: inconnus / flags
 *      Byte 18:    frame type (ex. 0x00)
 *      Byte 19:    footer (0x18)
 *      Bytes 20-..: footer 0x5A 0x5A 0x5A 0x5A (ou variantes) + éventuel BMS trailing
 *
 *  - Frame B (header 0x55 0xAA):
 *      Bytes 2-5:  BE total distance (uint32)
 *      Byte 6:     pedals mode / alarms (nibbles)
 *      Bytes 7-12: champs additionnels inconnus
 *      Byte 13:    LED / mode
 *      Bytes 14-17: inconnus
 *      Byte 18:    frame type (ex. 0x04)
 *      Footer idem
 *
 *  - Paquets "legacy" (ex. 0x01 / 0x02) :
 *      - Souvent envoyés par Serial->BLE adapter ou firmwares alternatifs.
 *      - Champs en LE, formats plus compacts; peuvent représenter voltage/speed/etc.
 *
 *  - Paquets 0xA5 :
 *      - Utilisés pour commandes (LIGHT_ON/OFF, BEEP, POWER_OFF) et parfois
 *        pour états compressés. Structure différente (header 0xA5 ...).
 *
 * Recommandations de parsing
 *  - Dispatcher par premier octet / header : 0x55 (A/B raw), 0x01/0x02 (legacy),
 *    0xA5 (command/status), ou par octet type dans la trame si présent.
 *  - Pour A/B : traiter les entiers en BE. Pour legacy/0xA5 : essayer LE.
 *  - Gérer la fragmentation : tolérer tailles variables, ignorer trames trop courtes,
 *    tenter une re‑synchronisation sur 0x55 0xAA ou les headers adapter.
 *  - Extraire dynamiquement les tensions de cellules depuis la queue si présentes :
 *      lire paires de 2 octets (LE) et convertir en V (mV -> V ou /100 -> V selon plages).
 *  - Convertir correctement les valeurs signées (courant, températures moteur).
 *  - Rester défensif : valider plages plausibles (voltage, courant, température).
 *
 * Pourquoi ces variantes n'étaient pas dans l'ancien commentaire ?
 *  - Le commentaire d'origine décrit le flux série brut observé sur un contrôleur/firme
 *    donné. D'autres firmwares/adaptateurs (Serial->BLE) réémettent ou transforment
 *    ces octets (headers différents, endianness différente) — ces variantes n'étaient
 *    pas forcément présentes lors de la rétro‑ingénierie initiale.
 */
class GotwayProtocol : EUCProtocol {

    companion object{
        const val FRAME_SIZE=24
        val HEADER: ByteArray=byteArrayOf(0x55.toByte(),0xAA.toByte())
        val FOOTER: ByteArray=byteArrayOf(0x5A.toByte(),0x5A.toByte(),0x5A.toByte(),0x5A.toByte())
        private const val MIN_BATTERY_VOLTAGE = 52.0
        private const val MAX_BATTERY_VOLTAGE = 134.4
        private const val MAX_BMS_CELL_SLOTS = 56
    }
    private val frameParser= FixedSizeFrameParser(FRAME_SIZE, HEADER, FOOTER)
    private val frameReassembler: FrameReassembler= FrameReassembler(frameParser)
    private val _channel = Channel<EUCData>(capacity = Channel.UNLIMITED)
    override val dataFlow: Flow<EUCData> = _channel.receiveAsFlow()

    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val rawFrameFlow: Flow<ByteArray> = _rawFrameFlow.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastKnownVoltage: Double? = null
    private var lastKnownCurrent: Double? = null
    private var lastKnownTripDistance = 0.0
    private var lastKnownTotalDistance: Double? = null
    private var lastKnownMotorTemperature: Double? = null
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
        "Begode", "Begode RS", "Begode Master", "Begode Hero"
    )

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.GOTWAY_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.GOTWAY_READ_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        return device.manufacturerId == BLEConstants.MANUFACTURER_GOTWAY ||
                device.name.contains("Gotway", ignoreCase = true) ||
                device.name.contains("Begode", ignoreCase = true) ||
                device.name.contains("Mten", ignoreCase = true) ||
                device.name.contains("MSX", ignoreCase = true) ||
                device.name.contains("Nikola", ignoreCase = true)
    }

    override fun close() {
        scope.cancel()
        smartBmsCellPages.clear()
        lastKnownVoltage = null
        lastKnownCurrent = null
        lastKnownTripDistance = 0.0
        lastKnownTotalDistance = null
        lastKnownMotorTemperature = null
        _channel.close()
    }

    override fun decode(data: ByteArray): EUCData? {
        _rawFrameFlow.tryEmit(data.clone())
        // Let the reassembler handle the incoming bytes asynchronously
        runBlocking(Dispatchers.IO) {
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
        val speed = (speedRaw * 3.6) / 100.0
        if (abs(speed) > 200.0) return null  // frame corrompue ou sentinel

        val voltageRaw = ByteUtils.tryGetUnsignedShortBE(data, 2) ?: return null
        val fallbackVoltage = voltageRaw / 100.0
        val voltage = lastKnownVoltage ?: fallbackVoltage
        if (voltage > 300.0) return null  // pareil pour voltage

        val primaryTripDistanceKm = ByteUtils.tryGetUnsignedShortBE(data, 8)?.toDouble()?.div(1000.0)
        val legacyTripDistanceKm = (ByteUtils.tryGetUnsignedIntBE(data, 6) ?: 0L).toDouble() / 1000.0
        val tripDistanceKm = primaryTripDistanceKm ?: legacyTripDistanceKm
        val currentRaw = ByteUtils.tryGetSignedShortBE(data, 10) ?: return null
        val tempRaw = ByteUtils.tryGetSignedShortBE(data, 12) ?: return null

        val currentFromTypeA = currentRaw / 100.0
        val current = lastKnownCurrent ?: currentFromTypeA
        val temperature = tempRaw / 100.0 // Assuming a 1/100 scale
        val power = voltage * current
        val batteryLevel = estimateBatteryLevel(voltage)
        lastKnownTripDistance = tripDistanceKm

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = batteryLevel,
            distance = tripDistanceKm,
            power = power,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = "Gotway (Type A)",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = false, // Not available in this frame
            rideTime = 0,
            cellVoltages = getCombinedCellVoltages(),
            motorTemperature = lastKnownMotorTemperature,
            totalDistance = lastKnownTotalDistance
        )
    }
    @VisibleForTesting
    private fun parseTypeB(data: ByteArray): EUCData? {
        // Frame B primarily provides total distance. Other fields are not documented
        // and may not be present.
        val distanceRaw = ByteUtils.tryGetUnsignedIntBE(data, 2) ?: return null
        lastKnownTotalDistance = distanceRaw.toDouble()

        return EUCData(
            // The following are placeholders as they are not in this frame type
            speed = 0.0,
            voltage = 0.0,
            current = 0.0,
            temperature = 0.0,
            batteryLevel = 0,
            distance = distanceRaw.toDouble(),
            power = 0.0,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = "Gotway (Type B)",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = false,
            rideTime = 0,
            cellVoltages = null,
            motorTemperature = null,
            totalDistance = lastKnownTotalDistance
        )
    }
    @VisibleForTesting
    private fun parseType1(data: ByteArray) {
        val batteryVoltageTenth = ByteUtils.tryGetUnsignedShortBE(data, 6) ?: return
        lastKnownVoltage = batteryVoltageTenth / 10.0
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

        // WheelLog/Begode Type 7 convention: positive raw value means charge/regen, so
        // published battery current is inverted to match discharge-positive telemetry.
        lastKnownCurrent = (-batteryCurrentRaw) / 100.0
        lastKnownMotorTemperature = motorTemperatureRaw.toDouble()

        val voltage = lastKnownVoltage ?: 0.0
        val current = lastKnownCurrent ?: 0.0
        val power = voltage * current

        return EUCData(
            speed = 0.0,
            voltage = voltage,
            current = current,
            temperature = 0.0,
            batteryLevel = estimateBatteryLevel(voltage),
            distance = lastKnownTripDistance,
            power = power,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = "Gotway (Type 7)",
            serialNumber = null,
            firmwareVersion = null,
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

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        return when (commandType) {
            CommandType.LIGHT_ON -> byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x01)
            CommandType.LIGHT_OFF -> byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x00)
            CommandType.BEEP -> byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x02, 0x01)
            CommandType.POWER_OFF -> byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x03, 0x01)
            CommandType.LIGHT_BRIGHTNESS -> {
                if (value is Int && value in 0..100) {
                    val brightness = (value * 255 / 100).toByte()
                    byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x04, brightness)
                } else byteArrayOf()
            }
            else -> byteArrayOf()
        }
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        // Conservative readiness checks: p
        return data.voltage > 0 && data.speed >= 0
    }
}
