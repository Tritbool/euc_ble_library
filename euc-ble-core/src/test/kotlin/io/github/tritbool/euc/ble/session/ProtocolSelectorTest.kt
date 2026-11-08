package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.protocols.ExtremeBullProtocol
import io.github.tritbool.euc.ble.protocols.GattServiceSpec
import io.github.tritbool.euc.ble.protocols.GotwayProtocol
import io.github.tritbool.euc.ble.protocols.KingsongProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ProtocolSelectorTest {
    @Test
    fun `fingerprints use required and excluded characteristics and preserve dialect`() {
        val protocol = GotwayProtocol()
        val service = UUID(0, 1)
        val required = UUID(0, 2)
        val excluded = UUID(0, 3)
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
        val service = UUID(0, 1)
        val result = selector.byFingerprint(listOf(ProtocolSelector.Service(service, emptySet()))) {
            listOf(listOf(GattServiceSpec(service)))
        }
        assertEquals(null to 0, result)
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
