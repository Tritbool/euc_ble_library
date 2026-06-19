package io.github.tritbool.euc.ble

import io.github.tritbool.euc.ble.analysis.BleFrequencyAnalysisTest
import io.github.tritbool.euc.ble.core.ByteUtilsSafeAccessTest
import io.github.tritbool.euc.ble.core.ByteUtilsTest
import io.github.tritbool.euc.ble.frames.GotwayFrameReassemblerTest
import io.github.tritbool.euc.ble.models.EUCDataTest
import io.github.tritbool.euc.ble.protocols.GotwayNoDropTest
import io.github.tritbool.euc.ble.protocols.GotwayProtocolTest
import io.github.tritbool.euc.ble.protocols.InMotionProtocolTest
import io.github.tritbool.euc.ble.protocols.InmotionNoDropTest
import io.github.tritbool.euc.ble.protocols.KingsongNoDropTest
import io.github.tritbool.euc.ble.protocols.KingsongProtocolAsyncTest
import io.github.tritbool.euc.ble.protocols.LeaperkimNoDropTest
import io.github.tritbool.euc.ble.protocols.LeaperkimProtocolTest
import io.github.tritbool.euc.ble.protocols.NinebotNoDropTest
import io.github.tritbool.euc.ble.protocols.NinebotProtocolTest
import io.github.tritbool.euc.ble.protocols.NinebotZProtocolTest
import io.github.tritbool.euc.ble.protocols.NosfetNoDropTest
import io.github.tritbool.euc.ble.protocols.NosfetProtocolTest
import io.github.tritbool.euc.ble.protocols.WheelLogGotwayTest
import io.github.tritbool.euc.ble.protocols.WheelLogInMotionTest
import io.github.tritbool.euc.ble.protocols.WheelLogKingsongTest
import io.github.tritbool.euc.ble.protocols.WheelLogLeaperkimTest
import io.github.tritbool.euc.ble.protocols.WheelLogNinebotTest
import io.github.tritbool.euc.ble.protocols.WheelLogNosfetTest
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
    NinebotZProtocolTest::class,
    GotwayNoDropTest::class,
    InmotionNoDropTest::class,
    KingsongNoDropTest::class,
    LeaperkimNoDropTest::class,
    NosfetNoDropTest::class,
    NinebotNoDropTest::class,
    EucBleClientEntryPointWheelLogTest::class,
)
class AllTests {
}