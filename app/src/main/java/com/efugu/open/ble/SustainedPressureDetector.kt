package com.efugu.open.ble

/**
 * Direction filter for sustained pressure detection.
 * POSITIVE: only reacts to positive pressure (Valsalva/Frenzel).
 * NEGATIVE: only reacts to negative pressure (reverse pack), reports absolute values.
 */
enum class PressureDirection { POSITIVE, NEGATIVE }

/**
 * Detects the highest sustained pressure level over a hold duration.
 *
 * Algorithm:
 * 1. Pressure in the specified [direction] crosses above threshold → start tracking
 * 2. Maintain a sliding window of [holdDurationMs] readings
 * 3. At each reading, compute rollingMin = min(readings in last [holdDurationMs])
 * 4. Track bestSustained = max(rollingMin) across the whole attempt
 * 5. Pressure drops below threshold → attempt ends, report bestSustained
 */
class SustainedPressureDetector(
    private val minThreshold: Double = 30.0,
    private val holdDurationMs: Long = 3000L,
    private val direction: PressureDirection = PressureDirection.POSITIVE
) {
    data class SustainedLevel(
        val valueHPa: Double,
        val durationMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    private data class TimestampedReading(val value: Double, val timestamp: Long)

    private var tracking = false
    private val readings = mutableListOf<TimestampedReading>()
    private var bestSustained = 0.0
    private var trackingStartMs = 0L

    fun reset() {
        tracking = false
        readings.clear()
        bestSustained = 0.0
        trackingStartMs = 0L
    }

    /**
     * Feed a new pressure reading (relative hPa).
     * Returns a SustainedLevel when an attempt ends (pressure drops below threshold), null otherwise.
     */
    fun addSample(relativeHPa: Double, timestampMs: Long = System.currentTimeMillis()): SustainedLevel? {
        // Convert to effective value based on direction:
        // POSITIVE: keep positive values, clamp negative to 0
        // NEGATIVE: negate (so negative pressure becomes positive), clamp positive-input to 0
        val effective = when (direction) {
            PressureDirection.POSITIVE -> relativeHPa.coerceAtLeast(0.0)
            PressureDirection.NEGATIVE -> (-relativeHPa).coerceAtLeast(0.0)
        }

        if (!tracking) {
            if (effective >= minThreshold) {
                tracking = true
                readings.clear()
                bestSustained = 0.0
                trackingStartMs = timestampMs
                readings.add(TimestampedReading(effective, timestampMs))
            }
            return null
        }

        // Tracking is active
        if (effective < minThreshold) {
            // Attempt ended — report result if we had a valid sustained period
            val result = if (bestSustained > 0.0) {
                SustainedLevel(
                    valueHPa = bestSustained,
                    durationMs = timestampMs - trackingStartMs
                )
            } else null
            reset()
            return result
        }

        readings.add(TimestampedReading(effective, timestampMs))

        // Trim readings outside the sliding window
        val windowStart = timestampMs - holdDurationMs
        readings.removeAll { it.timestamp < windowStart }

        // Only compute rolling min if we have a full window worth of data
        val windowSpan = readings.last().timestamp - readings.first().timestamp
        if (windowSpan >= holdDurationMs * 0.9) { // 90% to handle timing jitter
            val rollingMin = readings.minOf { it.value }
            if (rollingMin > bestSustained) {
                bestSustained = rollingMin
            }
        }

        return null
    }

    /** Whether we're currently tracking an attempt above threshold. */
    val isTracking: Boolean get() = tracking

    /** Current best sustained value in the active attempt (0.0 if none yet). */
    val currentBestSustained: Double get() = bestSustained

    /** Duration of current tracking attempt in ms. */
    fun currentTrackingDurationMs(now: Long = System.currentTimeMillis()): Long {
        return if (tracking) now - trackingStartMs else 0L
    }
}
