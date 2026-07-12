@file:OptIn(ExperimentalUuidApi::class)

package org.hubik.openfugu.ble

import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid

/**
 * java.util.UUID copies of [EFuguIds] for the legacy Android BLE stack
 * (BluetoothGatt APIs require them). Note the historical naming:
 * PRESSURE_SERVICE is actually the auth/config service.
 */
object EFuguUuids {
    val PRESSURE_SERVICE: UUID = EFuguIds.authService.toJavaUuid()
    val DCDF_CHAR: UUID = EFuguIds.dcdf.toJavaUuid()
    val AUTH_CHALLENGE: UUID = EFuguIds.authChallenge.toJavaUuid()

    val REALTIME_PRESSURE_SERVICE: UUID = EFuguIds.pressureService.toJavaUuid()
    val REALTIME_PRESSURE_DATA: UUID = EFuguIds.pressureData.toJavaUuid()

    val BATTERY_SERVICE: UUID = EFuguIds.batteryService.toJavaUuid()
    val BATTERY_LEVEL: UUID = EFuguIds.batteryLevel.toJavaUuid()
    val DEVICE_INFO_SERVICE: UUID = EFuguIds.deviceInfoService.toJavaUuid()
    val FIRMWARE_REVISION: UUID = EFuguIds.firmwareRevision.toJavaUuid()
    val HARDWARE_REVISION: UUID = EFuguIds.hardwareRevision.toJavaUuid()
    val MANUFACTURER_NAME: UUID = EFuguIds.manufacturerName.toJavaUuid()
    val SERIAL_NUMBER: UUID = EFuguIds.serialNumber.toJavaUuid()

    // Client Characteristic Configuration Descriptor (for enabling notifications)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
