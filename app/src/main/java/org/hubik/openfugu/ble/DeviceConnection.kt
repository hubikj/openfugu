package org.hubik.openfugu.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.security.SecureRandom

/**
 * A [PressureSource] backed by a real eFugu device over BLE. Owns the
 * BluetoothGatt, GATT callback, auth, and pressure-notification parsing;
 * the shared ingestion pipeline (calibration, history, chart) lives in
 * [PressureSource].
 */
@SuppressLint("MissingPermission")
class DeviceConnection(
    address: String,
    savedDevice: SavedDevice,
    private val context: Context,
    onLog: (String) -> Unit,
    private val onUnexpectedDisconnect: () -> Unit = {}
) : PressureSource(address, savedDevice, onLog) {
    companion object {
        // Give up on unreachable devices well before the ~30 s system-level
        // GATT timeout. An in-range device completes a direct connect within
        // a couple of seconds.
        private const val CONNECT_TIMEOUT_MS = 10_000L
    }

    // --- BLE internals ---
    private var gatt: BluetoothGatt? = null
    private var pendingCharacteristics: MutableList<BluetoothGattCharacteristic> = mutableListOf()

    // Connect timeout. Everything (connect(), disconnect(), GATT callbacks)
    // runs on the main thread, so posting/removing needs no synchronization.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectTimeoutRunnable = Runnable {
        val g = gatt
        if (g != null && state.value is DeviceConnectionState.Connecting) {
            abortConnection(g, "Connection timed out — device not reachable")
        }
    }

    // Auth
    private val secureRandom = SecureRandom()
    private var authChallenge: ByteArray? = null
    private var authResponseBuffer = ByteArray(0)
    private var authComplete = false

    // --- Public API ---

    fun connect(adapter: BluetoothAdapter) {
        _state.value = DeviceConnectionState.Connecting
        onLog("Connecting...")
        val device = adapter.getRemoteDevice(address)
        // Deliver all GATT callbacks on the main thread. Every consumer of this
        // class's state lives there, so single-thread confinement removes the
        // races between binder-thread callbacks and UI-driven calls like
        // resetCalibration() or disconnect().
        gatt = device.connectGatt(
            context, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, Handler(Looper.getMainLooper())
        )
        if (gatt == null) {
            onLog("connectGatt returned null — is Bluetooth on?")
            _state.value = DeviceConnectionState.Error("Could not start connection")
            onUnexpectedDisconnect()
        } else {
            mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
        }
    }

    override fun disconnect() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        authChallenge = null
        authResponseBuffer = ByteArray(0)
        authComplete = false
        resetSourceState()
    }

    /** Give up on a half-established connection: close, surface an error, notify the owner. */
    private fun abortConnection(gatt: BluetoothGatt, message: String) {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        onLog(message)
        _state.value = DeviceConnectionState.Error(message)
        gatt.close()
        this.gatt = null
        onUnexpectedDisconnect()
    }

    // --- GATT callback (per-device) ---

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    onLog("Connected — requesting MTU 517...")
                    _state.value = DeviceConnectionState.Connected
                    if (!gatt.requestMtu(517)) {
                        onLog("MTU request not initiated — discovering services directly...")
                        if (!gatt.discoverServices()) {
                            abortConnection(gatt, "Could not start service discovery")
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
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
            if (!gatt.discoverServices()) {
                abortConnection(gatt, "Could not start service discovery")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                abortConnection(gatt, "Service discovery failed (status=$status)")
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
                    val level = value.firstOrNull()?.toInt()?.and(0xFF)
                    if (level != null) {
                        _batteryLevel.value = level
                        onLog("Battery: $level%")
                    }
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
                    value.firstOrNull()?.let { _batteryLevel.value = it.toInt() and 0xFF }
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
                char.uuid == EFuguUuids.BATTERY_LEVEL

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
        // toDoubleOrNull accepts "NaN"/"Infinity" — a single such frame would
        // permanently poison the calibration average, so require a finite value.
        val pressurePa = ascii.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return
        ingestPressureHPa(pressurePa / 100.0)
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
