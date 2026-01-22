// File: `euc-ble-core/src/main/java/com/euc/ble/protocols/GotwayProtocol.kt`
package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.core.FrameReassembler
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    init {
        // Start observing frames asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            FrameReassembler.observeFrames().collectLatest { frame ->
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
        // Let the reassembler handle the incoming bytes (async)
        CoroutineScope(Dispatchers.IO).launch {
            FrameReassembler.processIncomingBytes(data)
        }
        return null // Decoding will happen when frames are emitted
    }
    /*
        Notes:
        - Tous les accès aux octets utilisent maintenant les helpers sûrs de ByteUtils (tryGet\*),
          qui retournent null si hors bornes.
        - Les parsers restent défensifs : si un champ obligatoire manque on renvoie null.
    */
    private fun processFrame(frame: ByteArray) {
        when (frame[18].toInt() and 0xFF) { // Frame type at byte 18
            0x00 -> parseTypeA(frame)
            0x04 -> parseTypeB(frame)
            else -> parseLegacy(frame)
        }
    }

    private fun parseLegacy(data: ByteArray): EUCData? {
        val voltageRaw = ByteUtils.tryGetUnsignedShortBE(data, 2) ?: return null
        val speedRaw = ByteUtils.tryGetUnsignedShortBE(data, 4) ?: return null
        val distanceRaw = ByteUtils.tryGetUnsignedIntBE(data, 6) ?: return null
        val currentRaw = ByteUtils.tryGetSignedShortBE(data, 10) ?: return null
        val tempRaw = ByteUtils.tryGetSignedShortBE(data, 12) ?: return null
        val batteryLevel = ByteUtils.tryGetUnsignedByte(data, 13) ?: 0
        val statusByte = ByteUtils.tryGetUnsignedByte(data, 14) ?: 0

        val voltage = voltageRaw / 100.0
        val speed = speedRaw / 100.0
        val distanceMeters = distanceRaw.toDouble()        // en mètres (legacy)
        val current = currentRaw / 100.0
        val temperature = tempRaw / 100.0
        val isCharging = (statusByte and 0x01) != 0
        val power = voltage * current
        val cellVoltages = parseTrailingCellVoltages(data, startIndex = 16)

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = batteryLevel.coerceIn(0, 100),
            distance = distanceMeters,
            power = power,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = "Gotway (legacy)",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = isCharging,
            rideTime = 0,
            cellVoltages = cellVoltages,
            motorTemperature = null
        )
    }

    private fun parseTypeA(data: ByteArray): EUCData? {
        // Supporte à la fois compact (sans header) et headered ; compact often LE
        val baseOff = if ((data[0].toInt() and 0xFF) == 0x00) 0 else 0 // parsers utilisent offsets constants
        val voltageRaw = ByteUtils.tryGetUnsignedShortLE(data, 1) ?: return null
        val speedRaw = ByteUtils.tryGetUnsignedShortLE(data, 3) ?: return null
        val distanceRaw = ByteUtils.tryGetUnsignedIntLE(data, 5) ?: return null
        val currentRaw = ByteUtils.tryGetSignedShortLE(data, 9) ?: return null
        val tempRaw = ByteUtils.tryGetSignedShortLE(data, 11) ?: return null
        val batteryLevel = ByteUtils.tryGetUnsignedByte(data, 13) ?: 0
        val statusByte = ByteUtils.tryGetUnsignedByte(data, 14) ?: 0
        val motorTempRaw = ByteUtils.tryGetSignedByte(data, 15)

        val voltage = voltageRaw / 10.0
        val speed = speedRaw / 10.0
        val distanceMeters = distanceRaw.toDouble()           // conserver en *mètres*
        val current = currentRaw / 10.0
        val temperature = tempRaw / 10.0
        val isCharging = (statusByte and 0x01) != 0
        val motorTemperature = motorTempRaw?.div(10.0)
        val power = voltage * current
        val cellVoltages = parseTrailingCellVoltages(data, startIndex = 16)

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = batteryLevel.coerceIn(0, 100),
            distance = distanceMeters, // <-- mètres (align legacy)
            power = power,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = "Gotway A",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = isCharging,
            rideTime = 0,
            cellVoltages = cellVoltages,
            motorTemperature = motorTemperature
        )
    }

    private fun parseTypeB(data: ByteArray): EUCData? {
        val voltageRaw = ByteUtils.tryGetUnsignedShortLE(data, 1) ?: return null
        val speedRaw = ByteUtils.tryGetUnsignedShortLE(data, 3) ?: return null
        val distanceRaw = ByteUtils.tryGetUnsignedIntLE(data, 5) ?: return null
        val currentRaw = ByteUtils.tryGetSignedShortLE(data, 9) ?: return null
        val tempRaw = ByteUtils.tryGetSignedShortLE(data, 11) ?: return null
        val batteryLevel = ByteUtils.tryGetUnsignedByte(data, 13) ?: 0
        val statusByte = ByteUtils.tryGetUnsignedByte(data, 14) ?: 0
        val motorTempRaw = ByteUtils.tryGetSignedByte(data, 15)

        val voltage = voltageRaw / 10.0
        val speed = speedRaw / 10.0
        val distanceMeters = distanceRaw.toDouble()           // conserver en *mètres*
        val current = currentRaw / 10.0
        val temperature = tempRaw / 10.0
        val isCharging = (statusByte and 0x01) != 0
        val motorTemperature = motorTempRaw?.div(10.0)
        val power = voltage * current
        val cellVoltages = parseTrailingCellVoltages(data, startIndex = 16)

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = batteryLevel.coerceIn(0, 100),
            distance = distanceMeters, // <-- mètres (align legacy)
            power = power,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = "Gotway B",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = isCharging,
            rideTime = 0,
            cellVoltages = cellVoltages,
            motorTemperature = motorTemperature
        )
    }

    private fun parseA5Like(data: ByteArray): EUCData? {
        return if (data.size >= 16) parseLegacy(data) else null
    }

    private fun parseTrailingCellVoltages(data: ByteArray, startIndex: Int): List<Double>? {
        if (data.size <= startIndex) return null
        val remaining = data.size - startIndex
        val cellCount = remaining / 2
        if (cellCount <= 0) return null
        val cells = mutableListOf<Double>()
        var idx = startIndex
        repeat(cellCount) {
            val raw = ByteUtils.tryGetUnsignedShortLE(data, idx) ?: return@repeat
            val cellVoltage = if (raw > 1000) raw / 1000.0 else raw / 100.0
            cells.add(cellVoltage)
            idx += 2
        }
        return if (cells.isNotEmpty()) cells else null
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
        // Conservative readiness checks: positive voltage, reasonable temperature and battery
        val tempOk = (data.temperature ?: Double.MAX_VALUE) < 75.0
        val voltageOk = data.voltage > 30.0
        val batteryOk = data.batteryLevel >= 5
        return voltageOk && tempOk && batteryOk
    }
}