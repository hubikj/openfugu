package org.hubik.openfugu.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * A simulated [PressureSource]: no hardware needed, produces samples at the
 * same rate and through the same ingestion pipeline as a real eFugu —
 * including the ambient auto-calibration. Driven by an on-screen slider
 * (see MockDeviceOverlay) or a scripted pattern. Several simulated devices
 * can run at once — each has its own MOCK-n address, pairs with a user, and
 * joins multiplayer games like any physical device.
 */
class MockDeviceConnection(
    address: String,
    savedDevice: SavedDevice,
    onLog: (String) -> Unit,
    private val scope: CoroutineScope
) : PressureSource(address, savedDevice, onLog) {

    companion object {
        const val ADDRESS_PREFIX = "MOCK-"
        fun isMockAddress(address: String) = address.startsWith(ADDRESS_PREFIX)

        // Cap so the control overlay stays usable: five slider columns still
        // fit next to the collapse handle on a 360 dp-wide screen.
        const val MAX_MOCK_DEVICES = 5

        // The overlay slider span. The negative side is deliberately smaller —
        // negative training ranges are smaller than positive ones in practice.
        const val CONTROL_MIN_HPA = -25.0
        const val CONTROL_MAX_HPA = 50.0
        const val SAMPLE_PERIOD_MS = 50L  // 20 Hz, same as the hardware
        const val SINE_PERIOD_MS = 6000L
        /** Sea-level standard pressure; the ingestion pipeline calibrates it away. */
        private const val AMBIENT_HPA = 1013.25
        // First-order low-pass toward the control target: real pressure never
        // steps instantly, and the detectors expect organic ramps.
        private const val SMOOTHING_PER_SAMPLE = 0.25
        private const val NOISE_AMPLITUDE_HPA = 0.06
    }

    enum class Pattern { Manual, SineWave }

    /** Target relative pressure (hPa) set by the overlay slider. */
    val controlHPa = MutableStateFlow(0.0)
    val pattern = MutableStateFlow(Pattern.Manual)

    private var smoothedHPa = 0.0
    private var elapsedMs = 0L
    private var ticker: Job? = null

    fun start() {
        if (ticker != null) return
        _state.value = DeviceConnectionState.Connected
        _batteryLevel.value = 100
        _deviceInfo.value = mapOf(
            "Manufacturer" to "OpenFugu",
            "Serial" to address,
            "Firmware" to "—",
            "Hardware" to "—"
        )
        onLog("Simulated device started")
        // Main dispatcher: PressureSource state is main-thread confined.
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val target = mockPatternTargetHPa(pattern.value, controlHPa.value, elapsedMs)
                smoothedHPa += (target - smoothedHPa) * SMOOTHING_PER_SAMPLE
                val noise = (Random.nextDouble() * 2.0 - 1.0) * NOISE_AMPLITUDE_HPA
                ingestPressureHPa(AMBIENT_HPA + smoothedHPa + noise)
                elapsedMs += SAMPLE_PERIOD_MS
                delay(SAMPLE_PERIOD_MS)
            }
        }
    }

    override fun disconnect() {
        ticker?.cancel()
        ticker = null
        smoothedHPa = 0.0
        elapsedMs = 0L
        controlHPa.value = 0.0
        pattern.value = Pattern.Manual
        onLog("Simulated device disconnected")
        resetSourceState()
    }
}

/**
 * Pattern target as pure math (unit-tested). Manual holds the control value;
 * SineWave sweeps 0 → control value → 0 with a [MockDeviceConnection.SINE_PERIOD_MS] period,
 * so it stays inside the positive range that non-expert games map.
 */
fun mockPatternTargetHPa(
    pattern: MockDeviceConnection.Pattern,
    controlHPa: Double,
    elapsedMs: Long
): Double = when (pattern) {
    MockDeviceConnection.Pattern.Manual -> controlHPa
    MockDeviceConnection.Pattern.SineWave ->
        controlHPa * 0.5 * (1.0 - cos(2.0 * PI * elapsedMs / MockDeviceConnection.SINE_PERIOD_MS))
}
