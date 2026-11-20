/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 0.1.3 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble.protocols

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ExtremeBullProtocolTest {

    private val protocol = ExtremeBullProtocol()

    @AfterEach
    fun tearDown() {
        protocol.close()
    }

    @Test
    fun identifiesExtremeBullAdvertisementNamesWithoutMatchingOtherBrands() {
        assertTrue(protocol.matchesDeviceName("Extreme Bull Commander"))
        assertTrue(protocol.matchesDeviceName("ROCKET"))
        assertTrue(protocol.matchesDeviceName("Griffin"))
        assertFalse(protocol.matchesDeviceName("Gotway Master"))
        assertFalse(protocol.matchesDeviceName("Unknown EUC"))
    }

    @Test
    fun exposesExtremeBullIdentityAndGotwayBleWriteRequirements() {
        val gotway = GotwayProtocol()
        assertEquals("ExtremeBull", protocol.manufacturer)
        assertEquals(ProtocolWriteType.NO_RESPONSE, protocol.preferredWriteType)
        try {
            assertEquals(gotway.supportedCommandTypes, protocol.supportedCommandTypes)
        } finally {
            gotway.close()
        }
    }
}
