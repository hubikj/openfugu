package org.hubik.openfugu.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid

/**
 * [BlePlatform] for Android: radio/permission checks plus the legacy
 * BluetoothGatt engine ([DeviceConnection] and the two-callback scan it
 * shipped with). The Kable engine lives in EFuguStore and needs nothing
 * from here.
 */
@SuppressLint("MissingPermission")
class AndroidBlePlatform(private val context: Context) : BlePlatform {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var filteredCallback: ScanCallback? = null
    private var unfilteredCallback: ScanCallback? = null

    override fun bluetoothUnavailableReason(): String? {
        val adapter = bluetoothAdapter ?: return "Bluetooth not available"
        if (!adapter.isEnabled) return "Bluetooth is disabled"
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED) return "Bluetooth permission not granted"
        return null
    }

    override val hasLegacyEngine: Boolean = true

    override fun startLegacyScan(onDevice: (ScannedDevice) -> Unit, onScanStopped: (String) -> Unit) {
        val adapter = bluetoothAdapter ?: return
        scanner = adapter.bluetoothLeScanner

        fun scannedDeviceOf(result: ScanResult) = ScannedDevice(
            name = result.device.name,
            address = result.device.address,
            rssi = result.rssi
        )

        // Two scans, like the vendor app: one filtered on the pressure service
        // UUID, one unfiltered matched by name (some firmwares don't advertise
        // the service).
        val filtered = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onDevice(scannedDeviceOf(result))
            }
            override fun onScanFailed(errorCode: Int) {
                onScanStopped("Scan stopped by system (error=$errorCode)")
            }
        }
        val unfiltered = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name.contains("efugu", ignoreCase = true) ||
                    name.contains("fugu", ignoreCase = true) ||
                    name.contains("go2deep", ignoreCase = true)) {
                    onDevice(scannedDeviceOf(result))
                }
            }
            // The filtered scan reports failures; both fail for the same reasons.
            override fun onScanFailed(errorCode: Int) {}
        }
        filteredCallback = filtered
        unfilteredCallback = unfiltered

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(EFuguUuids.PRESSURE_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, filtered)
        scanner?.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), unfiltered)
    }

    override fun stopLegacyScan() {
        filteredCallback?.let { scanner?.stopScan(it) }
        unfilteredCallback?.let { scanner?.stopScan(it) }
        filteredCallback = null
        unfilteredCallback = null
    }

    override fun connectLegacy(
        address: String,
        savedDevice: SavedDevice,
        onLog: (String) -> Unit,
        onUnexpectedDisconnect: () -> Unit
    ): PressureSource {
        val connection = DeviceConnection(
            address = address,
            savedDevice = savedDevice,
            context = context,
            onLog = onLog,
            onUnexpectedDisconnect = onUnexpectedDisconnect
        )
        // The store checked bluetoothUnavailableReason() right before calling,
        // so the adapter exists here.
        connection.connect(bluetoothAdapter!!)
        return connection
    }
}
