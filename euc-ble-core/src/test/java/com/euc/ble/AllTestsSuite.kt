package com.euc.ble

import com.euc.ble.core.ByteUtilsTest
import com.euc.ble.models.EUCDataTest
import com.euc.ble.protocols.GotwayProtocolTest
import com.euc.ble.protocols.KingsongProtocolTest
import com.euc.ble.protocols.WheelLogGotwayTest
import com.euc.ble.protocols.WheelLogKingsongTest
import com.euc.ble.protocols.WheelLogNinebotTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Test suite that runs all unit tests for the EUC BLE library
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    ByteUtilsTest::class,           // Core byte manipulation utilities
    EUCDataTest::class,            // Data model tests
    KingsongProtocolTest::class,   // Kingsong protocol tests
    GotwayProtocolTest::class,     // Gotway/Begode protocol tests
    WheelLogKingsongTest::class,  // Real Kingsong BLE frames from WheelLog
    WheelLogNinebotTest::class,    // Real Ninebot BLE frames from WheelLog
    WheelLogGotwayTest::class      // Real Gotway BLE frames from WheelLog
)
class AllTestsSuite {
    // This class remains empty, it is used only as a holder for the above annotations
}
