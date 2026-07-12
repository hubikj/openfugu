# OpenFugu — Claude Code Instructions

## Project

OpenFugu is an open-source Android app for the eFugu freediving BLE pressure training device. Kotlin Multiplatform + Compose Multiplatform, targeting modern Android (API 35+); an iOS target is planned (IDEAS.md).

**Module rule:** platform-neutral code lives in `shared/src/commonMain` (games, exercises, detectors, sessions, most screens); anything importing `android.*` or `EFuguViewModel` lives in `app` (Activity shell, EFuguApp routing, BLE layer, ViewModel-driven screens). Never import `android.*` (including `androidx.compose.ui.graphics.nativeCanvas`), `java.*`, `String.format`, or `System.currentTimeMillis()` in commonMain — use the `util/` helpers (`fmt`, `nowMillis`, `formatTimestamp`, `AppLog`) and `ui/CanvasText.kt` for canvas text.

## Documentation

Read before making changes:
- **ARCHITECTURE.md** — app structure, data flow, navigation, design decisions
- **IDEAS.md** — loose collection of ideas: completed, planned, and maybes
- **SPEC.md** — platform-neutral domain spec (algorithms, scoring, session format).
  Keep it in sync when changing detectors, scoring, ranges, or the session schema.

Read only when working on BLE layer or protocol investigation:
- **PROTOCOL.md** — BLE services, characteristics, auth flow, pressure data format

**Keep docs in sync:** When completing a feature, update IDEAS.md. When changing app structure (new packages, navigation changes, new shared components), update ARCHITECTURE.md. When establishing a new convention or pattern, update this file.

## Build

From the project root, with `JAVA_HOME` pointing at a JDK 17+ (for example the
one bundled with Android Studio):

```bash
JAVA_HOME=<path-to-jdk> ./gradlew compileDebugKotlin      # compile check (fast, both modules)
JAVA_HOME=<path-to-jdk> ./gradlew :shared:allTests        # unit tests
```

Always compile after changes, and run the unit tests when touching the
algorithmic core (detectors, exercises, game math, session serialization —
they live in `shared/src/androidHostTest/`). Use `compileDebugKotlin`
(fast) not `packageDebug`/`assembleDebug` (slow, may OOM).

## Conventions

### Reuse before creating
Before creating a new composable, color constant, or utility, check if a shared one already exists (paths under `shared/src/commonMain/kotlin/org/hubik/openfugu/`):
- `ui/SharedComponents.kt` — shared colors (`AppColors`), label-value rows, dialogs
- `ui/CanvasText.kt` — canvas text drawing (`drawCanvasText`, baseline-anchored, multiplatform)
- `game/GameUtils.kt` — shared game state, pressure-to-Y mapping, frame loop (`runFrameLoop`), game colors, drawing helpers
- `game/MultiplayerGameUtils.kt` — everything multiplayer games share: player info/state, readiness check, per-player fish update, session save, waiting row, scoreboard, game-over overlay
- `PressureChart.kt` — unified chart with optional overlays (don't create separate chart composables)
- `util/` — number formatting (`fmt`), time formats, JSON accessors, `nowMillis`, `AppLog`

Per-game tuning constants live once, public in the single-player file (`REEF_*`, `FEAST_*`, `CAVE_*`); multiplayer variants reuse them — never redefine a tuning constant in a second file.

### Pressure sources
Screens, exercises, and games depend on `ble/PressureSource.kt` (the abstract source owning the shared ingestion pipeline) — never on a concrete connection class directly. Real devices connect through `DeviceConnection` (Android BluetoothGatt, default) or `KableDeviceConnection` (Kable, multiplatform), selected by the "Bluetooth engine" developer setting. Simulated devices (`MockDeviceConnection`, `MOCK-n` addresses) must keep working wherever a real device does. Call them "simulated devices" in user-facing text ("mock" is developer jargon).

### User-facing text
Spell out words: "equalization" not "EQ", "minimum" not "min", "maximum" not "max". Abbreviations are fine in code identifiers. Use "target range" not "target band".

### Linked entity colors
When showing a linked entity next to a primary entity, the primary entity for that tab gets `tertiary` color (brighter), the linked entity stays default.
- Devices/Live tabs: device name = `tertiary`, user name = default
- Users tab: user name = `tertiary`, device name = default

### State in composables
Always use `collectAsState()` to observe StateFlows — never read `.value` directly in composables (causes stale UI that doesn't update).

### User context
There is no "active user" concept. User settings flow from device-user pairing: select a device → look up its paired user → use that user's calibration/settings.

### Portrait only
The app is locked to portrait orientation until proper support for landscape layout is added.
