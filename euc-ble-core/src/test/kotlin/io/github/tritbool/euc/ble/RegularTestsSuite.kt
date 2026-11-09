package io.github.tritbool.euc.ble

import io.github.tritbool.euc.ble.analysis.BleFrequencyAnalysisTest
import io.github.tritbool.euc.ble.core.ByteUtilsSafeAccessTest
import io.github.tritbool.euc.ble.core.ByteUtilsTest
import io.github.tritbool.euc.ble.frames.GotwayFrameReassemblerTest
import io.github.tritbool.euc.ble.models.EUCDataTest
import io.github.tritbool.euc.ble.protocols.ExtremeBullProtocolTest
import io.github.tritbool.euc.ble.protocols.GotwayProtocolTest
import io.github.tritbool.euc.ble.protocols.InMotionProtocolTest
import io.github.tritbool.euc.ble.protocols.InMotionSyncProtocolTest
import io.github.tritbool.euc.ble.protocols.KingsongProtocolAsyncTest
import io.github.tritbool.euc.ble.protocols.KingsongProtocolTest
import io.github.tritbool.euc.ble.protocols.KingsongPasswordTest
import io.github.tritbool.euc.ble.protocols.SyncTimerProtocolsTest
import io.github.tritbool.euc.ble.protocols.LeaperkimProtocolTest
import io.github.tritbool.euc.ble.protocols.LeaperkimSyncProtocolTest
import io.github.tritbool.euc.ble.protocols.NinebotProtocolTest
import io.github.tritbool.euc.ble.protocols.NinebotSyncProtocolTest
import io.github.tritbool.euc.ble.protocols.NinebotZProtocolTest
import io.github.tritbool.euc.ble.protocols.NosfetProtocolTest
import io.github.tritbool.euc.ble.protocols.ProtocolParityContractTest
import io.github.tritbool.euc.ble.protocols.WheelLogGotwayTest
import io.github.tritbool.euc.ble.protocols.WheelLogInMotionTest
import io.github.tritbool.euc.ble.protocols.WheelLogKingsongTest
import io.github.tritbool.euc.ble.protocols.WheelLogLeaperkimTest
import io.github.tritbool.euc.ble.protocols.WheelLogNinebotTest
import io.github.tritbool.euc.ble.protocols.WheelLogNosfetTest
import io.github.tritbool.euc.ble.frames.SyncFrameParsingTest
import io.github.tritbool.euc.ble.protocols.ProtocolOutboxTest
import io.github.tritbool.euc.ble.session.FlowProtocolAdapterTest
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import io.github.tritbool.euc.ble.session.PollingEngineTest
import io.github.tritbool.euc.ble.session.SessionDriversTest
import io.github.tritbool.euc.ble.session.ProtocolSelectorTest
import io.github.tritbool.euc.ble.session.ReconnectPolicyTest
import io.github.tritbool.euc.ble.core.EucUuidTest
import io.github.tritbool.euc.ble.core.EucCrc32Test

@Suite
@SelectClasses(
    PollingEngineTest::class,
    SessionDriversTest::class,
    ProtocolSelectorTest::class,
    ReconnectPolicyTest::class,
    EucUuidTest::class,
    EucCrc32Test::class,
    FlowProtocolAdapterTest::class,
    ProtocolOutboxTest::class,
    SyncFrameParsingTest::class,
    FrameReassemblerStaticFlowTest::class,
    ByteUtilsTest::class,
    ByteUtilsSafeAccessTest::class,
    EUCDataTest::class,
    GotwayProtocolTest::class,
    InMotionProtocolTest::class,
    InMotionSyncProtocolTest::class,
    GotwayFrameReassemblerTest::class,
    KingsongProtocolAsyncTest::class,
    KingsongProtocolTest::class,
    KingsongPasswordTest::class,
    SyncTimerProtocolsTest::class,
    ProtocolParityContractTest::class,
    BleFrequencyAnalysisTest::class,
    WheelLogGotwayTest::class,
    WheelLogInMotionTest::class,
    WheelLogNinebotTest::class,
    WheelLogLeaperkimTest::class,
    WheelLogNosfetTest::class,
    WheelLogKingsongTest::class,
    LeaperkimProtocolTest::class,
    LeaperkimSyncProtocolTest::class,
    NosfetProtocolTest::class,
    NinebotProtocolTest::class,
    NinebotSyncProtocolTest::class,
    NinebotZProtocolTest::class,
    ExtremeBullProtocolTest::class,
)

class RegularTestsSuite {
}