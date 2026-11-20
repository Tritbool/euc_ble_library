/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 0.1.3 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.EucUuid
import io.github.tritbool.euc.ble.protocols.ProtocolWriteType

/**
 * Synchronous BLE stack boundary. Boolean results mean stack acceptance, not wheel confirmation.
 * The connector feeds connection, discovery, notification and write-ack callbacks into its driver.
 */
interface BleTransport {
    fun connect(address: String): Boolean
    fun disconnect()
    fun discoverServices(): Boolean
    fun hasCharacteristic(uuid: EucUuid): Boolean
    fun subscribe(uuid: EucUuid): Boolean
    fun write(uuid: EucUuid, bytes: ByteArray, preferredType: ProtocolWriteType): Boolean
}
