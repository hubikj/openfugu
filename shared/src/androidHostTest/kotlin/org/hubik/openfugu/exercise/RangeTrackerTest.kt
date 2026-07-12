package org.hubik.openfugu.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RangeTracker.create(15.0) → activation 15.0, range 9.0..16.5 (60%–110%),
 * grace period 3000 ms.
 */
class RangeTrackerTest {

    private fun tracker() = RangeTracker.create(15.0)

    /** Activate at t, then advance past the grace period so scoring is live. */
    private fun activatedAndScoring(t0: Long = 0L): RangeTracker {
        val tr = tracker()
        tr.addSample(15.0, t0)                 // activates
        tr.addSample(12.0, t0 + 3000)          // grace elapsed → scoring starts here
        return tr
    }

    @Test
    fun `does not activate below threshold`() {
        val tr = tracker()
        assertFalse(tr.addSample(14.9, 0))
        assertFalse(tr.activated)
    }

    @Test
    fun `activates at threshold`() {
        val tr = tracker()
        assertTrue(tr.addSample(15.0, 0))
        assertTrue(tr.activated)
        assertFalse(tr.scoring)
    }

    @Test
    fun `grace period accumulates no score`() {
        val tr = tracker()
        tr.addSample(15.0, 0)
        tr.addSample(12.0, 1000)
        tr.addSample(12.0, 2000)
        assertFalse(tr.scoring)
        assertEquals(0L, tr.totalTimeMs)
    }

    @Test
    fun `scoring starts after grace period`() {
        val tr = activatedAndScoring()
        assertTrue(tr.scoring)
        assertEquals(3000L, tr.scoringStartMs)
        // The sample that flips scoring on does not itself accumulate time
        assertEquals(0L, tr.totalTimeMs)
    }

    @Test
    fun `accumulates in-range time and streaks`() {
        val tr = activatedAndScoring()
        var t = 3000L
        repeat(10) {
            t += 50
            tr.addSample(12.0, t)              // in range
        }
        assertEquals(500L, tr.timeInRangeMs)
        assertEquals(500L, tr.currentStreakMs)
        assertEquals(500L, tr.bestStreakMs)
        assertEquals(1f, tr.percentageInRange, 1e-6f)

        tr.addSample(20.0, t + 50)             // out of range (above 16.5)
        assertEquals(50L, tr.timeOutOfRangeMs)
        assertEquals(0L, tr.currentStreakMs)
        assertEquals(500L, tr.bestStreakMs)
        assertTrue(tr.percentageInRange < 1f)
    }

    @Test
    fun `range boundaries are inclusive`() {
        val tr = activatedAndScoring()
        tr.addSample(9.0, 3050)                // exactly lower bound
        assertTrue(tr.isInRange)
        tr.addSample(16.5, 3100)               // exactly upper bound
        assertTrue(tr.isInRange)
        tr.addSample(8.99, 3150)
        assertFalse(tr.isInRange)
    }

    @Test
    fun `large sample gaps are clamped, not credited`() {
        val tr = activatedAndScoring()
        tr.addSample(12.0, 3050)
        val before = tr.timeInRangeMs
        // 60-second gap (app backgrounded / BLE stall) — only the 200 ms clamp
        // may be credited, otherwise streaks and percentages are fabricated.
        tr.addSample(12.0, 63050)
        assertEquals(before + 200L, tr.timeInRangeMs)
        assertTrue(tr.bestStreakMs <= before + 200L)
    }

    @Test
    fun `non-monotonic timestamps accumulate nothing`() {
        val tr = activatedAndScoring()
        tr.addSample(12.0, 3050)
        val before = tr.totalTimeMs
        tr.addSample(12.0, 3000)               // clock went backwards
        assertEquals(before, tr.totalTimeMs)
    }

    @Test
    fun `reset returns to initial state`() {
        val tr = activatedAndScoring()
        tr.addSample(12.0, 3050)
        tr.reset()
        assertFalse(tr.activated)
        assertFalse(tr.scoring)
        assertEquals(0L, tr.totalTimeMs)
        assertEquals(0L, tr.bestStreakMs)
    }
}
