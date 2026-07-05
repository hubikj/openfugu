package org.hubik.openfugu.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Defaults: minPeakAmplitude 5.0, dropThreshold 0.5 (peak confirmed when the
 * smoothed signal falls below 50% of the peak), smoothing window 5 samples.
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
