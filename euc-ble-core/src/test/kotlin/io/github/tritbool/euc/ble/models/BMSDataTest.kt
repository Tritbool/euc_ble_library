package io.github.tritbool.euc.ble.models

import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertFalse
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertNull
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the BMSData model and its charging-status resolution.
 */
class BMSDataTest {

    private fun bmsData(current: Double?, isCharging: Boolean?) = BMSData(
        bmsIndex = 1,
        voltage = 84.0,
        current = current,
        remainingCapacity = null,
        factoryCapacity = null,
        cycles = null,
        temperatures = null,
        cellVoltages = null,
        isCharging = isCharging
    )

    @Test
    fun chargingStatusDefaultsToNull() {
        val data = BMSData(
            bmsIndex = 1,
            voltage = 84.0,
            current = 1.0,
            remainingCapacity = null,
            factoryCapacity = null,
            cycles = null,
            temperatures = null,
            cellVoltages = null
        )
        assertNull(data.isCharging)
    }

    @Test
    fun chargingStatusIsCarriedByTheModel() {
        assertTrue(bmsData(current = -3.0, isCharging = true).isCharging == true)
        assertFalse(bmsData(current = 3.0, isCharging = false).isCharging == true)
    }

    @Test
    fun wheelChargingFlagWins() {
        assertTrue(resolveBmsChargingState(current = 5.0, wheelIsCharging = true) == true)
    }

    @Test
    fun negativeBmsCurrentMeansCharging() {
        assertTrue(resolveBmsChargingState(current = -2.5, wheelIsCharging = false) == true)
        assertTrue(resolveBmsChargingState(current = -2.5, wheelIsCharging = null) == true)
    }

    @Test
    fun positiveOrNearZeroBmsCurrentMeansNotCharging() {
        assertFalse(resolveBmsChargingState(current = 12.0, wheelIsCharging = null) == true)
        assertFalse(resolveBmsChargingState(current = -0.05, wheelIsCharging = null) == true)
    }

    @Test
    fun unknownStateStaysNull() {
        assertNull(resolveBmsChargingState(current = null, wheelIsCharging = null))
    }
}
