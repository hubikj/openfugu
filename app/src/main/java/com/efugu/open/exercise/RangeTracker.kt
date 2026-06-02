package com.efugu.open.exercise

/**
 * Tracks whether pressure readings fall within a target range for the Constant EQ exercise.
 *
 * Activation: pressure must first cross [activationThreshold] (= minEqPressureHPa) to start tracking.
 * Once activated, the target range is [lowerBound]..[upperBound].
 * Default range: 60%-110% of minEqPressureHPa (asymmetric because sustaining EQ requires less pressure).
 */
class RangeTracker(
    val activationThreshold: Double,
    val lowerBound: Double,
    val upperBound: Double,
    private val gracePeriodMs: Long = 3000L
) {
    var activated = false
        private set
    /** True once the grace period has elapsed and scoring has started. */
    var scoring = false
        private set
    var scoringStartMs: Long = 0L
        private set
    var timeInRangeMs: Long = 0L
        private set
    var timeOutOfRangeMs: Long = 0L
        private set
    var currentStreakMs: Long = 0L
        private set
    var bestStreakMs: Long = 0L
        private set
    var isInRange = false
        private set

    private var lastTimestampMs: Long? = null
    private var activationTimestampMs: Long = 0L
    private var wasInRange = false

    val totalTimeMs: Long get() = timeInRangeMs + timeOutOfRangeMs
    val percentageInRange: Float
        get() = if (totalTimeMs > 0) timeInRangeMs.toFloat() / totalTimeMs else 0f

    /** Time remaining in grace period (0 if scoring). */
    fun graceRemainingMs(now: Long = System.currentTimeMillis()): Long {
        if (!activated || scoring) return 0L
        return (gracePeriodMs - (now - activationTimestampMs)).coerceAtLeast(0)
    }

    fun reset() {
        activated = false
        scoring = false
        scoringStartMs = 0L
        timeInRangeMs = 0L
        timeOutOfRangeMs = 0L
        currentStreakMs = 0L
        bestStreakMs = 0L
        isInRange = false
        lastTimestampMs = null
        activationTimestampMs = 0L
        wasInRange = false
    }

    fun addSample(relativeHPa: Double, timestampMs: Long): Boolean {
        if (!activated) {
            if (relativeHPa >= activationThreshold) {
                activated = true
                activationTimestampMs = timestampMs
                lastTimestampMs = timestampMs
                isInRange = relativeHPa in lowerBound..upperBound
                wasInRange = isInRange
            }
            return activated
        }

        val dt = lastTimestampMs?.let { timestampMs - it } ?: 0L
        lastTimestampMs = timestampMs

        if (dt <= 0) return true

        isInRange = relativeHPa in lowerBound..upperBound

        // Grace period — don't score yet, just track isInRange for visual feedback
        if (!scoring) {
            if (timestampMs - activationTimestampMs >= gracePeriodMs) {
                scoring = true
                scoringStartMs = timestampMs
                wasInRange = isInRange
            }
            return true
        }

        if (isInRange) {
            timeInRangeMs += dt
            currentStreakMs += dt
            if (currentStreakMs > bestStreakMs) {
                bestStreakMs = currentStreakMs
            }
        } else {
            timeOutOfRangeMs += dt
            if (wasInRange) {
                currentStreakMs = 0L
            }
        }

        wasInRange = isInRange
        return true
    }

    companion object {
        fun create(
            minEqPressureHPa: Double,
            lowerPercent: Double = 0.6,
            upperPercent: Double = 1.1
        ): RangeTracker = RangeTracker(
            activationThreshold = minEqPressureHPa,
            lowerBound = minEqPressureHPa * lowerPercent,
            upperBound = minEqPressureHPa * upperPercent
        )
    }
}
