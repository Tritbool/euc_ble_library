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
    val cellVoltages: List<Double>?
)
