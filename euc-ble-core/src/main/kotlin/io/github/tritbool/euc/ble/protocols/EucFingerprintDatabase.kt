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
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a02-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a03-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a2a-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                ),
                // Newer KingSong: proprietary OTA/configuration service
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002ac9-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("02f00000-0000-0000-0000-00000000fe00"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("02f00000-0000-0000-0000-00000000ff03"),
                            UUID.fromString("02f00000-0000-0000-0000-00000000ff02"),
                            UUID.fromString("02f00000-0000-0000-0000-00000000ff00"),
                            UUID.fromString("02f00000-0000-0000-0000-00000000ff01"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cba"),
                            UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb8"),
                        )
                    ),
                ),
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002b29-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002b2a-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a2a-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                ),
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("d0611e78-bbb4-4591-a5f8-487910ae4366"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("8667556c-9a37-4c91-84ed-54ee27d90049"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("9fa480e0-4967-4542-9390-d343dc5d04ae"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("af0badb1-5b99-43cd-917a-a77bc549e3cc"),
                        )
                    ),
                )
            )
        ),

        ProtocolFingerprint(
            protocolId = "GotwayProtocol",
            gattSignatures = listOf(
                // Newer Gotway/Begode: proprietary OTA service exclusive to this manufacturer
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a02-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a03-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a2a-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                ),
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002b2a-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002b29-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("f7bf3564-fb6d-4e53-88a4-5e37e0326063"),
                        )
                    ),
                ),
            )
        ),

        ProtocolFingerprint(
            protocolId = "InMotionProtocol",
            gattSignatures = listOf(
                // InMotion V1 (legacy): has the proprietary 0000ffc0 service (unique to InMotion legacy hardware)
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb"),
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb")
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb")
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff6-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff7-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff8-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fff9-0000-1000-8000-00805f9b34fb"),
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffd1-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ffd2-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ffd3-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ffd4-0000-1000-8000-00805f9b34fb"),
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffc0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffc1-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ffc2-0000-1000-8000-00805f9b34fb"),
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffb0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffb1-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ffb2-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ffb3-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ffb4-0000-1000-8000-00805f9b34fb"),
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffa0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffa1-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ffa2-0000-1000-8000-00805f9b34fb"),
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000ff90-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ff91-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ff92-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ff93-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ff94-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ff95-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ff96-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ff97-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ff98-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ff99-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000ff9a-0000-1000-8000-00805f9b34fb"),
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000fc60-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000fc64-0000-1000-8000-00805f9b34fb")
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000fe00-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fe03-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fe04-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fe05-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fe06-0000-1000-8000-00805f9b34fb"),
                        )
                    ),

                    ),
                // InMotion V2 (modern): Nordic UART service + 00002aa6 characteristic in 00001800 (Central Address Resolution)
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        version = 2,
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002aa6-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
                        version = 2,
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                        version = 2,
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
                        )
                    ),
                ),
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        version = 2,
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002aa6-0000-1000-8000-00805f9b34fb"),
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
                        version = 2,
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                        version = 2,
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb"),
                        version = 2,
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb"),
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
                        version = 2,
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                ),

                listOf(

                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        version = 2,
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002aa6-0000-1000-8000-00805f9b34fb"),
                        )
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
                        version = 2,
                    ),

                    GattServiceSpec(
                        uuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                        version = 2,
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
                        )
                    ),
                )
                // end IM V2
            )
        ),


        ProtocolFingerprint(
            protocolId = "NinebotProtocol",
            gattSignatures = listOf(
                // NinebotZ: Nordic UART service, and 00001800 does NOT contain 00002aa6 (InMotion V2 marker)
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a02-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a03-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
                        )
                    )
                )
            )
        ),

        ProtocolFingerprint(
            protocolId = "NinebotZProtocol",
            gattSignatures = listOf(
                // NinebotZ: Nordic UART service, and 00001800 does NOT contain 00002aa6 (InMotion V2 marker)
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
                    ),

                    GattServiceSpec(uuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
                            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                        ),
                        excludedCharacteristicUUIDs = setOf(UUID.fromString("00002aa6-0000-1000-8000-00805f9b34fb"))
                    )
                ),
                listOf(
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb"),
                        )
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
                            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                        ),
                        excludedCharacteristicUUIDs = setOf(UUID.fromString("00002aa6-0000-1000-8000-00805f9b34fb"))
                    ),
                    GattServiceSpec(
                        uuid = UUID.fromString("0000fee7-0000-1000-8000-00805f9b34fb"),
                        requiredCharacteristicUUIDs = setOf(
                            UUID.fromString("0000fec8-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fec7-0000-1000-8000-00805f9b34fb"),
                            UUID.fromString("0000fec9-0000-1000-8000-00805f9b34fb"),
                        ),
                    )
                )
            )
        )
    )

    // NinebotProtocol, LeaperkimProtocol, NosfetProtocol, ExtremeBullProtocol:
    // These protocols share service UUIDs (0000ffe0) with other manufacturers and cannot be
    // uniquely identified by GATT fingerprint alone. Manual selection by the caller is required.


    /**
     * Returns the registered GATT signatures for the given protocol class simple name,
     * or an empty list if no fingerprint is registered for that protocol.
     */
    fun getSignatures(protocolSimpleName: String): List<GattSignature> {
        return entries.find { it.protocolId == protocolSimpleName }?.gattSignatures ?: emptyList()
    }
}
