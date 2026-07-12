package org.hubik.openfugu.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Game range derivation — this encodes the project's safety policy:
 * calibration measures a COMFORTABLE maximum, the automatic range targets
 * 80% of it for headroom, and a manual range may never exceed the calibrated
 * value itself. (Deliberate decision — do not "fix" the manual cap to 80%.)
 */
class UserProfileTest {

    private fun profile(
        maxPositive: Double? = null,
        maxNegative: Double? = null,
        manualPositive: Double? = null,
        manualNegative: Double? = null,
        useAuto: Boolean = true,
        expert: Boolean = false
    ) = UserProfile(
        name = "Test",
        maxPositiveHPa = maxPositive,
        maxNegativeHPa = maxNegative,
        gamePressureRangeManual = manualPositive,
        gameNegativeRangeManual = manualNegative,
        useAutoRange = useAuto,
        expertMode = expert
    )

    // --- Positive range ---

    @Test
    fun `auto range is 80 percent of calibrated maximum`() {
        assertEquals(40.0, profile(maxPositive = 50.0).gamePressureRange, 1e-9)
    }

    @Test
    fun `auto range without calibration falls back to default`() {
        assertEquals(40.0, profile().gamePressureRange, 1e-9)
    }

    @Test
    fun `manual range below calibrated maximum is used as-is`() {
        val p = profile(maxPositive = 50.0, manualPositive = 30.0, useAuto = false)
        assertEquals(30.0, p.gamePressureRange, 1e-9)
    }

    @Test
    fun `manual range is capped at the calibrated maximum`() {
        val p = profile(maxPositive = 50.0, manualPositive = 80.0, useAuto = false)
        assertEquals(50.0, p.gamePressureRange, 1e-9)
    }

    @Test
    fun `manual range without calibration is unclamped`() {
        // Nothing to clamp against — the user detail screen warns instead.
        val p = profile(manualPositive = 80.0, useAuto = false)
        assertEquals(80.0, p.gamePressureRange, 1e-9)
    }

    @Test
    fun `manual mode without a manual value falls back to default`() {
        assertEquals(40.0, profile(useAuto = false).gamePressureRange, 1e-9)
    }

    // --- Negative range (expert mode) ---

    @Test
    fun `auto negative range is 80 percent of calibrated maximum`() {
        assertEquals(16.0, profile(maxNegative = 20.0).gameNegativeRange, 1e-9)
    }

    @Test
    fun `negative range defaults to zero without calibration`() {
        assertEquals(0.0, profile().gameNegativeRange, 1e-9)
    }

    @Test
    fun `manual negative range is capped at the calibrated maximum`() {
        val p = profile(maxNegative = 20.0, manualNegative = 50.0, useAuto = false)
        assertEquals(20.0, p.gameNegativeRange, 1e-9)
    }
}
