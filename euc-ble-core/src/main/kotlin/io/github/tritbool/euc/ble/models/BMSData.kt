package io.github.tritbool.euc.ble.models

/**
 * Companion model for BMS (Battery Management System) summary data.
 * Provides detailed battery health information beyond what EUCData carries.
 */
data class BMSData(
    /** BMS number (1 or 2 for dual-battery wheels) */
    val bmsIndex: Int,
    /** BMS pack voltage in volts */
    val voltage: Double?,
    /** BMS current in amps (positive = discharge) */
    val current: Double?,
    /** Remaining capacity in mAh */
    val remainingCapacity: Int?,
    /** Factory/design capacity in mAh */
    val factoryCapacity: Int?,
    /** Charge cycle count */
    val cycles: Int?,
    /** Temperature probe readings in degrees Celsius */
    val temperatures: List<Double>?,
    /** Individual cell voltages in volts */
    val cellVoltages: List<Double>?,
    /**
     * Charging status of this battery pack.
     * `true` when the pack is being charged, `false` when it is not, `null` when the
     * protocol exposes neither a charging flag nor a signed BMS current.
     */
    val isCharging: Boolean? = null
)

/** Minimum negative BMS current (in amps) considered as an actual charge current. */
private const val BMS_CHARGE_CURRENT_THRESHOLD_A = 0.1

/**
 * Resolves the charging status of a battery pack.
 *
 * Wheel-reported charging state takes precedence when it is positive; otherwise the
 * sign of the BMS current is used (BMS currents are published discharge-positive, so a
 * negative current means the pack is being charged).
 */
internal fun resolveBmsChargingState(current: Double?, wheelIsCharging: Boolean?): Boolean? {
    if (wheelIsCharging == true) return true
    val chargingByCurrent = current?.let { it < -BMS_CHARGE_CURRENT_THRESHOLD_A }
    return chargingByCurrent ?: wheelIsCharging
}
