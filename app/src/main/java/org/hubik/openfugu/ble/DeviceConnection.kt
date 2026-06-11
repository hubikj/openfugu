package org.hubik.openfugu.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

sealed class DeviceConnectionState {
    data object Connecting : DeviceConnectionState()
    data object Connected : DeviceConnectionState()
    data object Disconnected : DeviceConnectionState()
    data class Error(val message: String) : DeviceConnectionState()
}

/**
 * Encapsulates all state and BLE logic for a single connected eFugu device.
 * Each instance owns its own BluetoothGatt, GATT callback, pressure stream,
 * calibration, auth, and chart data.
 */
@SuppressLint("MissingPermission")
class DeviceConnection(
    val address: String,
    val savedDevice: SavedDevice,
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onUnexpectedDisconnect: () -> Unit = {}
) {
    companion object {
        private const val CHART_HISTORY_SIZE = 72000  // ~60 minutes at 20 Hz
    }

    // --- Observable state ---
    private val _state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Connecting)
    val state = _state.asStateFlow()

    private val _latestPressure = MutableStateFlow<PressureReading?>(null)
    val latestPressure = _latestPressure.asStateFlow()

    private val _chartData = MutableStateFlow<List<PressureReading>>(emptyList())
    val chartData = _chartData.asStateFlow()

    private val _chartMin = MutableStateFlow<Double?>(null)
    val chartMin = _chartMin.asStateFlow()

    private val _chartMax = MutableStateFlow<Double?>(null)
    val chartMax = _chartMax.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel = _batteryLevel.asStateFlow()

    private val _deviceInfo = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceInfo = _deviceInfo.asStateFlow()

    private val _isCalibrated = MutableStateFlow(false)
    val isCalibrated = _isCalibrated.asStateFlow()

    val displayName: String get() = savedDevice.displayName

    // --- BLE internals ---
    private var gatt: BluetoothGatt? = null
    private var pendingCharacteristics: MutableList<BluetoothGattCharacteristic> = mutableListOf()

    // Auth
    private val secureRandom = SecureRandom()
    private var authChallenge: ByteArray? = null
    private var authResponseBuffer = ByteArray(0)
    private var authComplete = false

    // Calibration
    private var ambientBaselineHPa: Double? = null
    private var calibrationSamples = mutableListOf<Double>()

    private var pressureLogCounter = 0

    // --- Public API ---

    fun connect(adapter: BluetoothAdapter) {
        _state.value = DeviceConnectionState.Connecting
        onLog("Connecting...")
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _state.value = DeviceConnectionState.Disconnected
        _latestPressure.value = null
        _chartData.value = emptyList()
        _chartMin.value = null
        _chartMax.value = null
        _batteryLevel.value = null
        _deviceInfo.value = emptyMap()
        authChallenge = null
        authResponseBuffer = ByteArray(0)
        authComplete = false
        ambientBaselineHPa = null
        calibrationSamples.clear()
        _isCalibrated.value = false
        pressureLogCounter = 0
    }

    fun resetCalibration() {
        ambientBaselineHPa = null
        calibrationSamples.clear()
        _isCalibrated.value = false
        _chartData.value = emptyList()
        _chartMin.value = null
        _chartMax.value = null
        _latestPressure.value = null
        onLog("Calibration reset")
    }

    // --- GATT callback (per-device) ---

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onLog("Connected — requesting MTU 517...")
                    _state.value = DeviceConnectionState.Connected
                    gatt.requestMtu(517)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onLog("Disconnected (status=$status)")
                    _state.value = DeviceConnectionState.Disconnected
                    gatt.close()
                    this@DeviceConnection.gatt = null
                    onUnexpectedDisconnect()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onLog("MTU=$mtu — discovering services...")
            } else {
                onLog("MTU failed (status=$status) — discovering services...")
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("Service discovery failed: status=$status")
                return
            }

            val serviceNames = gatt.services.map { s ->
                when (s.uuid) {
                    EFuguUuids.REALTIME_PRESSURE_SERVICE -> "Pressure"
                    EFuguUuids.PRESSURE_SERVICE -> "Auth"
                    EFuguUuids.BATTERY_SERVICE -> "Battery"
                    EFuguUuids.DEVICE_INFO_SERVICE -> "DeviceInfo"
                    else -> s.uuid.toString().take(8)
                }
            }
            onLog("Services: ${serviceNames.joinToString(", ")}")

            pendingCharacteristics.clear()
            readDeviceInfoAndSubscribe(gatt)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                processNextCharacteristic(gatt)
                return
            }

            when (characteristic.uuid) {
                EFuguUuids.BATTERY_LEVEL -> {
                    val level = value[0].toInt() and 0xFF
                    _batteryLevel.value = level
                    onLog("Battery: $level%")
                }
                EFuguUuids.FIRMWARE_REVISION -> {
                    _deviceInfo.value += ("Firmware" to String(value))
                }
                EFuguUuids.HARDWARE_REVISION -> {
                    _deviceInfo.value += ("Hardware" to String(value))
                }
                EFuguUuids.MANUFACTURER_NAME -> {
                    _deviceInfo.value += ("Manufacturer" to String(value))
                }
                EFuguUuids.SERIAL_NUMBER -> {
                    _deviceInfo.value += ("Serial" to String(value))
                }
            }
            processNextCharacteristic(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                EFuguUuids.REALTIME_PRESSURE_DATA -> handlePressureNotification(value)
                EFuguUuids.AUTH_CHALLENGE -> handleAuthResponse(value)
                EFuguUuids.BATTERY_LEVEL -> {
                    _batteryLevel.value = value[0].toInt() and 0xFF
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == EFuguUuids.AUTH_CHALLENGE && status != BluetoothGatt.GATT_SUCCESS) {
                onLog("Auth challenge write failed: status=$status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("Failed to enable notifications for ${descriptor.characteristic.uuid}: status=$status")
            }
            processNextCharacteristic(gatt)
        }
    }

    // --- BLE setup helpers ---

    private fun readDeviceInfoAndSubscribe(gatt: BluetoothGatt) {
        authComplete = false
        authResponseBuffer = ByteArray(0)

        gatt.getService(EFuguUuids.DEVICE_INFO_SERVICE)?.let { service ->
            service.getCharacteristic(EFuguUuids.MANUFACTURER_NAME)?.let { pendingCharacteristics.add(it) }
            service.getCharacteristic(EFuguUuids.SERIAL_NUMBER)?.let { pendingCharacteristics.add(it) }
            service.getCharacteristic(EFuguUuids.FIRMWARE_REVISION)?.let { pendingCharacteristics.add(it) }
            service.getCharacteristic(EFuguUuids.HARDWARE_REVISION)?.let { pendingCharacteristics.add(it) }
        }

        gatt.getService(EFuguUuids.BATTERY_SERVICE)?.let { service ->
            service.getCharacteristic(EFuguUuids.BATTERY_LEVEL)?.let { pendingCharacteristics.add(it) }
        }

        gatt.getService(EFuguUuids.REALTIME_PRESSURE_SERVICE)?.let { service ->
            service.getCharacteristic(EFuguUuids.REALTIME_PRESSURE_DATA)?.let { char ->
                pendingCharacteristics.add(char)
            }
        } ?: onLog("WARNING: Pressure service not found!")

        gatt.getService(EFuguUuids.PRESSURE_SERVICE)?.let { service ->
            service.getCharacteristic(EFuguUuids.AUTH_CHALLENGE)?.let { char ->
                pendingCharacteristics.add(char)
            }
        }

        gatt.getService(EFuguUuids.BATTERY_SERVICE)?.let { service ->
            service.getCharacteristic(EFuguUuids.BATTERY_LEVEL)?.let { char ->
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    pendingCharacteristics.add(char)
                }
            }
        }

        processNextCharacteristic(gatt)
    }

    private fun processNextCharacteristic(gatt: BluetoothGatt) {
        if (pendingCharacteristics.isEmpty()) {
            onLog("Setup complete — starting auth...")
            startAuthentication(gatt)
            return
        }

        val char = pendingCharacteristics.removeFirst()
        val props = char.properties

        val isCustomChar = char.service.uuid == EFuguUuids.PRESSURE_SERVICE ||
                char.service.uuid == EFuguUuids.REALTIME_PRESSURE_SERVICE ||
                (char.uuid == EFuguUuids.BATTERY_LEVEL && _batteryLevel.value != null)

        if (isCustomChar && (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)) {
            enableNotifications(gatt, char)
        } else if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            gatt.readCharacteristic(char)
        } else {
            processNextCharacteristic(gatt)
        }
    }

    private fun startAuthentication(gatt: BluetoothGatt) {
        val service = gatt.getService(EFuguUuids.PRESSURE_SERVICE) ?: return
        val authChar = service.getCharacteristic(EFuguUuids.AUTH_CHALLENGE) ?: return

        val challenge = ByteArray(128)
        secureRandom.nextBytes(challenge)
        authChallenge = challenge
        authResponseBuffer = ByteArray(0)

        onLog("Auth: sending challenge...")
        gatt.writeCharacteristic(authChar, challenge, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    private fun handleAuthResponse(value: ByteArray) {
        authResponseBuffer += value
        if (!authComplete && authResponseBuffer.size >= 336) {
            authComplete = true
            onLog("Auth complete (${authResponseBuffer.size}B signature)")
        }
    }

    private fun handlePressureNotification(value: ByteArray) {
        val ascii = String(value, Charsets.US_ASCII)
        val pressurePa = ascii.toDoubleOrNull() ?: return
        val pressureHPa = pressurePa / 100.0

        // Auto-calibrate from first 20 readings (~1 second)
        if (ambientBaselineHPa == null) {
            calibrationSamples.add(pressureHPa)
            if (calibrationSamples.size >= 20) {
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

        val history = _chartData.value.toMutableList()
        history.add(reading)
        if (history.size > CHART_HISTORY_SIZE) {
            history.removeFirst()
        }
        _chartData.value = history

        val values = history.map { it.relativeHPa }
        _chartMin.value = values.min()
        _chartMax.value = values.max()

        // Log every ~5 seconds
        pressureLogCounter++
        if (pressureLogCounter % 100 == 1) {
            onLog("Pressure: ${"%.1f".format(relativeHPa)} hPa (abs=${"%.1f".format(pressureHPa)})")
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(EFuguUuids.CCCD)
        if (descriptor != null) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            processNextCharacteristic(gatt)
        }
    }
}
