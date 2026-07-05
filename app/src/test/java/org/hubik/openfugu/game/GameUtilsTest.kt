package org.hubik.openfugu.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * calculateTargetY maps pressure to a normalized vertical position
 * (0 = top of screen, 1 = bottom). Pressure only ever controls position —
 * never speed or power (project safety principle).
 */
class GameUtilsTest {

    private val eps = 1e-6f

    // --- Normal mode: 0 hPa = bottom, pressureRange = top ---

    @Test
    fun `zero pressure sits at the bottom`() {
        assertEquals(1f, calculateTargetY(0.0, 40.0, 0.0, expertMode = false), eps)
    }

    @Test
    fun `full range pressure sits at the top`() {
        assertEquals(0f, calculateTargetY(40.0, 40.0, 0.0, expertMode = false), eps)
    }

    @Test
    fun `half range pressure sits mid-screen`() {
        assertEquals(0.5f, calculateTargetY(20.0, 40.0, 0.0, expertMode = false), eps)
    }

    @Test
    fun `pressure above the range clamps to the top`() {
        assertEquals(0f, calculateTargetY(400.0, 40.0, 0.0, expertMode = false), eps)
    }

    @Test
    fun `negative pressure clamps to the bottom in normal mode`() {
        assertEquals(1f, calculateTargetY(-25.0, 40.0, 0.0, expertMode = false), eps)
    }

    // --- Expert mode: 0 hPa = center, positive maps up, negative maps down ---

    @Test
    fun `expert zero pressure sits at the center`() {
        assertEquals(0.5f, calculateTargetY(0.0, 40.0, 20.0, expertMode = true), eps)
    }

    @Test
    fun `expert full positive range reaches the top`() {
        // Top of screen requires exactly pressureRange (asymmetric halves)
        assertEquals(0f, calculateTargetY(40.0, 40.0, 20.0, expertMode = true), eps)
    }

    @Test
    fun `expert full negative range reaches the bottom`() {
        assertEquals(1f, calculateTargetY(-20.0, 40.0, 20.0, expertMode = true), eps)
    }

    @Test
    fun `expert half values map to quarter positions`() {
        assertEquals(0.25f, calculateTargetY(20.0, 40.0, 20.0, expertMode = true), eps)
        assertEquals(0.75f, calculateTargetY(-10.0, 40.0, 20.0, expertMode = true), eps)
    }

    @Test
    fun `expert overshoot clamps at the edges`() {
        assertEquals(0f, calculateTargetY(400.0, 40.0, 20.0, expertMode = true), eps)
        assertEquals(1f, calculateTargetY(-200.0, 40.0, 20.0, expertMode = true), eps)
    }

    @Test
    fun `expert mode without a negative range behaves like normal mode`() {
        assertEquals(1f, calculateTargetY(0.0, 40.0, 0.0, expertMode = true), eps)
        assertEquals(0f, calculateTargetY(40.0, 40.0, 0.0, expertMode = true), eps)
    }
}
