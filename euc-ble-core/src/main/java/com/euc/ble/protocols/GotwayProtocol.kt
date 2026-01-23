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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

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
    }
    private val frameParser= FixedSizeFrameParser(FRAME_SIZE, HEADER, FOOTER)
    private val frameReassembler: FrameReassembler= FrameReassembler(frameParser)
    private val _dataFlow = MutableSharedFlow<EUCData>(replay = 1)
    val dataFlow: Flow<EUCData> = _dataFlow

    private val scope = CoroutineScope(Dispatchers.IO)

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

    override fun decode(data: ByteArray): EUCData? {
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
            0x04 -> parseTypeB(frame)
            else -> null // Ignore unknown frame types from the reassembler
        }

        eucData?.let {
            scope.launch {
                _dataFlow.emit(it)
            }
        }
    }
    @VisibleForTesting
    private fun parseTypeA(data: ByteArray): EUCData? {
        val voltageRaw = ByteUtils.tryGetUnsignedShortBE(data, 2) ?: return null
        val speedRaw = ByteUtils.tryGetUnsignedShortBE(data, 4) ?: return null
        val distanceRaw = ByteUtils.tryGetUnsignedIntBE(data, 6) ?: return null
        val currentRaw = ByteUtils.tryGetSignedShortBE(data, 10) ?: return null
        val tempRaw = ByteUtils.tryGetSignedShortBE(data, 12) ?: return null

        val voltage = voltageRaw / 100.0
        val speed = (speedRaw * 3.6) / 100.0 // Convert to km/h
        val distanceMeters = distanceRaw.toDouble()
        val current = currentRaw / 100.0
        val temperature = tempRaw / 100.0 // Assuming a 1/100 scale
        val power = voltage * current

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = 0, // Not available in this frame
            distance = distanceMeters,
            power = power,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = "Gotway (Type A)",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = false, // Not available in this frame
            rideTime = 0,
            cellVoltages = null, // Not available in standard 24-byte frame
            motorTemperature = null
        )
    }
    @VisibleForTesting
    private fun parseTypeB(data: ByteArray): EUCData? {
        // Frame B primarily provides total distance. Other fields are not documented
        // and may not be present.
        val distanceRaw = ByteUtils.tryGetUnsignedIntBE(data, 2) ?: return null

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
            motorTemperature = null
        )
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
