package org.hubik.openfugu.ble

import java.util.UUID

data class UserProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val minEqPressureHPa: Double? = null,
    val maxPositiveHPa: Double? = null,
    val maxNegativeHPa: Double? = null,
    val gamePressureRangeManual: Double? = null,
    val gameNegativeRangeManual: Double? = null,
    val useAutoRange: Boolean = true,
    val expertMode: Boolean = false,
    val lastCalibratedAt: Long? = null
) {
    val gamePressureRange: Double
        get() = when {
            !useAutoRange && gamePressureRangeManual != null -> gamePressureRangeManual
            useAutoRange && maxPositiveHPa != null -> maxPositiveHPa * 0.8
            else -> 40.0
        }

    val gameNegativeRange: Double
        get() = when {
            !useAutoRange && gameNegativeRangeManual != null -> gameNegativeRangeManual
            useAutoRange && maxNegativeHPa != null -> maxNegativeHPa * 0.8
            else -> 0.0
        }
}

data class DeviceUserPairing(
    val deviceAddress: String,
    val userId: String
)
