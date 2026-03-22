package com.efugu.open.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class ScannedDevice(
    val name: String?,
    val address: String,
    val rssi: Int
)

data class SavedDevice(
    val address: String,
    val name: String,
    val nickname: String?,
    val lastConnectedAt: Long
) {
    val displayName: String get() = nickname ?: name
}

data class PressureReading(
    val pressureHPa: Double,
    val relativeHPa: Double,
    val timestamp: Long = System.currentTimeMillis()
)

/** App-level scan state (not per-device) */
sealed class ScanState {
    data object Idle : ScanState()
    data object Scanning : ScanState()
    data class Error(val message: String) : ScanState()
}

@SuppressLint("MissingPermission")
class EFuguViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "EFugu"
        private const val PREFS_NAME = "efugu_prefs"
        private const val PREF_SAVED_DEVICES = "saved_devices_json"
    }

    private val bluetoothManager =
        application.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- App-level state ---
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState = _scanState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices = _scannedDevices.asStateFlow()

    private val _savedDevices = MutableStateFlow<List<SavedDevice>>(emptyList())
    val savedDevices = _savedDevices.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages = _logMessages.asStateFlow()

    // --- Active connections (address -> DeviceConnection) ---
    private val _connections = MutableStateFlow<Map<String, DeviceConnection>>(emptyMap())
    val connections = _connections.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        loadSavedDevices()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        val ts = timeFormat.format(Date())
        _logMessages.value += "[$ts] $message"
        if (_logMessages.value.size > 200) {
            _logMessages.value = _logMessages.value.takeLast(200)
        }
    }

    // --- Saved device persistence ---

    private fun loadSavedDevices() {
        val json = prefs.getString(PREF_SAVED_DEVICES, null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                val devices = mutableListOf<SavedDevice>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    devices.add(SavedDevice(
                        address = obj.getString("address"),
                        name = obj.getString("name"),
                        nickname = obj.optString("nickname").takeIf { it.isNotEmpty() && it != "null" },
                        lastConnectedAt = obj.getLong("lastConnectedAt")
                    ))
                }
                _savedDevices.value = devices.sortedByDescending { it.lastConnectedAt }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load saved devices", e)
            }
        }

        // Migrate old single-device prefs
        val oldAddress = prefs.getString("last_device_address", null)
        val oldName = prefs.getString("last_device_name", null)
        if (oldAddress != null && _savedDevices.value.none { it.address == oldAddress }) {
            val migrated = SavedDevice(oldAddress, oldName ?: "eFugu", null, System.currentTimeMillis())
            _savedDevices.value = listOf(migrated) + _savedDevices.value
            persistSavedDevices()
            prefs.edit().remove("last_device_address").remove("last_device_name").apply()
        }
    }

    private fun persistSavedDevices() {
        val arr = JSONArray()
        _savedDevices.value.forEach { dev ->
            arr.put(JSONObject().apply {
                put("address", dev.address)
                put("name", dev.name)
                put("nickname", dev.nickname ?: "")
                put("lastConnectedAt", dev.lastConnectedAt)
            })
        }
        prefs.edit().putString(PREF_SAVED_DEVICES, arr.toString()).apply()
    }

    private fun rememberDevice(name: String?, address: String) {
        val current = _savedDevices.value.toMutableList()
        val existing = current.indexOfFirst { it.address == address }
        val device = if (existing >= 0) {
            val old = current.removeAt(existing)
            old.copy(name = name ?: old.name, lastConnectedAt = System.currentTimeMillis())
        } else {
            SavedDevice(address, name ?: "eFugu", null, System.currentTimeMillis())
        }
        current.add(0, device)
        _savedDevices.value = current
        persistSavedDevices()
    }

    fun forgetDevice(address: String) {
        _savedDevices.value = _savedDevices.value.filter { it.address != address }
        persistSavedDevices()
        log("Forgot device $address")
    }

    fun setNickname(address: String, nickname: String?) {
        _savedDevices.value = _savedDevices.value.map {
            if (it.address == address) it.copy(nickname = nickname?.takeIf { n -> n.isNotBlank() })
            else it
        }
        persistSavedDevices()
        // Update the DeviceConnection's reference if it exists
        val conn = _connections.value[address]
        if (conn != null) {
            val updatedSaved = _savedDevices.value.find { it.address == address }
            if (updatedSaved != null) {
                // Recreate connection map to trigger recomposition
                _connections.value = _connections.value.toMap()
            }
        }
    }

    // --- Scanning ---

    fun startScan() {
        if (_scanState.value is ScanState.Scanning) return

        val adapter = bluetoothAdapter ?: run {
            _scanState.value = ScanState.Error("Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            _scanState.value = ScanState.Error("Bluetooth is disabled")
            return
        }

        scanner = adapter.bluetoothLeScanner
        _scannedDevices.value = emptyList()
        _scanState.value = ScanState.Scanning

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(EFuguUuids.PRESSURE_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        log("Scanning for eFugu...")
        scanner?.startScan(listOf(filter), settings, scanCallback)
        scanner?.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), unfilteredScanCallback)
    }

    fun stopScan() {
        scanner?.stopScan(scanCallback)
        scanner?.stopScan(unfilteredScanCallback)
        if (_scanState.value is ScanState.Scanning) {
            _scanState.value = ScanState.Idle
        }
        log("Scan stopped")
    }

    // --- Connection management ---

    fun connectToDevice(address: String) {
        // Don't double-connect
        if (_connections.value.containsKey(address)) return

        val adapter = bluetoothAdapter ?: run {
            log("Bluetooth not available")
            return
        }

        // Stop scanning when connecting (can restart later for more devices)
        stopScan()

        // Find or create saved device entry
        val saved = _savedDevices.value.find { it.address == address }
            ?: SavedDevice(address, "eFugu", null, System.currentTimeMillis())

        rememberDevice(saved.name, address)
        // Re-fetch after remember to get updated saved device
        val updatedSaved = _savedDevices.value.find { it.address == address } ?: saved

        val tag = address.takeLast(5)
        val connection = DeviceConnection(
            address = address,
            savedDevice = updatedSaved,
            context = getApplication(),
            onLog = { msg -> log("[$tag] $msg") },
            onUnexpectedDisconnect = { removeConnection(address) }
        )

        _connections.value = _connections.value + (address to connection)
        connection.connect(adapter)
    }

    private fun removeConnection(address: String) {
        _connections.value = _connections.value - address
    }

    fun disconnectDevice(address: String) {
        val connection = _connections.value[address] ?: return
        connection.disconnect()
        _connections.value = _connections.value - address
        log("Disconnected ${connection.displayName}")
    }

    fun disconnectAll() {
        _connections.value.values.forEach { it.disconnect() }
        _connections.value = emptyMap()
    }

    fun resetCalibration(address: String) {
        _connections.value[address]?.resetCalibration()
    }

    // --- Scan callbacks ---

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }
    }

    private val unfilteredScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (name.contains("efugu", ignoreCase = true) ||
                name.contains("fugu", ignoreCase = true) ||
                name.contains("go2deep", ignoreCase = true)) {
                addScanResult(result)
            }
        }
    }

    private fun addScanResult(result: ScanResult) {
        val device = ScannedDevice(
            name = result.device.name,
            address = result.device.address,
            rssi = result.rssi
        )
        val current = _scannedDevices.value.toMutableList()
        val existing = current.indexOfFirst { it.address == device.address }
        if (existing >= 0) {
            current[existing] = device
        } else {
            log("Found: ${device.name ?: "unnamed"} [${device.address}] RSSI=${device.rssi}")
            current.add(device)
        }
        _scannedDevices.value = current

        // Auto-connect to most recently used saved device (if not already connected)
        val saved = _savedDevices.value
        if (saved.isNotEmpty() && _scanState.value is ScanState.Scanning) {
            val mruDevice = saved.first()
            if (device.address == mruDevice.address && !_connections.value.containsKey(device.address)) {
                log("Auto-connecting to ${mruDevice.displayName}...")
                connectToDevice(device.address)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnectAll()
    }
}

/** Format hPa avoiding "-0.0" display */
fun formatHPa(value: Double): String {
    val display = if (abs(value) < 0.05) 0.0 else value
    return "%.1f".format(display)
}
