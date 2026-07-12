@file:OptIn(ExperimentalUuidApi::class)

package org.hubik.openfugu.ble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * eFugu BLE service/characteristic identifiers (see PROTOCOL.md), as
 * multiplatform [Uuid]s. The legacy Android BLE layer derives its
 * java.util.UUID copies from these.
 */
object EFuguIds {
    // eFugu auth/config service (dcdf = unknown/config, dcdc = RSA auth).
    // This is also the service UUID the device advertises.
    val authService: Uuid = Uuid.parse("81e7d6e4-6a6e-4561-8a33-af23e095be46")
    val dcdf: Uuid = Uuid.parse("acf1ab11-2e62-45b7-95d1-44e786c9dcdf")
    val authChallenge: Uuid = Uuid.parse("acf1ab11-2e62-45b7-95d1-44e786c9dcdc")

    // Real-time pressure service — ASCII Pascals, notifications auto-start
    val pressureService: Uuid = Uuid.parse("071517e5-9b61-4bec-a809-493dbbf6e811")
    val pressureData: Uuid = Uuid.parse("5acc8084-d5f9-4439-8c95-51fd73370dd4")

    // Standard services
    val batteryService: Uuid = Uuid.parse("0000180f-0000-1000-8000-00805f9b34fb")
    val batteryLevel: Uuid = Uuid.parse("00002a19-0000-1000-8000-00805f9b34fb")
    val deviceInfoService: Uuid = Uuid.parse("0000180a-0000-1000-8000-00805f9b34fb")
    val firmwareRevision: Uuid = Uuid.parse("00002a26-0000-1000-8000-00805f9b34fb")
    val hardwareRevision: Uuid = Uuid.parse("00002a27-0000-1000-8000-00805f9b34fb")
    val manufacturerName: Uuid = Uuid.parse("00002a29-0000-1000-8000-00805f9b34fb")
    val serialNumber: Uuid = Uuid.parse("00002a25-0000-1000-8000-00805f9b34fb")
}
