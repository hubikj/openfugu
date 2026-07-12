package org.hubik.openfugu.ble

import org.hubik.openfugu.util.nowMillis

/**
 * Detects pressure peaks for min EQ calibration.
 * Two-state machine: Idle → Rising → Idle.
 * Uses rolling average smoothing (5 samples = 250ms at 20Hz).
 */
class PeakDetector(
    private val minPeakAmplitude: Double = 5.0,
    private val dropThreshold: Double = 0.5,
    private val smoothingWindow: Int = 5,
    private val sampleRateHz: Int = 20,
    stuckAfterMs: Long = 10_000L
) {
    // Smoothing introduces a lag of half the window duration
    private val smoothingLagMs: Long = (smoothingWindow * 1000L) / (2 * sampleRateHz)
    private val stuckAfterSamples: Int = (stuckAfterMs * sampleRateHz / 1000L).toInt()
    data class DetectedPeak(
        val peakValueHPa: Double,
        val timestamp: Long = nowMillis()
    )

    private enum class State { IDLE, RISING }

    private var state = State.IDLE
    private val recentSamples = ArrayDeque<Double>(smoothingWindow)
    private var currentPeak = 0.0
    private var currentPeakTimestamp = 0L
    private var needsBaselineReturn = false  // after confirming a peak, wait for signal to return to baseline
    private var elevatedSampleCount = 0

    /**
     * True when the smoothed signal has stayed at or above minPeakAmplitude for
     * stuckAfterMs without a peak being confirmed — the detector cannot make
     * progress (typically ambient baseline drift or a blocked sensor). Surface
     * this to the user; deliberately no auto-re-zero, which would silently
     * change what measured values mean. Clears when the signal returns below
     * minPeakAmplitude, a peak is confirmed, or on reset().
     */
    val isStuck: Boolean
        get() = elevatedSampleCount >= stuckAfterSamples

    fun reset() {
        state = State.IDLE
        recentSamples.clear()
        currentPeak = 0.0
        currentPeakTimestamp = 0L
        needsBaselineReturn = false
        elevatedSampleCount = 0
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
        if (smoothed >= minPeakAmplitude) elevatedSampleCount++ else elevatedSampleCount = 0

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
                    currentPeakTimestamp = nowMillis()
                }
                null
            }
            State.RISING -> {
                if (smoothed > currentPeak) {
                    currentPeak = smoothed
                    currentPeakTimestamp = nowMillis()
                    null
                } else if (smoothed < currentPeak * (1.0 - dropThreshold)) {
                    // Dropped enough to confirm peak — use the timestamp of the actual peak,
                    // corrected for smoothing lag (smoothed value lags behind real signal)
                    val peak = DetectedPeak(peakValueHPa = currentPeak, timestamp = currentPeakTimestamp - smoothingLagMs)
                    currentPeak = 0.0
                    state = State.IDLE
                    needsBaselineReturn = true  // prevent cascading false peaks on ramp-down
                    elevatedSampleCount = 0  // confirming a peak is progress, not a lockout
                    peak
                } else {
                    null
                }
            }
        }
    }
}
