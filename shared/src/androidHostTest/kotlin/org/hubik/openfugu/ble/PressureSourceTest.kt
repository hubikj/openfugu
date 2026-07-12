package org.hubik.openfugu.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minimal concrete source: exposes the protected ingestion entry point. */
private class TestSource : PressureSource(
    address = "TEST",
    savedDevice = SavedDevice("TEST", "Test", null, 0L),
    onLog = {}
) {
    override fun disconnect() = resetSourceState()
    fun feed(pressureHPa: Double) = ingestPressureHPa(pressureHPa)
}

class PressureSourceTest {

    private fun calibrated(baselineHPa: Double = 1000.0): TestSource {
        val source = TestSource()
        repeat(PressureSource.CALIBRATION_SAMPLES) { source.feed(baselineHPa) }
        return source
    }

    @Test
    fun `not calibrated and no readings until the calibration window fills`() {
        val source = TestSource()
        repeat(PressureSource.CALIBRATION_SAMPLES - 1) { source.feed(1000.0) }
        assertFalse(source.isCalibrated.value)
        assertNull(source.latestPressure.value)
        assertTrue(source.chartData.value.isEmpty())

        source.feed(1000.0)
        assertTrue(source.isCalibrated.value)
        // Calibration samples themselves are not published as readings
        assertNull(source.latestPressure.value)
    }

    @Test
    fun `baseline is the average of the calibration samples`() {
        val source = TestSource()
        // Average of 999 and 1001 alternating is 1000
        repeat(PressureSource.CALIBRATION_SAMPLES) { i ->
            source.feed(if (i % 2 == 0) 999.0 else 1001.0)
        }
        source.feed(1010.0)
        assertEquals(10.0, source.latestPressure.value!!.relativeHPa, 1e-9)
        assertEquals(1010.0, source.latestPressure.value!!.pressureHPa, 1e-9)
    }

    @Test
    fun `chart extremes track the running minimum and maximum`() {
        val source = calibrated()
        source.feed(1005.0)
        source.feed(996.0)
        source.feed(1002.0)
        assertEquals(-4.0, source.chartMin.value!!, 1e-9)
        assertEquals(5.0, source.chartMax.value!!, 1e-9)
    }

    @Test
    fun `history is capped and drops the oldest samples`() {
        val source = calibrated()
        val extra = 5
        repeat(PressureSource.CHART_HISTORY_SIZE + extra) { i ->
            source.feed(1000.0 + i)
        }
        val history = source.historySnapshot()
        assertEquals(PressureSource.CHART_HISTORY_SIZE, history.size)
        // The first `extra` samples fell off the front
        assertEquals(extra.toDouble(), history.first().relativeHPa, 1e-9)
    }

    @Test
    fun `chart data publishes immutable snapshots`() {
        val source = calibrated()
        source.feed(1001.0)
        val snapshot = source.chartData.value
        source.feed(1002.0)
        assertEquals(1, snapshot.size)
        assertEquals(2, source.chartData.value.size)
    }

    @Test
    fun `resetCalibration establishes a fresh baseline`() {
        val source = calibrated(baselineHPa = 1000.0)
        source.feed(1010.0)
        source.resetCalibration()
        assertFalse(source.isCalibrated.value)
        assertNull(source.latestPressure.value)
        assertTrue(source.chartData.value.isEmpty())
        assertNull(source.chartMin.value)
        assertNull(source.chartMax.value)

        repeat(PressureSource.CALIBRATION_SAMPLES) { source.feed(1020.0) }
        source.feed(1025.0)
        assertEquals(5.0, source.latestPressure.value!!.relativeHPa, 1e-9)
    }

    @Test
    fun `disconnect clears all observable state`() {
        val source = calibrated()
        source.feed(1010.0)
        source.disconnect()
        assertTrue(source.state.value is DeviceConnectionState.Disconnected)
        assertNull(source.latestPressure.value)
        assertTrue(source.chartData.value.isEmpty())
        assertTrue(source.historySnapshot().isEmpty())
        assertFalse(source.isCalibrated.value)
    }
}

class MockPatternTest {

    @Test
    fun `manual pattern holds the control value`() {
        assertEquals(12.5, mockPatternTargetHPa(MockDeviceConnection.Pattern.Manual, 12.5, 0L), 1e-9)
        assertEquals(12.5, mockPatternTargetHPa(MockDeviceConnection.Pattern.Manual, 12.5, 9999L), 1e-9)
    }

    @Test
    fun `sine wave sweeps from zero to the control value and back`() {
        val control = 20.0
        val period = MockDeviceConnection.SINE_PERIOD_MS
        assertEquals(0.0, mockPatternTargetHPa(MockDeviceConnection.Pattern.SineWave, control, 0L), 1e-9)
        assertEquals(control / 2, mockPatternTargetHPa(MockDeviceConnection.Pattern.SineWave, control, period / 4), 1e-9)
        assertEquals(control, mockPatternTargetHPa(MockDeviceConnection.Pattern.SineWave, control, period / 2), 1e-9)
        assertEquals(0.0, mockPatternTargetHPa(MockDeviceConnection.Pattern.SineWave, control, period), 1e-9)
    }

    @Test
    fun `sine wave never leaves the zero to control range`() {
        val control = 30.0
        for (t in 0..MockDeviceConnection.SINE_PERIOD_MS step 100) {
            val value = mockPatternTargetHPa(MockDeviceConnection.Pattern.SineWave, control, t)
            assertTrue(value >= -1e-9 && value <= control + 1e-9)
        }
    }
}
