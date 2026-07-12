@file:OptIn(ExperimentalUuidApi::class)

package org.hubik.openfugu.ble

import com.juul.kable.Identifier
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi

/**
 * A [PressureSource] backed by a real eFugu device through Kable, the
 * multiplatform BLE library (also what the original vendor app uses). Runs
 * the same setup sequence as the legacy [DeviceConnection]: subscribe to
 * pressure + battery notifications, read device info, then fire the auth
 * challenge. Selected via the Bluetooth engine developer setting.
 *
 * [identifier] is Kable's platform peripheral identity (the MAC address
 * string on Android); [address] stays the app-wide device key.
 */
class KableDeviceConnection(
    address: String,
    private val identifier: Identifier,
    savedDevice: SavedDevice,
    onLog: (String) -> Unit,
    private val scope: CoroutineScope,
    private val onUnexpectedDisconnect: () -> Unit = {}
) : PressureSource(address, savedDevice, onLog) {

    companion object {
        // Match the legacy implementation: give up on unreachable devices
        // well before the ~30 s system GATT timeout.
        private const val CONNECT_TIMEOUT_MS = 10_000L

        private val pressureDataChar =
            characteristicOf(EFuguIds.pressureService, EFuguIds.pressureData)
        private val authChallengeChar =
            characteristicOf(EFuguIds.authService, EFuguIds.authChallenge)
        private val batteryLevelChar =
            characteristicOf(EFuguIds.batteryService, EFuguIds.batteryLevel)

        private val deviceInfoCharacteristics = listOf(
            "Manufacturer" to EFuguIds.manufacturerName,
            "Serial" to EFuguIds.serialNumber,
            "Firmware" to EFuguIds.firmwareRevision,
            "Hardware" to EFuguIds.hardwareRevision,
        )
    }

    private var peripheral: Peripheral? = null
    private var connectionJob: Job? = null
    private var authComplete = false
    private var authResponseBuffer = ByteArray(0)

    fun connect() {
        _state.value = DeviceConnectionState.Connecting
        onLog("Connecting (Kable)...")
        connectionJob = scope.launch { runConnection() }
    }

    override fun disconnect() {
        // Cancel the supervisor first so the state watcher cannot report
        // this deliberate disconnect as unexpected.
        connectionJob?.cancel()
        connectionJob = null
        val p = peripheral
        peripheral = null
        if (p != null) {
            scope.launch {
                try { p.disconnect() } catch (_: Exception) {}
            }
        }
        authComplete = false
        authResponseBuffer = ByteArray(0)
        resetSourceState()
    }

    private suspend fun runConnection() {
        val p = Peripheral(identifier) {
            // Keep the connection alive when an individual observation fails
            // (e.g. a notification subscription hiccup) — just log it.
            observationExceptionHandler { cause ->
                onLog("Observation error: ${cause.message}")
            }
        }
        peripheral = p
        try {
            val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { p.connect() }
            if (connected == null) {
                fail(p, "Connection timed out — device not reachable")
                return
            }
            _state.value = DeviceConnectionState.Connected
            onLog("Connected — setting up...")

            // Pressure notifications auto-start on the device (no CCCD);
            // battery notifies every ~3 s.
            connected.launch {
                p.observe(pressureDataChar).collect { handlePressureNotification(it) }
            }
            connected.launch {
                p.observe(batteryLevelChar).collect { bytes ->
                    bytes.firstOrNull()?.let { _batteryLevel.value = it.toInt() and 0xFF }
                }
            }
            connected.launch {
                p.observe(authChallengeChar).collect { handleAuthResponse(it) }
            }

            readDeviceInfo(p)
            startAuthentication(p)

            // Stay here watching for an unexpected drop; Kable cancels the
            // connected scope (and with it the observations) on disconnect.
            p.state.first { it is State.Disconnected }
            onLog("Disconnected")
            _state.value = DeviceConnectionState.Disconnected
            onUnexpectedDisconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(p, "Connection failed: ${e.message}")
        }
    }

    /** Give up on a half-established connection: close, surface an error, notify the owner. */
    private suspend fun fail(p: Peripheral, message: String) {
        onLog(message)
        _state.value = DeviceConnectionState.Error(message)
        try { p.disconnect() } catch (_: Exception) {}
        peripheral = null
        onUnexpectedDisconnect()
    }

    private suspend fun readDeviceInfo(p: Peripheral) {
        deviceInfoCharacteristics.forEach { (label, uuid) ->
            try {
                val value = p.read(characteristicOf(EFuguIds.deviceInfoService, uuid))
                _deviceInfo.value += (label to value.decodeToString())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Optional characteristic — skip quietly like the legacy path.
            }
        }
        try {
            p.read(batteryLevelChar).firstOrNull()?.let {
                val level = it.toInt() and 0xFF
                _batteryLevel.value = level
                onLog("Battery: $level%")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun startAuthentication(p: Peripheral) {
        // The device proves itself by signing our challenge; we never verify
        // the signature (see PROTOCOL.md), so a plain random source is fine.
        val challenge = Random.nextBytes(128)
        authComplete = false
        authResponseBuffer = ByteArray(0)
        onLog("Auth: sending challenge...")
        try {
            p.write(authChallengeChar, challenge, WriteType.WithResponse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLog("Auth challenge write failed: ${e.message}")
        }
    }

    private fun handleAuthResponse(value: ByteArray) {
        authResponseBuffer += value
        if (!authComplete && authResponseBuffer.size >= 336) {
            authComplete = true
            onLog("Auth complete (${authResponseBuffer.size}B signature)")
        }
    }

    private fun handlePressureNotification(value: ByteArray) {
        val ascii = value.decodeToString()
        // toDoubleOrNull accepts "NaN"/"Infinity" — a single such frame would
        // permanently poison the calibration average, so require a finite value.
        val pressurePa = ascii.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return
        ingestPressureHPa(pressurePa / 100.0)
    }
}
