package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.protocols.EucProtocolSpec
import io.github.tritbool.euc.ble.protocols.GattSignature
import io.github.tritbool.euc.ble.core.EucUuid

/** Selection policy over plain GATT snapshots; notification setup belongs to the connector. */
class ProtocolSelector<P : EucProtocolSpec>(private val protocols: List<P>, private val logger: Logger) {
    data class Service(val uuid: EucUuid, val characteristics: Set<EucUuid>)

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
