package com.euc.ble

import com.euc.ble.analysis.BleFrequencyAnalysisTest
import com.euc.ble.core.ByteUtilsSafeAccessTest
import com.euc.ble.core.ByteUtilsTest
import com.euc.ble.frames.GotwayFrameReassemblerTest
import com.euc.ble.models.EUCDataTest
import com.euc.ble.protocols.GotwayProtocolTest
import com.euc.ble.protocols.InMotionProtocolTest
import com.euc.ble.protocols.KingsongProtocolAsyncTest
import com.euc.ble.protocols.LeaperkimProtocolTest
import com.euc.ble.protocols.NinebotProtocolTest
import com.euc.ble.protocols.NosfetProtocolTest
import com.euc.ble.protocols.WheelLogGotwayTest
import com.euc.ble.protocols.WheelLogInMotionTest
import com.euc.ble.protocols.WheelLogKingsongTest
import com.euc.ble.protocols.WheelLogLeaperkimTest
import com.euc.ble.protocols.WheelLogNinebotTest
import com.euc.ble.protocols.WheelLogNosfetTest
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite

@Suite
@SelectClasses(
    FrameReassemblerStaticFlowTest::class,
    ByteUtilsTest::class,
    ByteUtilsSafeAccessTest::class,
    EUCDataTest::class,
    GotwayProtocolTest::class,
    InMotionProtocolTest::class,
    GotwayFrameReassemblerTest::class,
    KingsongProtocolAsyncTest::class,
    BleFrequencyAnalysisTest::class,
    WheelLogGotwayTest::class,
    WheelLogInMotionTest::class,
    WheelLogNinebotTest::class,
    WheelLogLeaperkimTest::class,
    WheelLogNosfetTest::class,
    WheelLogKingsongTest::class,
    LeaperkimProtocolTest::class,
    NosfetProtocolTest::class,
    NinebotProtocolTest::class,
)

class RegularTestsSuite {
}