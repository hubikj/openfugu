package org.hubik.openfugu.ble

import org.hubik.openfugu.util.nowMillis

data class ScannedDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val lastSeenMs: Long = nowMillis()
)

/** Preset colors for identifying eFugu devices (hex ARGB). */
object DeviceColors {
    val presets = listOf(
        0xFFE53935.toLong(), // Red
        0xFFFF9800.toLong(), // Orange
        0xFFFFD54F.toLong(), // Yellow
        0xFF43A047.toLong(), // Green
        0xFF1E88E5.toLong(), // Blue
        0xFF8E24AA.toLong(), // Purple
        0xFFD81B60.toLong(), // Pink
        0xFF00ACC1.toLong(), // Teal
        0xFF6D4C41.toLong(), // Brown
        0xFF546E7A.toLong(), // Slate
    )
}

data class SavedDevice(
    val address: String,
    val name: String,
    val nickname: String?,
    val lastConnectedAt: Long,
    val colorArgb: Long? = null
) {
    val displayName: String get() = nickname ?: name
}

data class PressureReading(
    val pressureHPa: Double,
    val relativeHPa: Double,
    val timestamp: Long = nowMillis()
)

/** App-level scan state (not per-device) */
sealed class ScanState {
    data object Idle : ScanState()
    data object Scanning : ScanState()
    data class Error(val message: String) : ScanState()
}
