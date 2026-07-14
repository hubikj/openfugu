package org.hubik.openfugu.ble

/**
 * The platform-specific slice of the BLE layer, injected into [org.hubik.openfugu.EFuguStore].
 * Kable scanning and [KableDeviceConnection] are multiplatform and live in the
 * store itself; this interface covers only what differs per platform: radio
 * readiness checks and the legacy Android engine (BluetoothGatt), which other
 * platforms don't have.
 */
interface BlePlatform {
    /**
     * Human-readable reason Bluetooth cannot be used right now ("Bluetooth is
     * disabled", missing permission, ...), or null when scanning may start.
     */
    fun bluetoothUnavailableReason(): String?

    /**
     * Whether this platform ships the legacy BLE engine selectable through the
     * "Bluetooth engine" developer setting. When false the store always uses
     * Kable and the setting is hidden.
     */
    val hasLegacyEngine: Boolean

    /** Start the legacy scan. Only called when [hasLegacyEngine]. */
    fun startLegacyScan(onDevice: (ScannedDevice) -> Unit, onScanStopped: (String) -> Unit)

    fun stopLegacyScan()

    /**
     * Create a legacy connection and start connecting. Only called when
     * [hasLegacyEngine].
     */
    fun connectLegacy(
        address: String,
        savedDevice: SavedDevice,
        onLog: (String) -> Unit,
        onUnexpectedDisconnect: () -> Unit
    ): PressureSource

    /**
     * Register the store's reaction to the radio powering on or off. Needed
     * because turning Bluetooth off on Android does not reliably deliver
     * GATT disconnect callbacks — without this, dead connections linger in
     * the UI. Platforms without such a signal keep the no-op default.
     */
    fun setBluetoothStateListener(onPoweredChanged: (poweredOn: Boolean) -> Unit) {}

    /** Release platform resources (Android: the state broadcast receiver). */
    fun close() {}
}

/**
 * [BlePlatform] for platforms where Kable is the only engine (iOS; desktop
 * later). Radio problems surface through Kable's own errors instead of an
 * up-front availability check.
 */
class KableOnlyBlePlatform : BlePlatform {
    override fun bluetoothUnavailableReason(): String? = null
    override val hasLegacyEngine: Boolean = false
    override fun startLegacyScan(onDevice: (ScannedDevice) -> Unit, onScanStopped: (String) -> Unit) =
        error("No legacy BLE engine on this platform")
    override fun stopLegacyScan() {}
    override fun connectLegacy(
        address: String,
        savedDevice: SavedDevice,
        onLog: (String) -> Unit,
        onUnexpectedDisconnect: () -> Unit
    ): PressureSource = error("No legacy BLE engine on this platform")
}
