@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package org.hubik.openfugu

import com.juul.kable.Scanner
import com.juul.kable.toIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.hubik.openfugu.ble.BlePlatform
import org.hubik.openfugu.ble.DeviceUserPairing
import org.hubik.openfugu.ble.EFuguIds
import org.hubik.openfugu.ble.KableDeviceConnection
import org.hubik.openfugu.ble.MockDeviceConnection
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.ble.SavedDevice
import org.hubik.openfugu.ble.ScanState
import org.hubik.openfugu.ble.ScannedDevice
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.session.Session
import org.hubik.openfugu.session.SessionIndexEntry
import org.hubik.openfugu.session.SessionRepository
import org.hubik.openfugu.storage.FileStore
import org.hubik.openfugu.storage.KeyValueStore
import org.hubik.openfugu.util.AppLog
import org.hubik.openfugu.util.LogTimeFormat
import org.hubik.openfugu.util.boolean
import org.hubik.openfugu.util.doubleOrNull
import org.hubik.openfugu.util.formatTimestamp
import org.hubik.openfugu.util.long
import org.hubik.openfugu.util.nowMillis
import org.hubik.openfugu.util.string
import org.hubik.openfugu.util.stringOrNull

/**
 * App-wide state and logic: saved devices, user profiles, pairings, scanning,
 * connections, session history, settings. Platform-neutral — each platform
 * shell constructs one with its own storage and [BlePlatform] and keeps it
 * alive for the app's lifetime (on Android inside EFuguViewModel, on iOS as
 * a process-wide singleton).
 */
class EFuguStore(
    private val scope: CoroutineScope,
    private val prefs: KeyValueStore,
    sessionFiles: FileStore,
    private val ble: BlePlatform,
    val appVersion: String,
) {
    companion object {
        private const val TAG = "EFugu"
        private const val PREF_SAVED_DEVICES = "saved_devices_json"
        private const val PREF_USER_PROFILES = "user_profiles_json"
        private const val PREF_DEVICE_USER_PAIRINGS = "device_user_pairings_json"
        private const val PREF_APP_SETTINGS = "app_settings_json"

        // Session files are at most a few MB (20 Hz traces); anything larger
        // than this is not a session file and must not be buffered into memory.
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
    }

    /** Whether the "Bluetooth engine" developer setting has anything to switch. */
    val hasLegacyBleEngine: Boolean get() = ble.hasLegacyEngine

    // --- App-level state ---
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState = _scanState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices = _scannedDevices.asStateFlow()

    private val _savedDevices = MutableStateFlow<List<SavedDevice>>(emptyList())
    val savedDevices = _savedDevices.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages = _logMessages.asStateFlow()

    // One-shot messages for the user (shown as snackbars by the app root).
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userMessages = _userMessages.asSharedFlow()

    // --- App-level settings (theme, developer options) ---
    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings = _appSettings.asStateFlow()

    // --- User profiles ---
    private val _userProfiles = MutableStateFlow<List<UserProfile>>(emptyList())
    val userProfiles = _userProfiles.asStateFlow()

    private val _deviceUserPairings = MutableStateFlow<List<DeviceUserPairing>>(emptyList())
    val deviceUserPairings = _deviceUserPairings.asStateFlow()

    // --- Active connections (address -> PressureSource: BLE device or simulated) ---
    private val _connections = MutableStateFlow<Map<String, PressureSource>>(emptyMap())
    val connections = _connections.asStateFlow()

    // --- Session recording ---
    private val sessionRepository = SessionRepository(sessionFiles)
    private val _recentSessions = MutableStateFlow<List<SessionIndexEntry>>(emptyList())
    val recentSessions = _recentSessions.asStateFlow()

    // Devices the user explicitly disconnected this app run. Auto-connect skips
    // these so a manual disconnect sticks while the device is still nearby;
    // an explicit connect (tap) makes the device eligible again.
    private val manuallyDisconnected = mutableSetOf<String>()

    init {
        log("OpenFugu $appVersion")
        prefs.getString(PREF_APP_SETTINGS)?.let { _appSettings.value = AppSettings.fromJsonString(it) }
        loadSavedDevices()
        loadUserProfiles()
        loadDeviceUserPairings()

        ble.setBluetoothStateListener { poweredOn ->
            if (poweredOn) {
                // The radio is back: replace the stale "Bluetooth is disabled"
                // error with a live scan. Auto-connect then restores the MRU
                // device, so a Bluetooth toggle heals without user action.
                if (_scanState.value is ScanState.Error) startScan()
            } else {
                // Drop real connections ourselves — the legacy engine gets no
                // GATT callback for this, leaving dead entries in the UI. Not
                // marked manually-disconnected: these devices should reconnect
                // when the radio returns. Simulated devices keep running.
                if (_scanState.value is ScanState.Scanning) stopScan()
                _scanState.value = ScanState.Error("Bluetooth is disabled")
                _connections.value
                    .filterKeys { !MockDeviceConnection.isMockAddress(it) }
                    .forEach { (address, connection) ->
                        connection.disconnect()
                        removeConnection(address)
                        log("Disconnected ${connection.displayName} — Bluetooth turned off")
                    }
            }
        }
        scope.launch {
            _recentSessions.value = sessionRepository.loadIndex()
        }

        // Periodically bump lastConnectedAt for currently-connected devices
        // so MRU tracking reflects ongoing usage, not just connection start time.
        scope.launch {
            while (true) {
                delay(15_000)
                val connectedAddresses = _connections.value.keys
                if (connectedAddresses.isNotEmpty()) {
                    val now = nowMillis()
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
        AppLog.i(TAG, message)
        val ts = formatTimestamp(nowMillis(), LogTimeFormat)
        _logMessages.update { (it + "[$ts] $message").takeLast(200) }
    }

    // --- Saved device persistence ---

    private fun loadSavedDevices() {
        val json = prefs.getString(PREF_SAVED_DEVICES)
        if (json != null) {
            try {
                val arr = Json.parseToJsonElement(json).jsonArray
                val devices = mutableListOf<SavedDevice>()
                for (el in arr) {
                    // Parse per entry: one unreadable device must not discard the rest.
                    try {
                        val obj = el.jsonObject
                        devices.add(SavedDevice(
                            address = obj.string("address"),
                            name = obj.string("name"),
                            nickname = obj.stringOrNull("nickname")?.takeIf { it.isNotEmpty() && it != "null" },
                            lastConnectedAt = obj.long("lastConnectedAt"),
                            colorArgb = obj.long("colorArgb", 0L).takeIf { it != 0L }
                        ))
                    } catch (e: Exception) {
                        AppLog.w(TAG, "Skipping unreadable saved device entry", e)
                    }
                }
                _savedDevices.value = devices.sortedByDescending { it.lastConnectedAt }
            } catch (e: Exception) {
                // Whole payload unreadable. Back it up: the in-memory list stays
                // empty, and the next persist would otherwise overwrite the pref
                // and silently destroy the user's devices.
                AppLog.w(TAG, "Failed to load saved devices — backing up raw payload", e)
                prefs.putString(PREF_SAVED_DEVICES + "_backup", json)
            }
        }

        // Migrate old single-device prefs
        val oldAddress = prefs.getString("last_device_address")
        val oldName = prefs.getString("last_device_name")
        if (oldAddress != null && _savedDevices.value.none { it.address == oldAddress }) {
            val migrated = SavedDevice(oldAddress, oldName ?: "eFugu", null, nowMillis())
            _savedDevices.value = listOf(migrated) + _savedDevices.value
            persistSavedDevices()
            prefs.remove("last_device_address")
            prefs.remove("last_device_name")
        }
    }

    private fun persistSavedDevices() {
        val arr = buildJsonArray {
            _savedDevices.value.forEach { dev ->
                addJsonObject {
                    put("address", dev.address)
                    put("name", dev.name)
                    put("nickname", dev.nickname ?: "")
                    put("lastConnectedAt", dev.lastConnectedAt)
                    put("colorArgb", dev.colorArgb ?: 0L)
                }
            }
        }
        prefs.putString(PREF_SAVED_DEVICES, arr.toString())
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
            SavedDevice(address, name ?: "eFugu", null, nowMillis())
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
    }

    fun setColor(address: String, colorArgb: Long?) {
        _savedDevices.value = _savedDevices.value.map {
            if (it.address == address) it.copy(colorArgb = colorArgb)
            else it
        }
        persistSavedDevices()
    }

    // --- User profile persistence ---

    private fun loadUserProfiles() {
        val json = prefs.getString(PREF_USER_PROFILES) ?: return
        try {
            val arr = Json.parseToJsonElement(json).jsonArray
            val profiles = mutableListOf<UserProfile>()
            for (el in arr) {
                // Parse per entry: one unreadable profile must not discard the rest.
                try {
                    val obj = el.jsonObject
                    profiles.add(UserProfile(
                        id = obj.string("id"),
                        name = obj.string("name"),
                        minEqPressureHPa = obj.doubleOrNull("minEqPressureHPa"),
                        maxPositiveHPa = obj.doubleOrNull("maxPositiveHPa"),
                        maxNegativeHPa = obj.doubleOrNull("maxNegativeHPa"),
                        gamePressureRangeManual = obj.doubleOrNull("gamePressureRangeManual"),
                        gameNegativeRangeManual = obj.doubleOrNull("gameNegativeRangeManual"),
                        useAutoRange = obj.boolean("useAutoRange", true),
                        expertMode = obj.boolean("expertMode", false),
                        lastCalibratedAt = obj.long("lastCalibratedAt", 0L).takeIf { it != 0L }
                    ))
                } catch (e: Exception) {
                    AppLog.w(TAG, "Skipping unreadable user profile entry", e)
                }
            }
            _userProfiles.value = profiles
        } catch (e: Exception) {
            // Whole payload unreadable — back it up so the calibration data is
            // not destroyed by the next persist (see loadSavedDevices).
            AppLog.w(TAG, "Failed to load user profiles — backing up raw payload", e)
            prefs.putString(PREF_USER_PROFILES + "_backup", json)
        }
    }

    private fun persistUserProfiles() {
        val arr = buildJsonArray {
            _userProfiles.value.forEach { p ->
                addJsonObject {
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
                }
            }
        }
        prefs.putString(PREF_USER_PROFILES, arr.toString())
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
        val json = prefs.getString(PREF_DEVICE_USER_PAIRINGS) ?: return
        try {
            val arr = Json.parseToJsonElement(json).jsonArray
            val pairings = mutableListOf<DeviceUserPairing>()
            for (el in arr) {
                try {
                    val obj = el.jsonObject
                    pairings.add(DeviceUserPairing(
                        deviceAddress = obj.string("deviceAddress"),
                        userId = obj.string("userId")
                    ))
                } catch (e: Exception) {
                    AppLog.w(TAG, "Skipping unreadable pairing entry", e)
                }
            }
            _deviceUserPairings.value = pairings
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to load pairings — backing up raw payload", e)
            prefs.putString(PREF_DEVICE_USER_PAIRINGS + "_backup", json)
        }
    }

    private fun persistDeviceUserPairings() {
        val arr = buildJsonArray {
            _deviceUserPairings.value.forEach { p ->
                addJsonObject {
                    put("deviceAddress", p.deviceAddress)
                    put("userId", p.userId)
                }
            }
        }
        prefs.putString(PREF_DEVICE_USER_PAIRINGS, arr.toString())
    }

    fun pairDeviceToUser(deviceAddress: String, userId: String) {
        _deviceUserPairings.value = _deviceUserPairings.value
            .filter { it.deviceAddress != deviceAddress } + DeviceUserPairing(deviceAddress, userId)
        persistDeviceUserPairings()
    }

    fun unpairDevice(deviceAddress: String) {
        _deviceUserPairings.value = _deviceUserPairings.value
            .filter { it.deviceAddress != deviceAddress }
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

        ble.bluetoothUnavailableReason()?.let { reason ->
            _scanState.value = ScanState.Error(reason)
            return
        }

        _scannedDevices.value = emptyList()
        _scanState.value = ScanState.Scanning

        if (_appSettings.value.bleBackend == BleBackend.KABLE || !ble.hasLegacyEngine) {
            startKableScan()
        } else {
            log("Scanning for eFugu...")
            ble.startLegacyScan(
                onDevice = ::addScannedDevice,
                onScanStopped = { message ->
                    log(message)
                    _scanState.value = ScanState.Idle
                }
            )
        }
    }

    fun stopScan() {
        kableScanJob?.cancel()
        kableScanJob = null
        if (ble.hasLegacyEngine) ble.stopLegacyScan()
        if (_scanState.value is ScanState.Scanning) {
            _scanState.value = ScanState.Idle
        }
        log("Scan stopped")
    }

    private var kableScanJob: Job? = null

    /** Scan through Kable — one unfiltered scan, matched like the two legacy scans combined. */
    private fun startKableScan() {
        log("Scanning for eFugu (Kable)...")
        kableScanJob = scope.launch {
            try {
                Scanner {}.advertisements.collect { adv ->
                    val name = adv.name
                    val isEfugu = adv.uuids.contains(EFuguIds.authService) ||
                        name?.let {
                            it.contains("efugu", ignoreCase = true) ||
                                it.contains("fugu", ignoreCase = true) ||
                                it.contains("go2deep", ignoreCase = true)
                        } == true
                    if (isEfugu) {
                        addScannedDevice(ScannedDevice(name, adv.identifier.toString(), adv.rssi))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Kable scan stopped: ${e.message}")
                _scanState.value = ScanState.Error(e.message ?: "Scan failed")
            }
        }
    }

    // --- Connection management ---

    fun connectToDevice(address: String) {
        // Don't double-connect
        if (_connections.value.containsKey(address)) return

        // Simulated devices bypass Bluetooth entirely — they work with the
        // adapter absent or disabled (e.g. on the emulator).
        if (MockDeviceConnection.isMockAddress(address)) {
            connectMockDevice(address)
            return
        }

        ble.bluetoothUnavailableReason()?.let { reason ->
            log("$reason — cannot connect")
            return
        }

        // An explicit connect makes the device eligible for auto-connect again.
        manuallyDisconnected.remove(address)

        // Keep scan running so other saved/unknown devices can still be discovered;
        // it stops when the user leaves the Devices tab.

        // Find or create saved device entry
        val saved = _savedDevices.value.find { it.address == address }
            ?: SavedDevice(address, "eFugu", null, nowMillis())

        rememberDevice(saved.name, address)
        // Re-fetch after remember to get updated saved device
        val updatedSaved = _savedDevices.value.find { it.address == address } ?: saved

        val tag = address.takeLast(5)
        if (_appSettings.value.bleBackend == BleBackend.KABLE || !ble.hasLegacyEngine) {
            val connection = KableDeviceConnection(
                address = address,
                identifier = address.toIdentifier(),
                savedDevice = updatedSaved,
                onLog = { msg -> log("[$tag] $msg") },
                scope = scope,
                onUnexpectedDisconnect = { removeConnection(address) }
            )
            _connections.value = _connections.value + (address to connection)
            connection.connect()
        } else {
            val connection = ble.connectLegacy(
                address = address,
                savedDevice = updatedSaved,
                onLog = { msg -> log("[$tag] $msg") },
                onUnexpectedDisconnect = { removeConnection(address) }
            )
            _connections.value = _connections.value + (address to connection)
        }
    }

    /**
     * Add a new simulated device and connect it immediately. Saved and paired
     * like a real device, so calibration-dependent features work; several can
     * run at once for multiplayer testing.
     */
    fun addMockDevice() {
        val used = _savedDevices.value
            .filter { MockDeviceConnection.isMockAddress(it.address) }
            .mapNotNull { it.address.removePrefix(MockDeviceConnection.ADDRESS_PREFIX).toIntOrNull() }
            .toSet()
        if (used.size >= MockDeviceConnection.MAX_MOCK_DEVICES) {
            log("Simulated device limit reached (${MockDeviceConnection.MAX_MOCK_DEVICES})")
            return
        }
        val number = generateSequence(1) { it + 1 }.first { it !in used }
        connectMockDevice("${MockDeviceConnection.ADDRESS_PREFIX}$number")
    }

    private fun connectMockDevice(address: String) {
        if (_connections.value.containsKey(address)) return
        manuallyDisconnected.remove(address)

        val number = address.removePrefix(MockDeviceConnection.ADDRESS_PREFIX)
        val saved = _savedDevices.value.find { it.address == address }
            ?: SavedDevice(address, "Simulated $number", null, nowMillis())

        rememberDevice(saved.name, address)
        val updatedSaved = _savedDevices.value.find { it.address == address } ?: saved

        val connection = MockDeviceConnection(
            address = address,
            savedDevice = updatedSaved,
            onLog = { msg -> log("[$address] $msg") },
            scope = scope
        )
        _connections.value = _connections.value + (address to connection)
        connection.start()
    }

    private fun removeConnection(address: String) {
        _connections.value = _connections.value - address
        bumpLastUsed(address)
    }

    fun disconnectDevice(address: String) {
        val connection = _connections.value[address] ?: return
        connection.disconnect()
        _connections.value = _connections.value - address
        manuallyDisconnected.add(address)
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
        updated[idx] = updated[idx].copy(lastConnectedAt = nowMillis())
        _savedDevices.value = updated.sortedByDescending { it.lastConnectedAt }
        persistSavedDevices()
    }

    fun resetCalibration(address: String) {
        _connections.value[address]?.resetCalibration()
    }

    private fun addScannedDevice(device: ScannedDevice) {
        val current = _scannedDevices.value.toMutableList()
        val existing = current.indexOfFirst { it.address == device.address }
        if (existing >= 0) {
            current[existing] = device
        } else {
            log("Found: ${device.name ?: "unnamed"} [${device.address}] RSSI=${device.rssi}")
            current.add(device)
        }
        _scannedDevices.value = current

        // Auto-connect to the most recently used saved device, but only when
        // nothing else is already connected, and never to a device the user
        // manually disconnected this app run — a manual disconnect must stick
        // even while the scan still sees the device nearby. Simulated devices
        // never appear in scans, so skip them when picking the MRU device or a
        // recently-used mock would block real-device auto-connect forever.
        val mruDevice = _savedDevices.value
            .firstOrNull { !MockDeviceConnection.isMockAddress(it.address) }
        if (mruDevice != null &&
            _connections.value.isEmpty() &&
            _scanState.value is ScanState.Scanning) {
            if (device.address == mruDevice.address &&
                mruDevice.address !in manuallyDisconnected) {
                log("Auto-connecting to ${mruDevice.displayName}...")
                connectToDevice(device.address)
            }
        }
    }

    // --- Session recording ---

    fun saveSession(session: Session) {
        scope.launch {
            val saved = sessionRepository.saveSession(session)
            _recentSessions.value = sessionRepository.loadIndex()
            if (!saved) {
                log("Failed to save session!")
                postUserMessage("Could not save session")
            }
        }
    }

    suspend fun loadSession(id: String): Session? =
        sessionRepository.loadSession(id)

    fun deleteSession(id: String) {
        scope.launch {
            sessionRepository.deleteSession(id)
            _recentSessions.value = sessionRepository.loadIndex()
        }
    }

    suspend fun exportSessionJson(id: String): String? = sessionRepository.exportSessionJson(id)

    /**
     * Import a shared session file whose text the platform shell already read
     * (content URI on Android, document URL on iOS). The session is saved into
     * history so the standard viewer, share, and delete actions all work on
     * it. Returns null if the text is not a readable OpenFugu session.
     */
    suspend fun importSessionText(text: String): Session? {
        val session = try {
            sessionRepository.parseSession(text)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to parse imported session", e)
            null
        } ?: return null
        if (!sessionRepository.saveSession(session)) return null
        _recentSessions.value = sessionRepository.loadIndex()
        log("Imported session: ${session.type} from ${session.deviceName}")
        return session
    }

    /** Queue a one-shot message for the snackbar at the app root. */
    fun postUserMessage(message: String) {
        _userMessages.tryEmit(message)
    }

    fun updateAppSettings(settings: AppSettings) {
        val hidingSimulated = _appSettings.value.showSimulatedDevices && !settings.showSimulatedDevices
        _appSettings.value = settings
        prefs.putString(PREF_APP_SETTINGS, settings.toJsonString())
        if (hidingSimulated) {
            // Simulated devices are hidden app-wide while disabled — a mock
            // left connected would still surface on the Live tab and overlay.
            _connections.value.keys
                .filter { MockDeviceConnection.isMockAddress(it) }
                .forEach { disconnectDevice(it) }
        }
    }

    /** Called by shells whose store does not live for the whole process (Android ViewModel). */
    fun shutdown() {
        stopScan()
        disconnectAll()
        ble.close()
    }
}
