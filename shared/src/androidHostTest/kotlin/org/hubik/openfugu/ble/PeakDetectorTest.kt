package org.hubik.openfugu.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defaults: minPeakAmplitude 5.0, dropThreshold 0.5 (peak confirmed when the
 * smoothed signal falls below 50% of the peak), smoothing window 5 samples,
 * stuck after 10 s (200 samples at 20 Hz) elevated without a confirmed peak.
 */
class PeakDetectorTest {

    private fun feed(detector: PeakDetector, vararg values: Double): List<PeakDetector.DetectedPeak> =
        values.toList().mapNotNull { detector.addSample(it) }

    @Test
    fun `no output until smoothing window is full`() {
        val d = PeakDetector()
        repeat(4) { assertNull(d.addSample(100.0)) }
    }

    @Test
    fun `detects a simple peak after rise and fall`() {
        val d = PeakDetector()
        val peaks = feed(
            d,
            0.0, 0.0, 0.0, 0.0, 0.0,       // settle at baseline
            10.0, 10.0, 10.0, 10.0, 10.0,  // rise — smoothed climbs to 10
            0.0, 0.0, 0.0                   // fall — smoothed drops below 5 (50% of 10)
        )
        assertEquals(1, peaks.size)
        assertEquals(10.0, peaks[0].peakValueHPa, 1e-9)
    }

    @Test
    fun `ignores bumps below minimum amplitude`() {
        val d = PeakDetector()
        val peaks = feed(
            d,
            0.0, 0.0, 0.0, 0.0, 0.0,
            4.0, 4.0, 4.0, 4.0, 4.0,       // smoothed never reaches 5.0
            0.0, 0.0, 0.0, 0.0, 0.0
        )
        assertEquals(0, peaks.size)
    }

    @Test
    fun `waits for baseline return before detecting the next peak`() {
        val d = PeakDetector()
        // Rise to 20 and confirm the peak by dropping to a plateau at 8:
        // 8 is below 50% of 20 (confirms) but still above minPeakAmplitude.
        val first = feed(
            d,
            0.0, 0.0, 0.0, 0.0, 0.0,
            20.0, 20.0, 20.0, 20.0, 20.0,
            8.0, 8.0, 8.0, 8.0, 8.0
        )
        assertEquals(1, first.size)
        assertEquals(20.0, first[0].peakValueHPa, 1e-9)

        // Still above baseline (smoothed ≥ 5): a new rise must NOT start.
        val blocked = feed(d, 20.0, 20.0, 20.0, 20.0, 20.0, 8.0, 8.0, 8.0, 8.0, 8.0)
        assertEquals(0, blocked.size)

        // Return to baseline, then a new peak is detected again.
        feed(d, 0.0, 0.0, 0.0, 0.0, 0.0)
        val second = feed(d, 12.0, 12.0, 12.0, 12.0, 12.0, 0.0, 0.0, 0.0)
        assertEquals(1, second.size)
        assertEquals(12.0, second[0].peakValueHPa, 1e-9)
    }

    @Test
    fun `peak value is the maximum of the smoothed signal`() {
        val d = PeakDetector()
        feed(d, 0.0, 0.0, 0.0, 0.0, 0.0)
        // Ramp up then down: smoothed max stays below the raw max of 18
        val peaks = feed(d, 6.0, 10.0, 14.0, 18.0, 14.0, 10.0, 6.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(1, peaks.size)
        // Smoothed maximum of the ramp: (10+14+18+14+10)/5 = 13.2
        assertEquals(13.2, peaks[0].peakValueHPa, 1e-9)
    }

    @Test
    fun `reset clears state mid-rise`() {
        val d = PeakDetector()
        feed(d, 0.0, 0.0, 0.0, 0.0, 0.0, 20.0, 20.0, 20.0)
        d.reset()
        // After reset the window must refill before anything is detected
        assertNull(d.addSample(0.0))
        val peaks = feed(d, 0.0, 0.0, 0.0, 0.0, 10.0, 10.0, 10.0, 10.0, 10.0, 0.0, 0.0, 0.0)
        assertEquals(1, peaks.size)
    }

    @Test
    fun `stuck when the signal stays elevated without confirming a peak`() {
        val d = PeakDetector()
        // The smoothing window fills after 5 samples; from then on the smoothed
        // signal sits at 8.0 (above the 5.0 minimum) in RISING and never drops
        // enough to confirm. 10 s at 20 Hz = 200 elevated smoothed samples.
        repeat(4 + 199) { d.addSample(8.0) }
        assertFalse(d.isStuck)
        d.addSample(8.0)
        assertTrue(d.isStuck)
    }

    @Test
    fun `stuck when the signal never returns to baseline after a peak`() {
        val d = PeakDetector()
        // Rise to 20, confirm the peak by dropping to a plateau at 8 — above
        // the 5.0 baseline threshold, so the detector waits for a return that
        // never comes.
        val peaks = feed(
            d,
            0.0, 0.0, 0.0, 0.0, 0.0,
            20.0, 20.0, 20.0, 20.0, 20.0,
            8.0, 8.0, 8.0, 8.0, 8.0
        )
        assertEquals(1, peaks.size)
        assertFalse(d.isStuck)

        // Counting restarts after the confirmed peak; 200 more elevated samples
        // without progress flip the stuck state.
        repeat(199) { d.addSample(8.0) }
        assertFalse(d.isStuck)
        d.addSample(8.0)
        assertTrue(d.isStuck)
    }

    @Test
    fun `normal peak cycles never report stuck`() {
        val d = PeakDetector()
        feed(d, 0.0, 0.0, 0.0, 0.0, 0.0)
        repeat(30) {
            feed(d, 10.0, 10.0, 10.0, 10.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            assertFalse(d.isStuck)
        }
    }

    @Test
    fun `returning to baseline clears the stuck state`() {
        val d = PeakDetector()
        repeat(4 + 200) { d.addSample(8.0) }
        assertTrue(d.isStuck)
        // The drift finally resolves: the drop confirms the pending 8.0 rise as
        // a peak, the stuck state clears, and detection works again.
        feed(d, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertFalse(d.isStuck)
        val peaks = feed(d, 12.0, 12.0, 12.0, 12.0, 12.0, 0.0, 0.0, 0.0)
        assertEquals(1, peaks.size)
    }

    @Test
    fun `reset clears the stuck state`() {
        val d = PeakDetector()
        repeat(4 + 200) { d.addSample(8.0) }
        assertTrue(d.isStuck)
        d.reset()
        assertFalse(d.isStuck)
    }

    @Test
    fun `detected peak carries a timestamp`() {
        val d = PeakDetector()
        val peaks = feed(
            d,
            0.0, 0.0, 0.0, 0.0, 0.0,
            10.0, 10.0, 10.0, 10.0, 10.0,
            0.0, 0.0, 0.0
        )
        assertNotNull(peaks[0].timestamp)
    }
}
