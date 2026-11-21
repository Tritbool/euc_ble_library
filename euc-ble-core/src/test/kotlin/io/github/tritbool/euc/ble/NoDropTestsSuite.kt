/*
 *
 *  Copyright (C) 2026 Gauthier Le Bartz Lyan (Tritbool)
 *  euc_ble_library — version 1.2.0 — licence GPL-3.0
 *  https://github.com/Tritbool/euc_ble_library
 * /
 */

package io.github.tritbool.euc.ble

import io.github.tritbool.euc.ble.protocols.GotwayNoDropTest
import io.github.tritbool.euc.ble.protocols.InmotionNoDropTest
import io.github.tritbool.euc.ble.protocols.KingsongNoDropTest
import io.github.tritbool.euc.ble.protocols.LeaperkimNoDropTest
import io.github.tritbool.euc.ble.protocols.NinebotNoDropTest
import io.github.tritbool.euc.ble.protocols.NosfetNoDropTest
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
)

class NoDropTestsSuite {
}