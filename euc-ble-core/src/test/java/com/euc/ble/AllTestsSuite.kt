package com.euc.ble

import com.euc.ble.core.ByteUtilsSafeAccessTest
import com.euc.ble.core.ByteUtilsTest
import com.euc.ble.frames.GotwayFrameReassemblerTest
import com.euc.ble.models.EUCDataTest
import com.euc.ble.protocols.GotwayNoDropTest
import com.euc.ble.protocols.GotwayProtocolTest
import com.euc.ble.protocols.InMotionProtocolTest
import com.euc.ble.protocols.InmotionNoDropTest
import com.euc.ble.protocols.KingsongNoDropTest
import com.euc.ble.protocols.KingsongProtocolAsyncTest
import com.euc.ble.protocols.ProtocolNoDropTestBase
import com.euc.ble.protocols.WheelLogGotwayTest
import com.euc.ble.protocols.WheelLogInMotionTest
import com.euc.ble.protocols.WheelLogKingsongTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Test suite that runs all unit tests for the EUC BLE library
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    FrameReassemblerStaticFlowTest::class,
    ByteUtilsTest::class,           // Core byte manipulation utilities
    ByteUtilsSafeAccessTest::class,
    EUCDataTest::class,            // Data model tests
    GotwayProtocolTest::class,     // Gotway/Begode protocol tests
    InMotionProtocolTest::class,   // InMotion V2 protocol (V9-first) tests
    GotwayFrameReassemblerTest::class,
    KingsongProtocolAsyncTest::class,
    WheelLogGotwayTest::class,     // Real Gotway BLE frames from WheelLog
    WheelLogInMotionTest::class,    // Real InMotion BLE frames from WheelLog
    GotwayNoDropTest::class,
    InmotionNoDropTest::class,
    KingsongNoDropTest::class
)
class AllTestsSuite {
    // This class remains empty, it is used only as a holder for the above annotations
}
