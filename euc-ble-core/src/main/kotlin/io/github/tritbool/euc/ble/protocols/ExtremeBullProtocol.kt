package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.models.EUCDevice

class ExtremeBullProtocol : GotwayProtocol() {

    override val manufacturer: String = "ExtremeBull"
    override val supportedModels: List<String> = listOf(
        "Commander", "Griffin", "commander mini", "Commander pro", "commander max", "rocket"
    )

    override fun canHandle(device: EUCDevice): Boolean {
        val metadataMatch = device.manufacturerId == BLEConstants.MANUFACTURER_EXTREMEBULL
        return metadataMatch || ProtocolMatching.hasStrongModelNameMatch(device.name, supportedModels)
    }

}