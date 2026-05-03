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
import com.euc.ble.protocols.LeaperkimNoDropTest
import com.euc.ble.protocols.LeaperkimProtocolTest
import com.euc.ble.protocols.NinebotProtocolTest
import com.euc.ble.protocols.ProtocolNoDropTestBase
import com.euc.ble.protocols.WheelLogGotwayTest
import com.euc.ble.protocols.WheelLogInMotionTest
import com.euc.ble.protocols.WheelLogKingsongTest
import com.euc.ble.protocols.WheelLogLeaperkimTest
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
    WheelLogGotwayTest::class,
    WheelLogInMotionTest::class,
    WheelLogLeaperkimTest::class,
    LeaperkimProtocolTest::class,
    NinebotProtocolTest::class,
    GotwayNoDropTest::class,
    InmotionNoDropTest::class,
    KingsongNoDropTest::class,
    LeaperkimNoDropTest::class
)
class AllTestsSuite
