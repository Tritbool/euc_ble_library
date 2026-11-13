package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.protocols.EucProtocolSpec
import io.github.tritbool.euc.ble.protocols.GattSignature
import io.github.tritbool.euc.ble.core.EucUuid
import io.github.tritbool.euc.ble.core.ProtocolSelectionMode
import io.github.tritbool.euc.ble.core.ProtocolSelectionReason

/** Session-owned selection over plain GATT snapshots; notification setup belongs to the connector. */
class ProtocolSelector<P : EucProtocolSpec>(private val protocols: List<P>, private val logger: Logger) {
    data class Service(val uuid: EucUuid, val characteristics: Set<EucUuid>)
    data class Decision<P>(
        val protocol: P? = null,
        val reason: ProtocolSelectionReason? = null,
        val dialect: Int? = null,
        val manual: Boolean = false,
        val waitForTelemetry: Boolean = false
    )

    fun select(
        name: String, services: List<Service>, mode: ProtocolSelectionMode, forced: P?,
        signatures: (String) -> List<GattSignature>
    ): Decision<P> {
        if (mode == ProtocolSelectionMode.FORCED) return Decision(forced, ProtocolSelectionReason.FORCED)
        val nosfet = protocols.singleOrNull { it.protocolId == "NosfetProtocol" }
        if (nosfet != null && name.contains("nosfet", ignoreCase = true)) {
            return Decision(nosfet, ProtocolSelectionReason.AUTO_DEVICE_NAME)
        }
        val (fingerprint, dialect) = byFingerprint(services, signatures)
        if (fingerprint != null) {
            val refined = subclass(fingerprint, name)
            return Decision(refined ?: fingerprint,
                if (refined == null) ProtocolSelectionReason.AUTO_GATT_FINGERPRINT
                else ProtocolSelectionReason.AUTO_DEVICE_NAME, dialect)
        }
        byName(name)?.let { return Decision(it, ProtocolSelectionReason.AUTO_DEVICE_NAME) }
        if (mode == ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK) return Decision(manual = true)
        val characteristics = services.flatMap { it.characteristics }.toSet()
        return Decision(waitForTelemetry = nosfet != null &&
            nosfet.getCandidateDataCharacteristicUUIDs().any { it in characteristics })
    }

    fun byFingerprint(
        services: List<Service>, signatures: (String) -> List<GattSignature>
    ): Pair<P?, Int> {
        val matches = protocols.mapNotNull { protocol ->
            val signature = signatures(protocol.protocolId).firstOrNull { signature ->
                signature.isNotEmpty() && signature.all { spec ->
                    val service = services.firstOrNull { it.uuid == spec.uuid }
                    service != null && service.characteristics.containsAll(spec.requiredCharacteristicUUIDs) &&
                        spec.excludedCharacteristicUUIDs.none { it in service.characteristics }
                }
            }
            signature?.let { protocol to it.first().version }
        }
        if (matches.size > 1) logger.warn("ProtocolSelector",
            "GATT fingerprint matched multiple protocols; manual selection required")
        return matches.singleOrNull() ?: (null to 0)
    }

    fun subclass(base: P, name: String): P? = unique(
        protocols.filter { it.protocolId != base.protocolId &&
            it.parentProtocolId == base.protocolId && it.matchesDeviceName(name) },
        "Device name matched multiple subclass protocols; using base protocol")

    fun byName(name: String): P? = unique(
        protocols.filter { it.matchesDeviceName(name) },
        "Device name matched multiple protocols; manual selection required")

    private fun unique(matches: List<P>, ambiguity: String): P? {
        if (matches.size > 1) logger.warn("ProtocolSelector", ambiguity)
        return matches.singleOrNull()
    }
}
