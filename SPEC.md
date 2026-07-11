# OpenFugu — Domain Specification

This document specifies the platform-neutral behavior of OpenFugu: the data
pipeline, calibration, detection algorithms, exercise scoring, game rules, and
session format. Together with [PROTOCOL.md](PROTOCOL.md) (the BLE protocol), it
is intended to be sufficient to build a port (iOS, Garmin, web…) **without
reading the Kotlin source**. The Android app is the reference implementation;
where they disagree, the Android behavior is authoritative and this document
has a bug.

Units: pressure is always **hPa relative to ambient baseline** unless stated
otherwise. Screen positions are normalized **0.0 = top, 1.0 = bottom**.

## 1. Pressure pipeline

1. The device streams pressure as an **ASCII decimal string in Pascals** at
   ~**20 Hz** (see PROTOCOL.md). Parse as floating point; **discard frames
   that do not parse or are not finite** (a single `NaN` would otherwise
   poison the calibration average permanently).
2. Convert to hPa: `hPa = Pa / 100`.
3. **Ambient baseline**: average the **first 20 samples** (~1 s) after
   connection; until then the device is "calibrating" and no readings are
   published. `relativeHPa = hPa − baseline`. A manual "Recalibrate" clears
   the baseline, history, and running extremes, and repeats this step.
4. Each reading is `(pressureHPa, relativeHPa, timestampMs)` with the
   timestamp taken client-side at arrival.
5. Keep a rolling history of at most **72,000 readings (~60 min at 20 Hz)**
   for charting and session traces, plus running session-wide min/max of
   `relativeHPa`.

## 2. User model and range policy

A **user profile** holds: name, `minEqPressureHPa`, `maxPositiveHPa`,
`maxNegativeHPa` (all optional, from calibration), manual range overrides,
`useAutoRange` flag, `expertMode` flag.

Users and devices are separate entities linked by **device–user pairings**
(one user may pair with several devices; a device has at most one user).
There is **no "active user"**: settings always flow from the selected
device's paired user.

**Game range derivation (safety-critical):**

| Case | Positive range | Negative range |
|---|---|---|
| Auto, calibrated | `maxPositiveHPa × 0.8` | `maxNegativeHPa × 0.8` |
| Auto, uncalibrated | `40.0` default | `0.0` |
| Manual, calibrated | manual value, **clamped to `maxPositiveHPa`** | manual, **clamped to `maxNegativeHPa`** |
| Manual, uncalibrated | manual value (UI warns) | manual value |

Policy rationale (do not change casually): calibration measures a
**comfortable** maximum — the wizard explicitly tells users not to push to
their limit — so the calibrated value itself is the safe ceiling; the auto
range keeps 20% headroom below it.

## 3. Calibration wizard

Per-user, five steps:

1. **Intro** — pick/confirm a connected device.
2. **Minimum equalization pressure** — user equalizes gently several times;
   peaks are detected (PeakDetector, §4.1, `minPeakAmplitude = 5.0`) and each
   is confirmed or rejected by the user. Peaks detected while the
   confirmation dialog is open are discarded (no queue), and the detector is
   reset when the dialog closes so nothing half-risen lingers. If the
   detector reports its stuck state (§4.1) while a confirmation is pending,
   the confirmation is cancelled and that peak is discarded — measured
   against a drifted baseline it is suspect, and it counts as neither
   confirmed nor rejected. Result = **mean of confirmed peaks**; stddev is
   shown, and the value is called "stable" at **≥ 5 confirmed peaks with
   stddev < 2.0 hPa**.
3. **Maximum positive** — repeated sustained holds (target: three) detected
   with SustainedPressureDetector (§4.2, threshold **30.0 hPa**, hold 3 s);
   result = **average of completed holds**.
4. **Maximum negative** (optional, reverse pack) — same, direction NEGATIVE,
   threshold **10.0 hPa**.
5. **Summary** — save to profile with `lastCalibratedAt`; shows derived game
   range. Instructional copy must emphasize *comfortable* effort throughout.

**Connection resilience:** the chosen device is pinned once the user leaves
the intro step — the wizard must never fall back to a different device. If
the pinned device disconnects, pause the current step behind a "device
disconnected — waiting to reconnect" notice; accumulated step progress
(confirmed peaks, completed holds) must survive the blip. The summary step
works without a live connection, so a blip cannot block saving.

## 4. Detectors

### 4.1 PeakDetector (minimum-equalization peaks)

Parameters: `minPeakAmplitude = 5.0` hPa, `dropThreshold = 0.5`,
`smoothingWindow = 5` samples, sample rate 20 Hz.

- Maintain a rolling mean over the last 5 samples ("smoothed"); produce
  nothing until the window is full.
- State machine IDLE → RISING → IDLE:
  - IDLE → RISING when smoothed ≥ `minPeakAmplitude` (and no pending
    baseline-return, below).
  - In RISING, track the maximum smoothed value as the current peak.
  - Confirm the peak when smoothed < `peak × (1 − dropThreshold)` (i.e.
    falls below 50% of the peak). Report the peak value with its timestamp
    corrected backwards by the smoothing lag (`window/2` samples).
  - After confirmation, require the smoothed signal to return **below
    `minPeakAmplitude`** before a new peak may start (prevents cascades of
    false peaks on a slow ramp-down).
- **Stuck state**: if the smoothed signal stays at or above
  `minPeakAmplitude` for **10 s** (200 samples at 20 Hz) without a peak
  being confirmed — whether waiting for baseline return or in an endless
  rise — the detector reports a stuck state. Surface it to the user with an
  option to re-zero the device baseline (§1); re-zeroing must be
  user-invoked — never automatic, which would silently change what measured
  values mean. The state clears when the smoothed signal drops below
  `minPeakAmplitude`, when a peak is confirmed, or on reset.

### 4.2 SustainedPressureDetector (calibration holds)

Parameters: `minThreshold` (30.0 positive / 10.0 negative), `holdDurationMs =
3000`, direction POSITIVE or NEGATIVE.

- Effective value: POSITIVE → `max(relativeHPa, 0)`; NEGATIVE →
  `max(−relativeHPa, 0)` (results are reported as absolute values).
- Tracking starts when effective ≥ threshold, ends when it drops below.
- While tracking, keep readings from the last `holdDurationMs`; once the
  window spans ≥ **90%** of the hold duration, compute the window's
  **minimum**; the attempt's result is the **maximum of those minima**
  ("highest level sustained for the full hold"). Attempts that never fill
  the window report nothing.

### 4.3 RangeTracker (constant equalization scoring)

Parameters: `activationThreshold`, `lowerBound`, `upperBound`,
`gracePeriodMs = 3000`.

- Inactive until a sample ≥ `activationThreshold` (this starts the attempt).
- During the grace period, in/out of range is shown but not scored.
- After grace, each sample credits `dt = now − lastSampleTime`, **clamped to
  200 ms**, to either time-in-range or time-out-of-range (bounds inclusive;
  non-positive `dt` credits nothing). Track the current in-range streak and
  the best streak (leaving the range resets the current streak).
- Score = `timeInRange / (timeInRange + timeOutOfRange)`.

## 5. Exercises

### 5.1 Minimum Equalization Pressure

Uses PeakDetector; every detected peak is confirmed/rejected by the user
(peaks arriving while the confirmation dialog is open are discarded, and the
detector is reset when the dialog closes). When the detector reports its
stuck state (§4.1), any pending peak confirmation is cancelled (the peak
counts as neither confirmed nor rejected) and a dialog offers re-zeroing
the device baseline. Reports mean, stddev,
success/fail counts; result can be saved to the profile's
`minEqPressureHPa`. Rewards the **smallest consistent** pressure — there is
deliberately no reward for higher peaks.

### 5.2 Constant Equalization

Target range derived from the user's `minEqPressureHPa` by difficulty
(asymmetric — sustaining equalization needs less pressure than initiating):

| Difficulty | Lower | Upper |
|---|---|---|
| Easy | 0.4 × minEq | 1.3 × minEq |
| Medium | 0.6 × minEq | 1.1 × minEq |
| Hard | 0.7 × minEq | 1.05 × minEq |
| Expert | 0.8 × minEq | 1.0 × minEq |

Durations: 30 s / 60 s / 2 min / Unlimited. Activation threshold = minEq.
Scored by RangeTracker; final rating from percent-in-range. Overshooting
the range *hurts* the score — precision, not force.

## 6. Games

### 6.1 Shared pressure→position mapping

Pressure maps **only to vertical position** — never to speed, power, size,
or score. `pressureRange`/`negativeRange` come from §2.

Normal mode: `y = 1 − clamp(pressure / pressureRange, 0, 1)`
(0 hPa = bottom, full range = top).

Expert mode (requires `negativeRange > 0`, else fall back to normal):
- `pressure ≥ 0`: `y = 0.5 − clamp(pressure / (2 × pressureRange), 0, 0.5)`
- `pressure < 0`: `y = 0.5 + clamp(−pressure / (2 × negativeRange), 0, 0.5)`
(0 hPa = center; each half maps its own range; top requires exactly
`pressureRange`, bottom exactly `−negativeRange`.)

Player position is exponentially smoothed toward the target:
`y += (targetY − y) × 10 × dt`, with frame `dt` clamped to 0.05 s.
All games gate the start on an established baseline and live pressure and
auto-save a session at game over. Single-player games end with a notice if
the device disconnects; in multiplayer games a disconnect eliminates only
that player and the game continues. Distances below are in dp on a
portrait phone screen; ports should treat them as proportions of a
~400 dp-wide, ~800 dp-tall play area.

### 6.2 Fugu Reef (single player)

Scrolling obstacle pairs with a gap; pass through gaps.
Constants: scroll 120 dp/s × `(1 + score × 0.02)`; gap 0.25 of screen
height (center random in 0.2–0.8); obstacle width 40, spacing 200, first
obstacle +400; fish radius 16, fish at x = 25% of width. Score +1 per
obstacle passed; collision = circle vs. both barrier rectangles.

### 6.3 Multiplayer Fugu Reef (2–7 players)

Same course for all players (no fish-vs-fish collision), each fish driven
by its own device and mapped with **its own user's ranges** (§2). A hit
eliminates that player ("last fugu standing"); survivors keep scoring;
speed scales with the *highest alive* score. Game ends when all are out;
results ranked by score.

### 6.4 Fugu Feast

Eat smaller fish, avoid bigger ones and rocks. Base speed 140 dp/s ×
`(1 + eaten × 0.003)` (= the speed multiplier); player starts at radius
18 dp, +1.5 per fish eaten, capped at 50; enemies 8–70 dp spawning every
0.6 s ÷ speed multiplier; predator chance
`min(0.3 + 0.005 × score, 0.6)`. Fish-vs-fish contact when centers are
closer than `0.7 × (player radius + enemy radius)`; edible iff enemy
radius ≤ player radius; score = fish eaten. Growth is a *consequence* of
score, never of pressure. Rocks: mound on the seabed (at 88% of screen
height) every 3 s, 60–140 dp tall, 96–224 dp wide; the hitbox narrows
with height — half-width at the fish's altitude is
`width × (1 − 0.7 × heightFraction) × 0.45` — and the player collides
when within `0.7 × player radius` of it.

### 6.5 Multiplayer Fugu Feast (2–7 players)

Competitive Feast on one shared screen; all fugus swim at the same x
(25% of width), each driven by its own device with **its own user's
ranges** (§2). Because every player sees the same fish, a fish's color
must mean the same thing for everyone: enemy type is **fixed at spawn** —
prey (green) spawn at ≤ 0.85 × the *smallest* alive player's radius,
predators (red) at ≥ 1.15 × the *largest* alive player's radius (capped
at 70 dp; players cap at 50 dp, so the cap cannot invert the relation).
Collisions resolve by that spawn type, never by live size comparison:
prey are edible by **any** player, predators eliminate **any** player —
so the coloring stays truthful even after players grow past an on-screen
fish. Prey are contested: when several players overlap one fish in the
same frame, the closest mouth eats it (contact threshold, growth, and
predator chance as §6.4). Rocks eliminate on touch (§6.4 geometry).
Speed and predator probability ramp with the *highest alive* score;
enemies spawn faster with more alive players: interval = 0.6 s ÷
(speed multiplier × (1 + 0.2 × (alive − 1))). A disconnected player is
eliminated. Eliminated fugus fade but stay visible; game ends when all
are out; results ranked by fish eaten.

### 6.6 Fugu Cave

Procedurally generated cave corridor; survive as long as possible.
Speed 150 dp/s × `(1 + distance × 0.0008)`; segments every 50 dp; gap
narrows from 0.22 to 0.15 of screen height at 0.00006 per distance point;
wall drift ≤ 0.14 per segment with 0.35 momentum bias; 8 safe starting
segments. Score = distance.

### 6.7 Fugu Flow (rhythm)

A target pressure curve scrolls right-to-left; keep your cursor on it.
Cursor at x = 30% of width; 3 s look-ahead, 2 s look-behind, 3 s grace
before scoring. Distance to the curve is measured as 2D distance (pressure
axis + time axis, time weighted to match visual pixels) to the nearest
curve point within ±1.5 s. Zones and points/second: Perfect ≤ 0.05 → 100;
Good ≤ 0.10 → 60; OK ≤ 0.18 → 20; else Miss (0, combo reset). Combo: ≥1 s
continuously in Perfect/Good raises the multiplier by 1 (max 4×). Score
accumulates fractionally (`pps × combo × dt`) and is displayed truncated —
per-frame integer truncation must be avoided (frame-rate dependence).
Patterns are keyframe lists `(timeSec, targetFraction of pressureRange)`;
built-ins: Gentle Wave, Pulse Train, Staircase, Mountain, Choppy Seas, and
a Random Mix assembled from segments. Patterns are authored against the
normal-mode mapping (fraction 0 = zero pressure); in expert mode (§6.1)
the active pattern is shifted vertically at game start so the midpoint
between its lowest and highest keyframe sits at 0.5 (zero pressure) —
unshifted it would sit entirely in the negative-pressure half.

## 7. Sessions

Exercises and games auto-save a session at completion/game-over. A session
records id (UUID), type, timestamp (epoch ms), duration, device name, user
name (nullable), and the pressure trace cropped to the activity window,
plus type-specific results (min-EQ: peak markers, mean, stddev, counts;
constant-EQ: bounds, activation, scoring start, percent, best streak,
difficulty/duration labels; games: score, ranges, expert flag; multiplayer:
per-player results with rank, color, and trace).

Storage (reference implementation): one JSON file per session plus a
lightweight index for listing; keep the most recent **50** sessions; writes
are atomic (temp file + rename). **The JSON field names are the
interchange format** — sessions are shared between users as these files, so
ports must read/write the same schema. The authoritative schema is
`SessionJson.kt`, locked by round-trip unit tests (`SessionJsonTest.kt`);
key encodings: trace points as `{"p": absolute hPa, "r": relative hPa,
"t": epoch ms}`; unknown session `type` values must load as "unknown"
(skip), never crash — forward compatibility. All pressure-derived values
(trace points, peak markers, means, bounds, ranges, percentages) are
rounded to **3 decimals** on write — 0.001 hPa is ten times finer than the
sensor's 1 Pa resolution, and it keeps files free of floating-point noise.
Readers must not assume more precision than that. Shared session files use
the **`.fugu`** extension (JSON content) and travel as
`application/octet-stream` — the Android app only registers to open that
MIME type, so ports must share sessions the same way to interoperate.

## 8. Safety invariants (every port MUST keep these)

1. Pressure maps to **position only** — never to speed, power, growth, or
   score magnitude.
2. Game ranges derive from calibration per §2, including the manual-range
   clamp.
3. Exercises reward the **minimum consistent** pressure and staying in a
   **sub-maximal band**; overshoot must never score better.
4. Calibration UX must instruct *comfortable* effort, never maximal.
5. No mechanic may compare players by pressure magnitude — only by accuracy.
