package org.hubik.openfugu.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defaults: threshold 30 hPa, hold duration 3000 ms (window must span at
 * least 90% of it before a rolling minimum counts), POSITIVE direction.
 */
class SustainedPressureDetectorTest {

    @Test
    fun `does not track below threshold`() {
        val d = SustainedPressureDetector()
        assertNull(d.addSample(29.9, 0))
        assertFalse(d.isTracking)
    }

    @Test
    fun `short attempt reports nothing`() {
        val d = SustainedPressureDetector()
        d.addSample(40.0, 0)
        d.addSample(40.0, 1000)
        d.addSample(40.0, 2000)          // window span 2000 < 2700 (90% of hold)
        val result = d.addSample(0.0, 2500)
        assertNull(result)                // never sustained long enough
        assertFalse(d.isTracking)
    }

    @Test
    fun `full hold reports the rolling minimum`() {
        val d = SustainedPressureDetector()
        var t = 0L
        // 40 hPa held for a full 3 s window, sampled every 500 ms
        while (t <= 3000) {
            assertNull(d.addSample(40.0, t))
            t += 500
        }
        assertTrue(d.isTracking)
        assertEquals(40.0, d.currentBestSustained, 1e-9)

        val result = d.addSample(0.0, 4000)   // release
        assertNotNull(result)
        assertEquals(40.0, result!!.valueHPa, 1e-9)
        assertEquals(4000L, result.durationMs)
        assertFalse(d.isTracking)              // detector reset after reporting
    }

    @Test
    fun `a dip during the hold lowers the sustained value`() {
        val d = SustainedPressureDetector()
        var t = 0L
        while (t <= 3000) {
            d.addSample(40.0, t)
            t += 500
        }
        // Dip to 32 — subsequent windows include it, so their rolling minimum
        // is 32; the best sustained value from the clean phase (40) is kept.
        d.addSample(32.0, 3500)
        d.addSample(40.0, 4000)
        val result = d.addSample(0.0, 4500)
        assertEquals(40.0, result!!.valueHPa, 1e-9)
    }

    @Test
    fun `best sustained is the maximum over rolling minima`() {
        val d = SustainedPressureDetector()
        var t = 0L
        // First 3 s at 35
        while (t <= 3000) {
            d.addSample(35.0, t)
            t += 500
        }
        assertEquals(35.0, d.currentBestSustained, 1e-9)
        // Then rise to 45 and hold for another full window — after 3 s the
        // window contains only 45s, so the rolling minimum (and best) is 45.
        t = 3500
        while (t <= 6500) {
            d.addSample(45.0, t)
            t += 500
        }
        val result = d.addSample(0.0, 7000)
        assertEquals(45.0, result!!.valueHPa, 1e-9)
    }

    @Test
    fun `negative direction measures reverse pressure`() {
        val d = SustainedPressureDetector(
            minThreshold = 10.0,
            holdDurationMs = 3000L,
            direction = PressureDirection.NEGATIVE
        )
        var t = 0L
        while (t <= 3000) {
            d.addSample(-15.0, t)          // reverse pack: negative hPa
            t += 500
        }
        // Positive input ends the attempt in NEGATIVE mode
        val result = d.addSample(5.0, 3500)
        assertNotNull(result)
        assertEquals(15.0, result!!.valueHPa, 1e-9)   // reported as absolute value
    }

    @Test
    fun `positive direction ignores negative pressure`() {
        val d = SustainedPressureDetector()
        assertNull(d.addSample(-50.0, 0))
        assertFalse(d.isTracking)
    }
}
