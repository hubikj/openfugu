package com.efugu.open.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class ScannedDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val lastSeenMs: Long = System.currentTimeMillis()
)

/** Preset colors for identifying eFugu devices (hex ARGB). */
object DeviceColors {
    val presets = listOf(
        0xFFE53935.toLong(), // Red
        0xFFFF9800.toLong(), // Orange
        0xFFFFD54F.toLong(), // Yellow
        0xFF43A047.toLong(), // Green
        0xFF1E88E5.toLong(), // Blue
        0xFF8E24AA.toLong(), // Purple
        0xFFD81B60.toLong(), // Pink
        0xFF00ACC1.toLong(), // Teal
        0xFF6D4C41.toLong(), // Brown
        0xFF546E7A.toLong(), // Slate
    )
}

data class SavedDevice(
    val address: String,
    val name: String,
    val nickname: String?,
    val lastConnectedAt: Long,
    val colorArgb: Long? = null
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
        private const val PREF_USER_PROFILES = "user_profiles_json"
        private const val PREF_DEVICE_USER_PAIRINGS = "device_user_pairings_json"
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

    // --- User profiles ---
    private val _userProfiles = MutableStateFlow<List<UserProfile>>(emptyList())
    val userProfiles = _userProfiles.asStateFlow()

    private val _deviceUserPairings = MutableStateFlow<List<DeviceUserPairing>>(emptyList())
    val deviceUserPairings = _deviceUserPairings.asStateFlow()

    // --- Active connections (address -> DeviceConnection) ---
    private val _connections = MutableStateFlow<Map<String, DeviceConnection>>(emptyMap())
    val connections = _connections.asStateFlow()

    // --- Session recording ---
    private val sessionRepository = com.efugu.open.session.SessionRepository(getApplication())
    private val _recentSessions = MutableStateFlow<List<com.efugu.open.session.SessionIndexEntry>>(emptyList())
    val recentSessions = _recentSessions.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        loadSavedDevices()
        loadUserProfiles()
        loadDeviceUserPairings()
        _recentSessions.value = sessionRepository.loadIndex()

        // Periodically bump lastConnectedAt for currently-connected devices
        // so MRU tracking reflects ongoing usage, not just connection start time.
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                val connectedAddresses = _connections.value.keys
                if (connectedAddresses.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val updated = _savedDevices.value.map { device ->
                        if (device.address in connectedAddresses)
                            device.copy(lastConnectedAt = now)
                        else device
                    }.sortedByDescending { it.lastConnectedAt }
                    _savedDevices.value = updated
                    persistSavedDevices()
                }
            }
        }
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
                        lastConnectedAt = obj.getLong("lastConnectedAt"),
                        colorArgb = obj.optLong("colorArgb", 0L).takeIf { it != 0L }
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
                put("colorArgb", dev.colorArgb ?: 0L)
            })
        }
        prefs.edit().putString(PREF_SAVED_DEVICES, arr.toString()).apply()
    }

    private fun rememberDevice(name: String?, address: String) {
        val current = _savedDevices.value.toMutableList()
        val existing = current.indexOfFirst { it.address == address }
        val device = if (existing >= 0) {
            // Existing device — keep timestamp as-is. The periodic ticker
            // updates lastConnectedAt while a device stays connected, so it
            // already reflects "most recent use". Updating here would mark a
            // just-tapped second device as MRU even though the first device
            // is still in active use.
            val old = current.removeAt(existing)
            old.copy(name = name ?: old.name)
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
        _connections.value = _connections.value.toMap()
    }

    fun setColor(address: String, colorArgb: Long?) {
        _savedDevices.value = _savedDevices.value.map {
            if (it.address == address) it.copy(colorArgb = colorArgb)
            else it
        }
        persistSavedDevices()
        _connections.value = _connections.value.toMap()
    }

    // --- User profile persistence ---

    private fun loadUserProfiles() {
        val json = prefs.getString(PREF_USER_PROFILES, null) ?: return
        try {
            val arr = JSONArray(json)
            val profiles = mutableListOf<UserProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                profiles.add(UserProfile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    minEqPressureHPa = obj.optDouble("minEqPressureHPa").takeIf { !it.isNaN() },
                    maxPositiveHPa = obj.optDouble("maxPositiveHPa").takeIf { !it.isNaN() },
                    maxNegativeHPa = obj.optDouble("maxNegativeHPa").takeIf { !it.isNaN() },
                    gamePressureRangeManual = obj.optDouble("gamePressureRangeManual").takeIf { !it.isNaN() },
                    gameNegativeRangeManual = obj.optDouble("gameNegativeRangeManual").takeIf { !it.isNaN() },
                    useAutoRange = obj.optBoolean("useAutoRange", true),
                    expertMode = obj.optBoolean("expertMode", false),
                    lastCalibratedAt = obj.optLong("lastCalibratedAt", 0L).takeIf { it != 0L }
                ))
            }
            _userProfiles.value = profiles
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load user profiles", e)
        }
    }

    private fun persistUserProfiles() {
        val arr = JSONArray()
        _userProfiles.value.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                p.minEqPressureHPa?.let { put("minEqPressureHPa", it) }
                p.maxPositiveHPa?.let { put("maxPositiveHPa", it) }
                p.maxNegativeHPa?.let { put("maxNegativeHPa", it) }
                p.gamePressureRangeManual?.let { put("gamePressureRangeManual", it) }
                p.gameNegativeRangeManual?.let { put("gameNegativeRangeManual", it) }
                put("useAutoRange", p.useAutoRange)
                put("expertMode", p.expertMode)
                p.lastCalibratedAt?.let { put("lastCalibratedAt", it) }
            })
        }
        prefs.edit()
            .putString(PREF_USER_PROFILES, arr.toString())
            .apply()
    }

    fun addUser(name: String): UserProfile {
        val profile = UserProfile(name = name)
        _userProfiles.value += profile
        persistUserProfiles()
        return profile
    }

    fun updateUser(profile: UserProfile) {
        _userProfiles.value = _userProfiles.value.map {
            if (it.id == profile.id) profile else it
        }
        persistUserProfiles()
    }

    fun deleteUser(userId: String) {
        _userProfiles.value = _userProfiles.value.filter { it.id != userId }
        _deviceUserPairings.value = _deviceUserPairings.value.filter { it.userId != userId }
        persistUserProfiles()
        persistDeviceUserPairings()
    }

    // --- Device-user pairing persistence ---

    private fun loadDeviceUserPairings() {
        val json = prefs.getString(PREF_DEVICE_USER_PAIRINGS, null) ?: return
        try {
            val arr = JSONArray(json)
            val pairings = mutableListOf<DeviceUserPairing>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                pairings.add(DeviceUserPairing(
                    deviceAddress = obj.getString("deviceAddress"),
                    userId = obj.getString("userId")
                ))
            }
            _deviceUserPairings.value = pairings
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load device-user pairings", e)
        }
    }

    private fun persistDeviceUserPairings() {
        val arr = JSONArray()
        _deviceUserPairings.value.forEach { p ->
            arr.put(JSONObject().apply {
                put("deviceAddress", p.deviceAddress)
                put("userId", p.userId)
            })
        }
        prefs.edit().putString(PREF_DEVICE_USER_PAIRINGS, arr.toString()).apply()
    }

    fun pairDeviceToUser(deviceAddress: String, userId: String) {
        _deviceUserPairings.value = _deviceUserPairings.value
            .filter { it.deviceAddress != deviceAddress } + DeviceUserPairing(deviceAddress, userId)
        persistDeviceUserPairings()
    }

    fun userForDevice(address: String): UserProfile? {
        val userId = _deviceUserPairings.value.find { it.deviceAddress == address }?.userId
            ?: return null
        return _userProfiles.value.find { it.id == userId }
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

        // Keep scan running so other saved/unknown devices can still be discovered.
        // The scan timeout (set in startScan) will stop it eventually.

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
        bumpLastUsed(address)
    }

    fun disconnectDevice(address: String) {
        val connection = _connections.value[address] ?: return
        connection.disconnect()
        _connections.value = _connections.value - address
        bumpLastUsed(address)
        log("Disconnected ${connection.displayName}")
    }

    fun disconnectAll() {
        val addresses = _connections.value.keys.toList()
        _connections.value.values.forEach { it.disconnect() }
        _connections.value = emptyMap()
        addresses.forEach { bumpLastUsed(it) }
    }

    /** Update lastConnectedAt to now for the given device (final timestamp at disconnect). */
    private fun bumpLastUsed(address: String) {
        val current = _savedDevices.value
        val idx = current.indexOfFirst { it.address == address }
        if (idx < 0) return
        val updated = current.toMutableList()
        updated[idx] = updated[idx].copy(lastConnectedAt = System.currentTimeMillis())
        _savedDevices.value = updated.sortedByDescending { it.lastConnectedAt }
        persistSavedDevices()
    }

    fun resetCalibration(address: String) {
        _connections.value[address]?.resetCalibration()
    }

    // --- Scan callbacks ---

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }
        override fun onScanFailed(errorCode: Int) {
            log("Scan stopped by system (error=$errorCode)")
            _scanState.value = ScanState.Idle
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
        override fun onScanFailed(errorCode: Int) {
            log("Unfiltered scan stopped by system (error=$errorCode)")
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

        // Auto-connect to most recently used saved device, but only when nothing
        // else is already connected. This prevents auto-reconnect loops after
        // manual disconnects: if A is connected and the user disconnects B, B is
        // not silently reconnected just because the scan still sees it nearby.
        val saved = _savedDevices.value
        if (saved.isNotEmpty() &&
            _connections.value.isEmpty() &&
            _scanState.value is ScanState.Scanning) {
            val mruDevice = saved.first()
            if (device.address == mruDevice.address) {
                log("Auto-connecting to ${mruDevice.displayName}...")
                connectToDevice(device.address)
            }
        }
    }

    // --- Session recording ---

    fun saveSession(session: com.efugu.open.session.Session) {
        sessionRepository.saveSession(session)
        _recentSessions.value = sessionRepository.loadIndex()
    }

    fun loadSession(id: String): com.efugu.open.session.Session? = sessionRepository.loadSession(id)

    fun deleteSession(id: String) {
        sessionRepository.deleteSession(id)
        _recentSessions.value = sessionRepository.loadIndex()
    }

    fun exportSessionJson(id: String): String? = sessionRepository.exportSessionJson(id)

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
