/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 1.1.0 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.core.EucUuid
import io.github.tritbool.euc.ble.core.ProtocolSelectionMode
import io.github.tritbool.euc.ble.core.ProtocolSelectionReason
import io.github.tritbool.euc.ble.protocols.ExtremeBullProtocol
import io.github.tritbool.euc.ble.protocols.GattServiceSpec
import io.github.tritbool.euc.ble.protocols.GotwayProtocol
import io.github.tritbool.euc.ble.protocols.KingsongProtocol
import io.github.tritbool.euc.ble.protocols.LeaperkimProtocol
import io.github.tritbool.euc.ble.protocols.NosfetProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProtocolSelectorTest {
    @Test
    fun `fingerprints use required and excluded characteristics and preserve dialect`() {
        val protocol = GotwayProtocol()
        val service = EucUuid.parse("00000000-0000-0000-0000-000000000001")
        val required = EucUuid.parse("00000000-0000-0000-0000-000000000002")
        val excluded = EucUuid.parse("00000000-0000-0000-0000-000000000003")
        val selector = ProtocolSelector(listOf(protocol), NoOpLogger())
        val signature = listOf(GattServiceSpec(service, requiredCharacteristicUUIDs = setOf(required),
            excludedCharacteristicUUIDs = setOf(excluded), version = 2))
        assertEquals(protocol to 2, selector.byFingerprint(
            listOf(ProtocolSelector.Service(service, setOf(required)))) { listOf(signature) })
        assertNull(selector.byFingerprint(
            listOf(ProtocolSelector.Service(service, setOf(required, excluded)))) { listOf(signature) }.first)
        assertNull(selector.byFingerprint(emptyList()) { listOf(emptyList()) }.first)
    }

    @Test
    fun `ambiguous fingerprints do not select the first protocol`() {
        val selector = ProtocolSelector(listOf(GotwayProtocol(), KingsongProtocol()), NoOpLogger())
        val service = EucUuid.parse("00000000-0000-0000-0000-000000000001")
        val result = selector.byFingerprint(listOf(ProtocolSelector.Service(service, emptySet()))) {
            listOf(listOf(GattServiceSpec(service)))
        }
        assertEquals(null to 0, result)
    }

    @Test
    fun `generic LK serial and Veteran device names resolve to Leaperkim`() {
        val leaperkim = LeaperkimProtocol()
        val nosfet = NosfetProtocol()
        val selector = ProtocolSelector(listOf(leaperkim, nosfet), NoOpLogger())
        assertSame(leaperkim, selector.byName("LK13709"))
        assertSame(leaperkim, selector.byName("Veteran Lynx"))
        assertNull(selector.byName("KS-S22"))
    }

    @Test
    fun `select auto-detects a Lynx from its generic LK name without fingerprint`() {
        val leaperkim = LeaperkimProtocol()
        val selector = ProtocolSelector(listOf(leaperkim), NoOpLogger())
        val service = EucUuid.parse("0000ffe0-0000-1000-8000-00805f9b34fb")
        val decision = selector.select(
            "LK13709",
            listOf(ProtocolSelector.Service(service, emptySet())),
            ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK, null
        ) { emptyList() }
        assertSame(leaperkim, decision.protocol)
        assertEquals(ProtocolSelectionReason.AUTO_DEVICE_NAME, decision.reason)
    }

    @Test
    fun `specific subclass refines fingerprint without Android`() {
        val base = GotwayProtocol()
        val bull = ExtremeBullProtocol()
        val selector = ProtocolSelector(listOf(base, bull), NoOpLogger())
        assertSame(bull, selector.subclass(base, "Extreme Bull"))
        assertNull(selector.subclass(base, "Gotway"))
    }
}
