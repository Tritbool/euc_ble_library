/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 0.1.3 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble

import io.github.tritbool.euc.ble.core.AndroidLoggerTest
import io.github.tritbool.euc.ble.core.BLEManagerCallbackContractTest
import io.github.tritbool.euc.ble.core.BLEManagerDeviceNameSelectionTest
import io.github.tritbool.euc.ble.core.BLEManagerGattFingerprintTest
import io.github.tritbool.euc.ble.core.BLEManagerProtocolSelectionTest
import io.github.tritbool.euc.ble.core.BLEManagerTrafficTest
import io.github.tritbool.euc.ble.core.BLEManagerWriteTypeTest
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite

@Suite
@SelectClasses(
    EucBleClientSecurityTest::class,
    BLEManagerGattFingerprintTest::class,
    BLEManagerDeviceNameSelectionTest::class,
    BLEManagerProtocolSelectionTest::class,
    BLEManagerWriteTypeTest::class,
    BLEManagerTrafficTest::class,
    BLEManagerCallbackContractTest::class,
    EucBleClientEntryPointWheelLogTest::class,
    AndroidLoggerTest::class,
)
class AndroidTests {
}