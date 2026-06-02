# OpenFugu — Architecture

## Overview

OpenFugu is an open-source Android app for the eFugu freediving BLE pressure training device. The app connects to one or more eFugu devices via Bluetooth LE, reads real-time nasal pressure data, and provides games, exercises, and visualization tools for equalization training.

The app is built with Kotlin, Jetpack Compose (Material 3), and targets modern Android (API 35+). There are no XML layouts — all UI is Compose.

## Project Structure

```
app/src/main/java/com/efugu/open/
├── MainActivity.kt          — Activity, root composable, navigation routing
├── PressureChart.kt         — Unified chart (pause/zoom/scroll, overlays)
├── ExercisesTab.kt          — Games + exercises listing, device selector
├── UsersTab.kt              — User profile list, paired devices
├── LogsTab.kt               — Debug log display, copy/save
│
├── ble/                     — Bluetooth LE layer
│   ├── EFuguViewModel.kt   — Central state management (ViewModel)
│   ├── DeviceConnection.kt  — Per-device BLE connection + data stream
│   ├── EFuguUuids.kt        — BLE service/characteristic UUIDs
│   ├── UserProfile.kt       — User profile + device-user pairing data classes
│   ├── PeakDetector.kt      — Peak detection for min EQ calibration
│   └── SustainedPressureDetector.kt — Sustained hold detection for max pressure
│
├── ui/                      — Shared UI components and screens
│   ├── SharedComponents.kt  — AppColors, StatRow, HpaValueRow, PeakConfirmDialog
│   ├── CalibrationWizard.kt — Multi-step pressure calibration (4 steps + summary)
│   ├── UserDetailScreen.kt  — Per-user settings (calibration, range, expert mode)
│   └── theme/               — Material 3 theme (Color, Theme, Type)
│
├── session/                 — Session recording and replay
│   ├── Session.kt           — Data model (sealed class: MinEq, ConstantEq, Game sessions)
│   ├── SessionRepository.kt — File I/O: save, load, list, delete, export JSON
│   └── SessionViewerScreen.kt — Full-screen replay with chart + stats + share
│
├── exercise/                — Training exercises
│   ├── MinEqExercise.kt     — Find minimum equalization pressure
│   ├── ConstantEqExercise.kt — Maintain pressure in target range
│   └── RangeTracker.kt      — In-range tracking with activation + grace period
│
└── game/                    — Pressure-controlled games
    ├── GameUtils.kt         — Shared: GameState, calculateTargetY, drawing helpers
    ├── FuguReefGame.kt      — Obstacle course (dodge gaps)
    ├── FuguFeastGame.kt     — Eat smaller fish, avoid bigger ones
    ├── FuguCaveGame.kt      — Navigate through narrowing cave passages
    ├── FuguFlowGame.kt      — Rhythm game (trace a scrolling target pressure curve)
    └── MultiplayerFuguReefGame.kt — Multiplayer reef race (2–7 players, last fugu standing)
```

## Data Flow

### BLE → UI Pipeline

```
eFugu device (BLE notification, ASCII Pascals, ~20 Hz)
  ↓
DeviceConnection.gattCallback.onCharacteristicChanged()
  ↓ parse ASCII → Double, subtract ambient baseline → relativeHPa
PressureReading(pressureHPa, relativeHPa, timestamp)
  ↓ stored in StateFlows
latestPressure (current value)    chartData (last 60 min history)
  ↓                                ↓
Composables collect via           PressureChart renders
collectAsState()                  with pause/scroll/zoom
  ↓
Games/exercises read
latestPressure each frame
```

### State Management

`EFuguViewModel` (AndroidViewModel) is the single source of truth:

| State | Type | Persistence |
|-------|------|-------------|
| `connections` | `Map<String, DeviceConnection>` | In-memory only |
| `savedDevices` | `List<SavedDevice>` | SharedPreferences (JSON) |
| `userProfiles` | `List<UserProfile>` | SharedPreferences (JSON) |
| `deviceUserPairings` | `List<DeviceUserPairing>` | SharedPreferences (JSON) |
| `recentSessions` | `List<SessionIndexEntry>` | JSON files (app internal storage) |
| `scanState` | `ScanState` | In-memory only |
| `scannedDevices` | `List<ScannedDevice>` | In-memory only |
| `logMessages` | `List<String>` | In-memory only |

Each `DeviceConnection` owns its own state:

| State | Description |
|-------|-------------|
| `state` | Connecting / Connected / Disconnected / Error |
| `latestPressure` | Most recent PressureReading (null until calibrated) |
| `chartData` | Rolling 60-minute pressure history (~72000 samples at 20 Hz) |
| `chartMin` / `chartMax` | Running min/max of relative pressure |
| `batteryLevel` | 0-100% (from standard Battery Service) |
| `deviceInfo` | Firmware, hardware, serial, manufacturer |
| `isCalibrated` | True after ambient baseline is established (~20 samples) |

## Navigation

The app uses a flat navigation model — no navigation library. Full-screen routing is handled by state flags in `EFuguApp`:

```
EFuguApp {
  if (showLogs)           → full-screen LogsTab with back button
  if (showUserDetail)     → full-screen UserDetailScreen
  if (calibratingUserId)  → full-screen CalibrationWizard
  if (activeGame)         → full-screen game/exercise (no chrome)
  else                    → Scaffold with bottom tabs
}
```

Bottom tabs: **Live** | **Exercises** | **Devices** | **Users**

Logs are accessible from the top-right icon on the main screen.

## Key Design Decisions

### Multi-device from the start
`connections` is a `Map<String, DeviceConnection>` — every device has independent state. The UI adapts: single device = full panel, multiple = scrollable compact cards.

### User ≠ Device
Users (profiles with calibration data) and devices (BLE hardware) are separate entities linked by `DeviceUserPairing`. One user can be paired to multiple devices. In a group setting, an instructor can quickly reassign users to different devices via the device picker.

### Calibration is per-user, not per-device
Calibration results (min EQ, max positive, max negative) are stored on `UserProfile`. When launching a game, the app looks up the user paired to the selected device to get pressure range and expert mode settings.

### Unified PressureChart
One chart composable used everywhere (Live tab, calibration wizard, exercises). Optional overlay parameters add exercise-specific features (peak markers, target range, scoring-colored line segments) without separate chart implementations.

### Game pressure mapping
All games use `calculateTargetY()` from GameUtils.kt. Normal mode: 0 hPa = bottom, pressureRange = top. Expert mode: 0 hPa = center, positive maps upper half, negative maps lower half (asymmetric ranges).

### No "active user" concept
User context flows from device pairing. Games/exercises get their settings from `viewModel.userForDevice(address)` based on which device is selected. The Users tab shows a list — tapping a user opens their detail screen directly.

## Persistence

**SharedPreferences (JSON strings)** — small, frequently accessed data:
- `SavedDevice` — address, name, nickname, color, lastConnectedAt
- `UserProfile` — calibration data, game range settings, expert mode
- `DeviceUserPairing` — device address ↔ user ID

**File storage** (`context.filesDir/sessions/`) — large session recordings:
- One JSON file per session (`session_{id}.json`) with full pressure trace
- Index file (`sessions_index.json`) for fast listing without loading traces
- Auto-cleanup: keeps last 50 sessions, deletes oldest on save
- Session types: `MinEqSession` (with peak markers), `ConstantEqSession` (with range/scoring data), `GameSession` (with score and pressure bounds), `MultiplayerGameSession` (per-player results)
- Pressure traces are filtered to the exercise/game time window, not the full rolling buffer

## BLE Protocol

See [PROTOCOL.md](PROTOCOL.md) for full BLE protocol documentation.

Key points:
- Pressure data comes from a custom service (not the auth service)
- ASCII Pascals at ~20 Hz, no CCCD write needed
- Auth is device-proves-identity via RSA signature (not required for pressure)
- The `dcdf` characteristic purpose is still unknown

## Technology Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **State:** StateFlow (Kotlin Coroutines)
- **Storage:** SharedPreferences with JSON (org.json)
- **BLE:** Android BluetoothManager / BluetoothGatt
- **Lifecycle:** AndroidViewModel
- **Min SDK:** 35 (Android 15)
- **Build:** Gradle with Kotlin DSL
