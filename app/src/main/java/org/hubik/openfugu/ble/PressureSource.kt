package org.hubik.openfugu.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class DeviceConnectionState {
    data object Connecting : DeviceConnectionState()
    data object Connected : DeviceConnectionState()
    data object Disconnected : DeviceConnectionState()
    data class Error(val message: String) : DeviceConnectionState()
}

/**
 * A live pressure data source — the abstraction screens, exercises, and games
 * depend on. Concrete sources: [DeviceConnection] (real eFugu over BLE) and
 * [MockDeviceConnection] (simulated, no hardware). Owns the shared
 * sample-ingestion pipeline — ambient auto-calibration, the rolling pressure
 * history, chart snapshots, and running extremes — so a simulated source
 * behaves exactly like hardware from the app's point of view.
 *
 * All state is main-thread confined: GATT callbacks are Handler-confined to
 * the main looper and the mock ticker runs on the main dispatcher, so the
 * mutable history needs no synchronization.
 */
abstract class PressureSource(
    val address: String,
    val savedDevice: SavedDevice,
    protected val onLog: (String) -> Unit
) {
    companion object {
        const val CHART_HISTORY_SIZE = 72000  // ~60 minutes at 20 Hz
        // Publish a chart snapshot every N samples. 1 = every sample (20 Hz,
        // smooth scrolling). Raise this if very long multi-device sessions
        // ever jank: each publish copies the history list (references only —
        // the per-sample boxing and full min/max scans are gone regardless).
        const val CHART_PUBLISH_EVERY = 1
        /** Ambient baseline is the average of this many initial samples (~1 s at 20 Hz). */
        const val CALIBRATION_SAMPLES = 20
    }

    // --- Observable state ---
    protected val _state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Connecting)
    val state = _state.asStateFlow()

    private val _latestPressure = MutableStateFlow<PressureReading?>(null)
    val latestPressure = _latestPressure.asStateFlow()

    private val _chartData = MutableStateFlow<List<PressureReading>>(emptyList())
    val chartData = _chartData.asStateFlow()

    private val _chartMin = MutableStateFlow<Double?>(null)
    val chartMin = _chartMin.asStateFlow()

    private val _chartMax = MutableStateFlow<Double?>(null)
    val chartMax = _chartMax.asStateFlow()

    protected val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel = _batteryLevel.asStateFlow()

    protected val _deviceInfo = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceInfo = _deviceInfo.asStateFlow()

    private val _isCalibrated = MutableStateFlow(false)
    val isCalibrated = _isCalibrated.asStateFlow()

    val displayName: String get() = savedDevice.displayName

    // --- Calibration ---
    private var ambientBaselineHPa: Double? = null
    private val calibrationSamples = mutableListOf<Double>()

    // Full pressure history (ring buffer), touched only on the main thread.
    // _chartData publishes immutable snapshots of it at a reduced rate —
    // copying the full list on every 20 Hz sample was a measured CPU/GC
    // hotspot.
    private val history = ArrayDeque<PressureReading>(1024)
    private var samplesSincePublish = 0
    private var runningMin = Double.POSITIVE_INFINITY
    private var runningMax = Double.NEGATIVE_INFINITY

    private var pressureLogCounter = 0

    /**
     * Immutable copy of the full pressure history, including samples not yet
     * published to [chartData]. Use for session traces at exercise/game end.
     */
    fun historySnapshot(): List<PressureReading> = ArrayList(history)

    private fun clearHistory() {
        history.clear()
        samplesSincePublish = 0
        runningMin = Double.POSITIVE_INFINITY
        runningMax = Double.NEGATIVE_INFINITY
    }

    /** Stop producing data and release whatever the source holds. */
    abstract fun disconnect()

    fun resetCalibration() {
        ambientBaselineHPa = null
        calibrationSamples.clear()
        _isCalibrated.value = false
        clearHistory()
        _chartData.value = emptyList()
        _chartMin.value = null
        _chartMax.value = null
        _latestPressure.value = null
        onLog("Calibration reset")
    }

    /** Reset all observable and calibration state; called from [disconnect] implementations. */
    protected fun resetSourceState() {
        _state.value = DeviceConnectionState.Disconnected
        _latestPressure.value = null
        clearHistory()
        _chartData.value = emptyList()
        _chartMin.value = null
        _chartMax.value = null
        _batteryLevel.value = null
        _deviceInfo.value = emptyMap()
        ambientBaselineHPa = null
        calibrationSamples.clear()
        _isCalibrated.value = false
        pressureLogCounter = 0
    }

    /**
     * Feed one absolute pressure sample (hPa) through the pipeline:
     * auto-calibration, relative conversion, history, extremes, chart publish.
     */
    protected fun ingestPressureHPa(pressureHPa: Double) {
        // Auto-calibrate from the first CALIBRATION_SAMPLES readings (~1 second)
        if (ambientBaselineHPa == null) {
            calibrationSamples.add(pressureHPa)
            if (calibrationSamples.size >= CALIBRATION_SAMPLES) {
                ambientBaselineHPa = calibrationSamples.average()
                _isCalibrated.value = true
                onLog("Calibrated: baseline=${"%.1f".format(ambientBaselineHPa)} hPa")
                calibrationSamples.clear()
            }
            return
        }

        val relativeHPa = pressureHPa - (ambientBaselineHPa ?: pressureHPa)
        val reading = PressureReading(
            pressureHPa = pressureHPa,
            relativeHPa = relativeHPa
        )
        _latestPressure.value = reading

        history.addLast(reading)
        if (history.size > CHART_HISTORY_SIZE) {
            history.removeFirst()
        }

        // Session-wide running extremes, updated incrementally.
        if (relativeHPa < runningMin) {
            runningMin = relativeHPa
            _chartMin.value = relativeHPa
        }
        if (relativeHPa > runningMax) {
            runningMax = relativeHPa
            _chartMax.value = relativeHPa
        }

        if (++samplesSincePublish >= CHART_PUBLISH_EVERY) {
            samplesSincePublish = 0
            _chartData.value = ArrayList(history)
        }

        // Log every ~5 seconds
        pressureLogCounter++
        if (pressureLogCounter % 100 == 1) {
            onLog("Pressure: ${"%.1f".format(relativeHPa)} hPa (abs=${"%.1f".format(pressureHPa)})")
        }
    }
}
