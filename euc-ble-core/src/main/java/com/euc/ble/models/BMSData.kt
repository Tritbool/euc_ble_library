package com.euc.ble.models

/**
 * Companion model for BMS (Battery Management System) summary data.
 * Provides detailed battery health information beyond what EUCData carries.
 */
data class BMSData(
    val bmsIndex: Int,                   // BMS number (1 or 2 for dual-battery wheels)
    val voltage: Double?,                // BMS pack voltage in volts
    val current: Double?,                // BMS current in amps (positive = discharge)
    val remainingCapacity: Int?,         // remaining capacity in mAh
    val factoryCapacity: Int?,           // factory/design capacity in mAh
    val cycles: Int?,                    // charge cycle count
    val temperatures: List<Double>?,     // temperature probe readings in °C
    val cellVoltages: List<Double>?      // individual cell voltages in volts
)
