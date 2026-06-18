package com.euc.ble

import com.euc.ble.protocols.GotwayNoDropTest
import com.euc.ble.protocols.InmotionNoDropTest
import com.euc.ble.protocols.KingsongNoDropTest
import com.euc.ble.protocols.LeaperkimNoDropTest
import com.euc.ble.protocols.NinebotNoDropTest
import com.euc.ble.protocols.NosfetNoDropTest
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite

@Suite
@SelectClasses(
    GotwayNoDropTest::class,
    InmotionNoDropTest::class,
    KingsongNoDropTest::class,
    LeaperkimNoDropTest::class,
    NosfetNoDropTest::class,
    NinebotNoDropTest::class,
    EucBleClientEntryPointWheelLogTest::class,
)

class NoDropTestsSuite {
}