# eFugu BLE Protocol Analysis

## BLE Services & Characteristics

### Device Information Service (standard: 0x180A)
| Characteristic | UUID | Description |
|---|---|---|
| Manufacturer Name | 0x2A29 | Read — "Go2Deep" |
| Serial Number | 0x2A25 | Read |
| Firmware Revision | 0x2A26 | Read — "2.0.0" |
| Hardware Revision | 0x2A27 | Read — "revB" |

### Battery Service (standard: 0x180F)
| Characteristic | UUID | Description |
|---|---|---|
| Battery Level | 0x2A19 | Read/Notify — single byte (0-100%), updates every ~3s |

### Real-Time Pressure Service (custom)
- **Service UUID:** `071517e5-9b61-4bec-a809-493dbbf6e811`

| Characteristic | UUID | Properties | Description |
|---|---|---|---|
| Pressure Data | `5acc8084-d5f9-4439-8c95-51fd73370dd4` | Read/Notify | ASCII pressure in Pascals |

**Data format:** ASCII decimal string, e.g. `"99435.218"` = 99435.218 Pa = 993.5 hPa

**Behavior:**
- Notifications auto-start immediately after connection (no CCCD write, no trigger command)
- ~20 Hz sample rate (one reading every ~50ms)
- No authentication required for pressure data
- No CCCD descriptor on this characteristic

### eFugu Auth Service (custom)
- **Service UUID:** `81e7d6e4-6a6e-4561-8a33-af23e095be46`

| Characteristic | UUID | Properties | Description |
|---|---|---|---|
| dcdf | `acf1ab11-2e62-45b7-95d1-44e786c9dcdf` | Write/Notify/WriteNoResp | Unknown — possibly config or exercise commands |
| dcdc | `acf1ab11-2e62-45b7-95d1-44e786c9dcdc` | Write/Notify/WriteNoResp | RSA challenge-response authentication |

## Authentication (SHA256withRSA signature verification)

The auth is **device-authenticates-to-app** (not the other way around). The device
proves it holds the RSA private key. The app only has the public key.

**Flow:**
1. App generates 128 random bytes (challenge) and writes to dcdc (Write Request, opcode 0x12)
2. Device signs the challenge with its 3072-bit RSA private key using SHA256withRSA
3. Device sends the 384-byte signature back on dcdc notification (single packet with MTU 517)
4. App verifies the signature against the known RSA public key

**Key details:**
- Algorithm: SHA256withRSA
- RSA key size: 3072-bit → 384-byte signature
- Public key format: X.509 SubjectPublicKeyInfo
- Challenge size: **128 bytes** (random)
- Auth is **NOT required** for pressure data — pressure flows independently
- Auth may be required for commands on dcdf (unconfirmed)
- With MTU 517 the signature arrives in a single notification; a client should still accumulate notification bytes until all 384 are received

## Pressure Data Details
- **Unit:** Pascals (ASCII decimal string)
- **Typical range:** ~99300-104300 Pa at sea level (993-1043 hPa)
- **Baseline (ambient):** ~993 hPa in testing
- **Peak (equalization):** ~1043 hPa during Valsalva/Frenzel maneuver (~50 hPa above baseline)
- **Timestamp:** Added client-side (not from device)
- **Sample rate:** ~20 Hz

## Exercise Semantics
The device has no exercise state — it only streams pressure. All training modes
(minimum-pressure detection, constant equalization, free chart recording, simulated
dive) are implemented entirely app-side on the same pressure stream.

## Live Device Observations
- Device advertised name: "eFugu"
- Custom characteristics (dcdf, dcdc, pressure) have **NO CCCD descriptors**
- `setCharacteristicNotification()` works without CCCD write
- Manufacturer: Go2Deep, Firmware: 2.0.0, Hardware: revB
- Characteristic properties: dcdf [WNw], dcdc [WNw], pressure [RN]
- MTU: 517 (requested and granted)
