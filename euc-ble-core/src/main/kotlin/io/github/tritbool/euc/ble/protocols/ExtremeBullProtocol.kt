package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.models.EUCDevice

class ExtremeBullProtocol : GotwayProtocol() {

    override val manufacturer: String = "ExtremeBull"
    override val supportedModels: List<String> = listOf(
        "Commander", "Griffin", "commander mini", "Commander pro", "commander max", "rocket"
    )

    override fun canHandle(device: EUCDevice): Boolean {
        val name = device.name
        return device.manufacturerId == BLEConstants.MANUFACTURER_EXTREMEBULL ||
                supportedModels.map{model -> model.contains(name, ignoreCase = true)}.reduce { a,b -> a || b  }
    }

}