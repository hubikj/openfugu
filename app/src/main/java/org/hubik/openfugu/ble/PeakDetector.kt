package org.hubik.openfugu.ble

/**
 * Detects pressure peaks for min EQ calibration.
 * Two-state machine: Idle → Rising → Idle.
 * Uses rolling average smoothing (5 samples = 250ms at 20Hz).
 */
class PeakDetector(
    private val minPeakAmplitude: Double = 5.0,
    private val dropThreshold: Double = 0.5,
    private val smoothingWindow: Int = 5,
    private val sampleRateHz: Int = 20
) {
    // Smoothing introduces a lag of half the window duration
    private val smoothingLagMs: Long = (smoothingWindow * 1000L) / (2 * sampleRateHz)
    data class DetectedPeak(
        val peakValueHPa: Double,
        val timestamp: Long = System.currentTimeMillis()
    )

    private enum class State { IDLE, RISING }

    private var state = State.IDLE
    private val recentSamples = ArrayDeque<Double>(smoothingWindow)
    private var currentPeak = 0.0
    private var currentPeakTimestamp = 0L
    private var needsBaselineReturn = false  // after confirming a peak, wait for signal to return to baseline

    fun reset() {
        state = State.IDLE
        recentSamples.clear()
        currentPeak = 0.0
        currentPeakTimestamp = 0L
        needsBaselineReturn = false
    }

    /**
     * Feed a new pressure reading (relative hPa).
     * Returns a DetectedPeak when a complete peak is confirmed, null otherwise.
     */
    fun addSample(relativeHPa: Double): DetectedPeak? {
        recentSamples.addLast(relativeHPa)
        if (recentSamples.size > smoothingWindow) recentSamples.removeFirst()
        if (recentSamples.size < smoothingWindow) return null

        val smoothed = recentSamples.average()

        return when (state) {
            State.IDLE -> {
                if (needsBaselineReturn) {
                    // Wait for signal to return to baseline before detecting next peak
                    if (smoothed < minPeakAmplitude) {
                        needsBaselineReturn = false
                    }
                } else if (smoothed >= minPeakAmplitude) {
                    state = State.RISING
                    currentPeak = smoothed
                    currentPeakTimestamp = System.currentTimeMillis()
                }
                null
            }
            State.RISING -> {
                if (smoothed > currentPeak) {
                    currentPeak = smoothed
                    currentPeakTimestamp = System.currentTimeMillis()
                    null
                } else if (smoothed < currentPeak * (1.0 - dropThreshold)) {
                    // Dropped enough to confirm peak — use the timestamp of the actual peak,
                    // corrected for smoothing lag (smoothed value lags behind real signal)
                    val peak = DetectedPeak(peakValueHPa = currentPeak, timestamp = currentPeakTimestamp - smoothingLagMs)
                    currentPeak = 0.0
                    state = State.IDLE
                    needsBaselineReturn = true  // prevent cascading false peaks on ramp-down
                    peak
                } else {
                    null
                }
            }
        }
    }
}
