package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.GattSignature
import java.util.UUID

/** Selection policy over plain GATT snapshots; notification setup belongs to the connector. */
class ProtocolSelector(private val protocols: List<EUCProtocol>, private val logger: Logger) {
    data class Service(val uuid: UUID, val characteristics: Set<UUID>)

    fun byFingerprint(
        services: List<Service>, signatures: (String) -> List<GattSignature>
    ): Pair<EUCProtocol?, Int> {
        val matches = protocols.mapNotNull { protocol ->
            val signature = signatures(protocol.javaClass.simpleName).firstOrNull { signature ->
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

    fun subclass(base: EUCProtocol, name: String): EUCProtocol? = unique(
        protocols.filter { it.javaClass != base.javaClass &&
            base.javaClass.isAssignableFrom(it.javaClass) && it.matchesDeviceName(name) },
        "Device name matched multiple subclass protocols; using base protocol")

    fun byName(name: String): EUCProtocol? = unique(
        protocols.filter { it.matchesDeviceName(name) },
        "Device name matched multiple protocols; manual selection required")

    private fun unique(matches: List<EUCProtocol>, ambiguity: String): EUCProtocol? {
        if (matches.size > 1) logger.warn("ProtocolSelector", ambiguity)
        return matches.singleOrNull()
    }
}
