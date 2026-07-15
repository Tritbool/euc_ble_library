package io.github.tritbool.euc.ble.protocols

import java.util.UUID

/**
 * EUC BLE protocol fingerprint database.
 *
 * This object contains GATT service fingerprint data for each known EUC protocol,
 * analogous to WheelLog's bluetooth_services.json.
 *
 * A protocol fingerprint is a list of alternative [GattSignature] entries (OR semantics):
 * the protocol matches if at least one signature matches. Each [GattSignature] is a list
 * of [GattServiceSpec] entries that must ALL match (AND semantics).
 *
 * Protocols without a fingerprint entry cannot be auto-detected and require
 * manual selection by the caller via [io.github.tritbool.euc.ble.EucBleClient.selectProtocol]
 * or [io.github.tritbool.euc.ble.EucBleClient.forceProtocol].
 */
internal object EucFingerprintDatabase {

    data class ProtocolFingerprint(
        val protocolId: String,
        val gattSignatures: List<GattSignature>
    )

    val entries: List<ProtocolFingerprint> = listOf(

        ProtocolFingerprint(
            protocolId = "KingsongProtocol",
            gattSignatures = listOf(
                // Older KingSong: 0000fff0 service contains 0000fff2 (not present on Gotway)
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"))
                    )
                ),
                // Newer KingSong: proprietary OTA/configuration service
                listOf(
                    GattServiceSpec(uuid = UUID.fromString("02f00000-0000-0000-0000-00000000fe00"))
                )
            )
        ),

        ProtocolFingerprint(
            protocolId = "GotwayProtocol",
            gattSignatures = listOf(
                // Newer Gotway/Begode: proprietary OTA service exclusive to this manufacturer
                listOf(
                    GattServiceSpec(uuid = UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0"))
                )
            )
        ),

        ProtocolFingerprint(
            protocolId = "InMotionProtocol",
            gattSignatures = listOf(
                // InMotion V1 (legacy): has the proprietary 0000ffc0 service (unique to InMotion legacy hardware)
                listOf(
                    GattServiceSpec(uuid = UUID.fromString("0000ffc0-0000-1000-8000-00805f9b34fb"))
                ),
                // InMotion V2 (modern): Nordic UART service + 00002aa6 characteristic in 00001800 (Central Address Resolution)
                listOf(
                    GattServiceSpec(uuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(UUID.fromString("00002aa6-0000-1000-8000-00805f9b34fb"))
                    )
                )
            )
        ),

        ProtocolFingerprint(
            protocolId = "NinebotZProtocol",
            gattSignatures = listOf(
                // NinebotZ: Nordic UART service, and 00001800 does NOT contain 00002aa6 (InMotion V2 marker)
                listOf(
                    GattServiceSpec(uuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        excludedCharacteristicUUIDs = setOf(UUID.fromString("00002aa6-0000-1000-8000-00805f9b34fb"))
                    )
                )
            )
        )

        // NinebotProtocol, LeaperkimProtocol, NosfetProtocol, ExtremeBullProtocol:
        // These protocols share service UUIDs (0000ffe0) with other manufacturers and cannot be
        // uniquely identified by GATT fingerprint alone. Manual selection by the caller is required.
    )

    /**
     * Returns the registered GATT signatures for the given protocol class simple name,
     * or an empty list if no fingerprint is registered for that protocol.
     */
    fun getSignatures(protocolSimpleName: String): List<GattSignature> {
        return entries.find { it.protocolId == protocolSimpleName }?.gattSignatures ?: emptyList()
    }
}
