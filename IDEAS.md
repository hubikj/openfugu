# OpenFugu — Ideas

A loose collection of ideas, not a committed roadmap: some are done, some are planned, some are just maybes.

## Completed
- [x] BLE protocol discovery (pressure service, auth, device info)
- [x] Multi-device BLE support (up to ~7 concurrent connections)
- [x] Bottom navigation UI (Live, Exercises, Devices, Users)
- [x] Saved devices with nicknames and color assignment
- [x] MRU auto-connect, auto-scan on app open
- [x] Live pressure chart (fixed 10s window, per-device colors, pause/scroll/zoom)
- [x] Fugu Reef game (obstacle course, progressive difficulty)
- [x] Fugu Feast game (eat smaller fish, avoid bigger, rocks, growth)
- [x] Fugu Cave game (cave swim-through, narrowing passages, jagged walls)
- [x] Fugu fish app icon
- [x] Unexpected disconnect handling
- [x] Unified device cards across Live and Devices tabs
- [x] Game code refactored — shared GameUtils.kt (GameState, pressure mapping, drawing helpers)
- [x] Expert pressure mode (bidirectional, asymmetric scaling, per-user toggle)
- [x] Calibration wizard (min equalization, max positive, max negative, per-user)
- [x] User profiles with calibration data, device-user pairing
- [x] Minimum equalization exercise (standalone, with save-to-profile)
- [x] Constant equalization exercise (activation, grace period, difficulty levels, scoring)
- [x] Unified PressureChart with optional overlays (peak markers, target range, scoring colors)
- [x] Dark/light theme (Material 3 dynamic theming)
- [x] Portrait orientation lock
- [x] Session import — shared `.fugu` session files open in OpenFugu (validated, saved to history, shown in the viewer)
- [x] First-run guided setup (start on Devices tab with welcome card → first user dialog after connect → calibration offer after any user creation)
- [x] Device-user assignment management (remove assigned user from device, assign/remove devices on user detail)
- [x] App version shown in the Logs screen and logged on startup
- [x] Session files rounded to 3 decimals (compact, no floating-point noise)

---

## Up Next (single-device, no extra hardware needed)

### Session Save/Load/Share
**Goal:** Let users save pressure recording sessions and share them (e.g., with an instructor for review).

**Implemented (v1):**
- [x] Auto-save on exercise completion and game over
- [x] Per-session JSON files in app internal storage with index for fast listing
- [x] History section in Exercises tab (last 20 sessions)
- [x] Session viewer with PressureChart replay (full zoom/scroll) + stats card
- [x] Share via Android share sheet (`.fugu` file — JSON inside — via FileProvider)
- [x] Min equalization: full replay with peak markers (green/red diamonds)
- [x] Constant equalization: full replay with target range overlay + scoring colors
- [x] Games: pressure trace + score
- [x] Delete sessions
- [x] Re-import: shared sessions are `.fugu` files; tapping one opens it in OpenFugu (imported into history, viewable, deletable). Custom extension keeps OpenFugu out of the "open with" list for ordinary JSON files.

**Future enhancements:**
- [ ] QR code sharing — student's phone shows QR, instructor scans to receive session data (works offline, no accounts)
- [ ] Shareable link — upload session to a simple cloud endpoint, get a short URL that opens the session in-app or browser (the expected long-term sharing path once internet features land)
- [ ] Save from Live tab (manual "save session" button for free recording)
- [ ] FIT file export — save sessions as Garmin FIT files (breathwork activity type) so users can log equalization training in Garmin Connect alongside their dive/fitness data. The FIT SDK is open source and supports custom developer fields for pressure data.

**Why this matters:** Instructors currently have no way to review a student's session after it ends. This enables asynchronous coaching — student trains, saves session, sends to instructor.

---

### Rhythm Game — Fugu Flow [x]
**Goal:** "Guitar Hero for your ears" — follow a scrolling pressure curve by matching your equalization pressure to a target pattern. Trains timing and pressure accuracy.

**UX concept:**
- A target pressure curve scrolls from right to left across the screen
- The player's actual pressure is shown as a line/dot that they try to keep on the target curve
- Scoring based on how closely the actual pressure matches the target at each moment
- Patterns could be: ramps, holds, pulses, sine waves, step functions
- Difficulty levels control curve complexity and required precision

**Pattern design:**
- Patterns defined as a sequence of `(time, targetHPa)` keyframes, interpolated linearly or with smoothing
- Built-in pattern library: "Gentle Ramp", "Pulse Train", "Staircase", "Wave"
- Advanced: patterns derived from the user's own calibration data (zero or something like 50% of min EQ as baseline, 80% max as ceiling)

**Scoring:**
- Continuous score: distance between actual and target, integrated over time
- "Perfect" / "Good" / "OK" / "Miss" zones around the target curve
- Combo multiplier for sustained accuracy
- Final score + breakdown at end

**Technical notes:**
- Reuse PressureChart rendering approach (Canvas with time-based scrolling)
- Pattern data structure: `data class RhythmPattern(val name: String, val keyframes: List<Pair<Float, Double>>)` where Float = seconds, Double = target hPa
- Single player, single device. Uses `pressureRange` from user profile to scale target values.

---

### App-Wide Baseline Drift Monitor
**Goal:** Surface baseline drift everywhere, not just in the minimum equalization screens. The stuck-detector notice (PeakDetector, 10 s elevated) only covers peak detection — drift equally corrupts the Live chart, exercises, and games.

**Concept (from device-testing the stuck notice, 2026-07-06):**
- [ ] Detect drift in `DeviceConnection` itself so every screen can observe it: a `baselineDriftSuspected` StateFlow set when the smoothed relative pressure stays away from zero for a long window — noticeably longer than the peak detector's 10 seconds (e.g. 60 s) to avoid false positives during normal training
- [ ] Distinguish drift from legitimate sustained effort: drift is a *flat* elevated signal (low variance), training pressure moves — require both "away from zero for the whole window" and "nearly constant" before flagging
- [ ] Watch both directions (`|smoothed|`) — weather/elevation drift can go negative too, which PeakDetector never sees
- [ ] Live tab: warning on the device card, reuse `BaselineDriftDialog` → the Recalibrate action already there
- [ ] Exercises tab device selector: warning chip so drift is caught *between* games
- [ ] Never interrupt a running game — recalibration mid-game is impossible (clears history, changes the pressure mapping); at most surface the warning at game over

---

### Game Score Leaderboard
**Goal:** Local leaderboards for game scores — see personal bests and compare between users on the same phone (family, training buddies, a class sharing an instructor's device).

**UX concept:**
- Per-game leaderboard: best scores for Fugu Reef, Fugu Feast, Fugu Cave (and future games), with user name and date
- Personal bests per user, plus an all-users view for friendly competition on a shared phone
- "New personal best!" / "New record!" celebration on the game over screen
- Entry point from the Exercises tab (alongside session history) or per-game

**Technical notes:**
- Game sessions are already auto-saved with scores — the leaderboard can be derived from the session index rather than a new data store
- Scores tie to the user via the device-user pairing at play time
- Multiplayer games could later feed the same leaderboards (one entry per player)
- Consider whether scores from different difficulty settings should rank separately

**Safety note:** Fine as-is per the design principle — game scores already reward precision and survival, never raw pressure, so a leaderboard doesn't incentivize dangerous force.

---

### Mock Device (simulated pressure source)
**Goal:** A fake device that produces pressure data without any eFugu hardware, selectable wherever a real device would be.

**Why:**
- Development without hardware at hand — the Android emulator has no Bluetooth (and neither does the iOS simulator, if the iOS port ever happens), so today nothing past the Devices tab can be exercised without a physical eFugu
- App Store review (should the iOS port reach that stage) — reviewers have no eFugu, a mock device lets them see the app working
- Try-before-buy: people without a device can play the games and get a feel for the app
- Reproducible screenshots and demos

**UX concept:**
- Mock device appears as a connectable entry (e.g. behind a developer toggle or long-press)
- Pressure controlled with an overlay slider while a game/exercise runs — drag to "equalize"
- Possibly scripted patterns too (replay a bundled session, sine wave) for hands-free demos

**Technical notes:**
- Extract an interface from `DeviceConnection` (pressure StateFlow, connection state) so exercises/games don't care whether the source is BLE or mock
- The mock pairs with a user like any device, so calibration-dependent features work

---

## Needs Multiple Devices

### Multiplayer Fugu Reef [x] (and we could also have multiplayer Fugu Cave in a similar fashion)
**Goal:** Multiple players on the same screen, each controlling their own colored fugu fish through the same obstacle course.

**UX concept:**
- Same obstacle course as single-player Fugu Reef, but with 2-7 colored fugus
- Each fugu controlled by a different connected device
- No inter-fish collision — fugus can overlap
- Each fish uses its device's assigned color
- Shared scoreboard at game over

**Technical approach:**
- Extend FuguReefGame to accept `List<DeviceConnection>` instead of single connection
- Each connection maps to a fish position via `calculateTargetY()`
- Draw multiple fugus with `drawFugu()` using device colors
- Collision detection per-fish against shared obstacles
- When a fish hits an obstacle, that player is out — last fish standing wins (but we can keep it alive until it is out too to get the highest distance)

**Device selection:**
- Use DevicePickerDialog in multi-select mode
- Each selected device must have a paired user (for settings) and an assigned color (for visual identity)

---

### Multiplayer Fugu Feast
**Goal:** Competitive eating — multiple players compete for food fish on the same screen.

**Challenges:**
- All fugu movement is vertical only (pressure-controlled) — horizontal position needs a different approach
- Option A: each player's fugu is in a fixed horizontal lane
- Option B: fugus share the same vertical space but are offset slightly horizontally for visual clarity
- Both options A and B are not suitable, because it gives the front fish an unfair advantage. All will have to be in the same horizontal position. Since our collision detection is using circles/ellipses, it should still work fine, because the most precisely positioned fish will get the food first? In case the position is exactly the same, then what do we do?

**Scoring:** most fish eaten wins. Eating a predator eliminates that player.

---

### Instructor Multi-Device Monitoring
**Goal:** Dedicated view for a freediving instructor watching multiple students train simultaneously.

**UX concept:**
- Grid or stacked layout of compact pressure cards, one per connected device
- Each card shows: student name (from paired user), device color, live pressure number, mini chart
- Alerts when a student exceeds a threshold (e.g., sharp spike = likely Valsalva instead of gentler Frenzel)
- Session concept: instructor starts/stops a monitoring session, all data recorded

**Alert system:**
- Configurable threshold (e.g., "alert if pressure > 40 hPa")
- Visual alert on the card (red flash, border color change)
- Optional vibration/sound on instructor's phone
- Helps catch dangerous technique (Valsalva) before it becomes a habit

**Session recording:**
- When monitoring session is active, all connected device data is recorded with timestamps
- After session: summary per student (min EQ found - how?, consistency - how?, technique alerts - how?)
- Export session as a report (CSV or shareable format)

---

### Multiplayer Constant Equalization Game
**Goal:** Competitive version of the constant equalization exercise — who can stay in range longest?

**UX concept:**
- All players share the same target range (based on configurable difficulty)
- Each player must first activate by crossing the threshold - yes, but the first crossing should start the grace period (maybe slightly longer than single player) already, to avoid opponents to prolong their start making the first one hold their breath for too long. If somebody does not cross their activation threshold before grace period ends, they are eliminated?
- Real-time leaderboard showing each player's in-range percentage and current streak
- Players are identified by device color and paired user name
- Game ends after a set time or when all but one player drop below a threshold percentage

**Scoring:**
- Primary: time in range percentage
- Tiebreaker: best streak duration
- Live "survival" mode: if you drop below 80% in-range, you're eliminated - I think 50% would be too easy, nobody would get eliminated...

---

### Interactive Multiplayer Games

**Design principle:** Never incentivize maximum pressure — it's dangerous to the middle ear. All games must reward precision, timing, and control, not raw force. Pressure maps to position/direction, never to power/advantage.

#### Pressure Pong
**Goal:** Two-player pong where each player controls their paddle position with equalization pressure. Could also work as single-player Breakout.

**UX concept:**
- Shared screen with a ball bouncing between two paddles
- Player 1's paddle position = their pressure reading mapped to screen height
- Player 2's paddle position = their pressure reading mapped to screen height
- Standard pong scoring (first to N points)
- Ball speed increases over time

**Technical notes:**
- Requires the players to pick exactly 2 devices in the device selection
- Ball physics: angle of reflection based on where ball hits paddle

---

#### Asymmetric Duel
**Goal:** Two-player game with asymmetric roles — one attacks, one defends. Roles swap each round.

**UX concept:**
- Attacker controls a projectile's launch angle with their pressure — release (lift device or release pressure?) to fire
- Defender controls a shield position with their pressure
- Attacker tries to be unpredictable (vary pressure before releasing), defender tries to read and react
- Round-based: each player gets N rounds as attacker, N as defender
- Scoring: attacker scores for hits, defender scores for blocks

**What makes it interesting:**
- Completely different skill per role — attacking is about deception, defending is about reaction
- Players develop strategies: fake-outs (move to one angle, quickly shift and fire), pattern reading
- No pressure magnitude advantage — both players use the same position-mapped range

---

#### 2D Navigator
**Goal:** Two players each control one axis of a shared object navigating through levels. The foundational cooperative two-eFugu game.

**UX concept:**
- Player A's pressure maps to X axis (left/right), Player B's pressure maps to Y axis (up/down)
- Navigate a shared object (fugu?) through mazes, collect items, avoid obstacles
- Levels designed to force diagonal movement, curves, and precise timing
- Both players see the same screen

**Level design ideas:**
- Tutorial: straight corridors (only one player needs to act at a time)
- Intermediate: L-shaped turns (one player holds steady, other moves)
- Advanced: diagonal paths (both must change pressure simultaneously)
- Expert: moving obstacles, narrow passages requiring coordinated precision

**What makes it interesting:**
- Players cannot talk during play (mouth closed, hands busy) — coordination must be purely intuitive
- To move diagonally, both players must change pressure at the same rate simultaneously
- Over time, good pairs develop wordless synchronization — this is the core emotional payoff
- Could have dozens of levels with increasing complexity

---

#### Lunar Lander
**Goal:** Cooperative multiplayer lunar lander — players share control of thrust and tilt axes.

**UX concept (2 players):**
- Player A (thrust): pressure maps to upward thrust countering gravity. Zero pressure = freefall, more pressure = more lift
- Player B (tilt): pressure maps to horizontal tilt. Neutral = straight, positive = tilt right, negative = tilt left (requires expert pressure mode)
- Land on platforms of decreasing size across levels
- Gravity pulls constantly — Player A must manage fuel/endurance while Player B corrects drift

**Expansion to 3 players — full 3D landing:**
- Player A (thrust): controls vertical thrust (up/down)
- Player B (tilt X): controls tilt left/right
- Player C (tilt Z): controls tilt forward/back
- Screen shows multiple simultaneous views: top-down view, front side view, right side view
- Landing pad is now a 2D target area, not just a 1D line — requires precision in both horizontal axes
- Three players must coordinate a stable descent in 3D space

**What makes it interesting:**
- Asymmetric but cooperative — each player has a distinct and essential role
- The tension of a gentle shared descent is unique — if any player panics, everyone crashes
- Creates natural "hold steady!" moments where all players must maintain precise pressure simultaneously
- Fuel mechanic could add strategic depth (thrust player can't just max thrust the whole time)
- The 3-player version with multiple views is a genuinely novel control experience — landing a spacecraft with three people and no verbal communication

---

#### Submarine
**Goal:** Cooperative two-player submarine navigation — one controls depth, the other controls speed.

**UX concept:**
- Player A (depth): pressure maps to vertical position — more pressure = deeper dive, less = surface
- Player B (speed): pressure maps to forward thrust — more pressure = faster
- Navigate through underwater cave systems with obstacles, currents, and narrow passages
- Side-scrolling view, constant gentle forward drift even at zero speed pressure

**Level elements:**
- Narrow vertical passages (Player A must be precise while Player B maintains steady speed)
- Speed gates (timed sections where Player B must burst forward while Player A holds depth steady)
- Hover zones (both players must hold minimal pressure while a danger passes overhead — the "both hold still" moments are the most tense)
- Currents that push the sub up/down (Player A must compensate) or slow/speed it (Player B must compensate)

**What makes it interesting:**
- The speed player controls pacing for both — going too fast doesn't give the depth player time to react
- Natural communication emerges: the speed player learns to slow down before tight passages
- "Both hold still" moments create shared tension without any pressure magnitude competition

---

#### Fugu Snake
**Goal:** Two classic snakes on one screen, each controlled by one player. Cooperative or competitive depending on the mode.

**UX concept:**
- Two snakes moving at constant speed on a shared grid
- Each player's pressure controls their snake's turning — positive pressure = turn right, negative = turn left, neutral = go straight (requires expert pressure mode)
- Alternatively for non-expert mode: pressure above threshold = turn clockwise, below = turn counter-clockwise, in the middle = straight
- Food spawns on the grid — eating food grows your snake
- Hit a wall, yourself, or the other snake = elimination

**Game modes:**
- **Competitive:** each player tries to survive longest. Other player's snake is an obstacle. Classic snake duel.
- **Cooperative:** both snakes must survive. Food only counts when both players have eaten one. Levels require coordination — e.g., one snake must clear a path for the other.

**What makes it interesting:**
- The control scheme is inherently challenging — you can only turn, and turning requires precise pressure timing at the right moment
- Two snakes on one grid creates dynamic obstacles that both players must react to
- Simple to understand, deep to master, highly replayable

---

#### Fugu Blaster (Tyrian-style Space Shooter)
**Goal:** A vertically scrolling space shooter controlled by eFugu. Works as single-player and scales up to a multi-eFugu crew.

**Single-player mode:**
- Ship auto-fires continuously, scrolls upward automatically
- Pressure controls horizontal position (left/right dodge)
- Destroy enemies to earn money, avoid enemies too tough to kill, dodge their projectiles
- Spend money on upgrades between waves (better guns, shields, speed)
- Progressive difficulty — more enemies, faster projectiles, tighter gaps
- This is a complete game on its own — simple controls, deep gameplay loop

**Multi-player mode (2-4+ eFugus):**
- One shared ship on screen, each eFugu controls a different role:
  - **Pilot X:** pressure controls horizontal position (left/right dodge)
  - **Pilot Y:** pressure controls vertical position (forward/back on screen)
  - **Main gun:** pressure controls aim angle of a turret (auto-fires, but player aims)
  - **Shield/special:** pressure charges a special weapon — hold to charge, release to deploy
- The more players, the more capable the ship — single pilot has auto-fire and no shield, adding players unlocks those systems
- Enemy waves scale with player count

**What makes it interesting:**
- Single-player mode is immediately accessible — same one-axis control as existing games, but a new genre
- Multi-player creates a "bridge crew" feeling — like Star Trek where everyone has a station
- Each role feels completely different — the pilot dodges, the gunner aims, the shield player manages timing
- Enemy patterns can require role coordination (e.g., shield must activate at the exact moment pilot dodges through a gap)
- Natural progression path: master single-player, then recruit friends to unlock the full ship

---

### Device Color in Multi-Device
- Use assigned colors in all multiplayer game UIs and instructor views
- Color-coded charts in multi-device Live tab (already done for line colors)
- Physical identification: 3D-printed colored nose pieces or colored tape to match app colors to physical devices

---

## Remote / Online Features

### Remote Multiplayer & Instructor Monitoring
**Goal:** Connect to other OpenFugu users over the internet for remote coaching or multiplayer.

**Approach:** Firebase Realtime Database
- Simplest real-time sync solution, free tier is generous, ~100ms latency
- Each user streams pressure readings to a shared "room" (database path)
- Other users subscribe to the room and receive updates in real-time
- No server to maintain — Firebase handles everything

**Data model:**
```
/rooms/{roomId}/
  /players/{odevi ceId}/
    name: "Alice"
    color: "#43A047"
    pressure: 12.5       (updated at ~10 Hz, throttled from 20 Hz)
    timestamp: 1711234567890
  /config/
    gameType: "constant_eq"
    difficulty: "medium"
    duration: 60
```

**Use cases:**
- Instructor at home monitors student practicing remotely
- Friends compete in Fugu Reef from different locations
- Group class where students use their own phones but share a room

---

## Maybe: iOS Version (Kotlin Multiplatform)

Conclusion from a 2026-07-07 discussion — no commitment, recorded so the reasoning isn't lost. There is real demand: several iPhone-owning friends want the app.

**Approach (if ever done):**
- One repo, migrated in place — **no fork**. Restructure into `shared/` (core logic, games, Compose Multiplatform UI) plus thin `androidApp/` and `iosApp/` shells. Rename the repo (e.g. `openfugu`) when it happens; GitHub redirects old URLs.
- The algorithmic core (PeakDetector, SustainedPressureDetector, RangeTracker, UserProfile, Session/SessionJson — ~700 lines) already has zero Android imports and ports as-is. SPEC.md is the platform-neutral reference.
- The BLE layer is the real rewrite: Android `BluetoothGatt` → [Kable](https://github.com/JuulLabs/kable) (Kotlin Multiplatform BLE). iOS CoreBluetooth exposes per-device UUIDs instead of MAC addresses, so device identity / device-user pairing keys need rethinking.
- UI: Compose Multiplatform runs the existing Compose UI on iOS (stable since 2025); the Canvas-based games and PressureChart translate directly.

**Economics (free & open source, no Mac owned; an iPhone 15 is available since 2026-07):**
- GitHub Actions macOS runners are free for public repos — CI can build the iOS target, run shared-core tests, and upload to TestFlight (fastlane + App Store Connect API key). No Mac required for the pipeline.
- BLE testing on the own iPhone 15 with the eFugu in hand. Installs without a Mac (the Windows PC is the phone's USB host; no Linux desktop needed): Sideloadly or AltServer on Windows install the CI-built `.ipa` with a free Apple ID (7-day auto-refresh), or TestFlight over the air once the developer account exists. Live device logs via libimobiledevice Windows builds (`idevicesyslog`); the in-app log-export screen is the primary diagnostic regardless of host. Note: Kotlin Apple targets compile only on macOS — iOS-specific compile errors surface in CI, not on the dev VM.
- Still build in diagnostics (on-screen log, log export via share sheet) from day one so remote testers can report usefully.
- Distribution needs someone's Apple Developer account ($99/year) — mine or a contributor's. TestFlight external link for friends; builds expire after 90 days.

**What keeps the option cheap meanwhile:** SPEC.md stays platform-neutral, the core stays free of Android imports, protocol knowledge lives in PROTOCOL.md.

---

## Rejected: PWA / Browser Version

Considered and rejected 2026-07-07. A browser app would need Web Bluetooth to talk to the device, and Web Bluetooth only exists in Chromium browsers (Chrome/Edge on desktop and Android). It is unavailable on iOS entirely — every iOS browser is WebKit underneath and Apple does not ship it — so a PWA fails the "runs on any device" goal and specifically excludes the iPhone users who motivate cross-platform work in the first place.

If desktop support is ever wanted, it falls out of the Kotlin Multiplatform restructuring above (Compose Multiplatform also targets desktop) rather than a web app; desktop BLE would need its own library choice, but the shared core and SPEC.md keep that door open.

---

## Investigation
- [ ] Figure out what the `dcdf` BLE characteristic does (exercise start/stop? device config? LED control?) — needs another HCI snoop while using the official app's exercise modes

## Low Priority
- [ ] Simulated dive mode — dry-run dive training: simulates the length of a breath hold and the frequency of equalizations. The user declares the depth at their first equalization; the app then predicts the following equalization points (same relative pressure-change intervals) down to the target depth and prompts the user to equalize at each one. The official app already has this; our focus is on games and instructor features. (Not to be confused with the Mock Device idea — this uses a real device.)
- [ ] Landscape orientation support
