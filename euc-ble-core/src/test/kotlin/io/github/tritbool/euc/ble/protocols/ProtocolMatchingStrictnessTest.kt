package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test

class ProtocolMatchingStrictnessTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private fun device(name: String): EUCDevice {
        return EUCDevice(name = name, address = "00:11:22:33:44:55", manufacturerId = 0, rssi = -55)
    }

    @Test
    fun blankAndGenericNamesDoNotMatchProtocolsWithoutManufacturerId() {
        val protocols = listOf(
            InMotionProtocol(),
            KingsongProtocol(scope = scope),
            GotwayProtocol(scope = scope),
            LeaperkimProtocol(scope = scope)
        )
        val names = listOf("", " ", "V", "ble", "unknown", "device")

        names.forEach { name ->
            protocols.forEach { protocol ->
                assertEquals(false, protocol.canHandle(device(name)))
            }
        }
    }
}
