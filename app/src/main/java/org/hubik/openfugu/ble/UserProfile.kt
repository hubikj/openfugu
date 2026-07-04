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
            // Safety: a manual range must never demand more than the auto range
            // would — games must not incentivize pressure above the calibrated
            // comfortable maximum. Uncalibrated profiles have nothing to clamp
            // against; the user detail screen warns about that case.
            !useAutoRange && gamePressureRangeManual != null ->
                maxPositiveHPa?.let { gamePressureRangeManual.coerceAtMost(it * 0.8) }
                    ?: gamePressureRangeManual
            useAutoRange && maxPositiveHPa != null -> maxPositiveHPa * 0.8
            else -> 40.0
        }

    val gameNegativeRange: Double
        get() = when {
            !useAutoRange && gameNegativeRangeManual != null ->
                maxNegativeHPa?.let { gameNegativeRangeManual.coerceAtMost(it * 0.8) }
                    ?: gameNegativeRangeManual
            useAutoRange && maxNegativeHPa != null -> maxNegativeHPa * 0.8
            else -> 0.0
        }
}

data class DeviceUserPairing(
    val deviceAddress: String,
    val userId: String
)
