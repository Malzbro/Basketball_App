# 🏀 WristShot

A hands-free basketball shooting tracker for the **Samsung Galaxy Watch 7 (Wear OS 5)**.
It runs entirely on the watch. Its motion sensors will detect your shots, a wrist twist will
label each one make or miss, and each shot's motion will be scored against your own baseline.
It exports CSV/JSON you can analyze yourself or hand to Claude.

> **Current status: Phases 1–3 are done.** The watch records raw sensor data and your Make/Miss
> labels, and **detects your shots live** (a short buzz per shot) with a detector tuned on your
> first real session: 139 of 144 shots found. The Python toolkit plots sessions and re-checks
> the detector. **New to the graphs? Start with [docs/reading_the_plots.md](docs/reading_the_plots.md);
> to build them yourself in Excel, see [docs/excel_guide.md](docs/excel_guide.md).**

| Phase | What | Status |
|---|---|---|
| 1 | Raw recorder: sensors → CSV, manual Make/Miss labels, screen-off recording | ✅ built |
| 2 | Python analysis: plots, prototype detector, precision/recall, threshold suggestions | ✅ built |
| 3 | On-device shot detection state machine, haptics, debug screen, CSV-fixture tests | ✅ built (F1 0.90 on your session) |
| 4 | Make/miss by wrist twist (pronation / supination), calibration, corrections | ⏳ |
| 5 | Form score (0–100) vs your personal baseline, session consistency | ⏳ |
| 6 | Shot location: drill mode and free mode | ⏳ |
| 7 | shots.csv + session_summary.json export, last-session stats screen | ⏳ |

### What a wrist sensor can and can't know

The watch measures **your wrist only**: acceleration, rotation speed and orientation. It cannot
see your elbow, legs, release height, ball arc, or the rim. So in this app:

* **"Form"** means *how closely this shot's wrist motion matches your own baseline motion*. It
  measures consistency. It doesn't judge technique.
* **Make/miss** is always something you tell the watch. The motion data is never used to guess it.
* **Shot location** can't come from the sensors. You tell the app (drill mode, Phase 6).

---

## Contents

1. [Project layout](#1-project-layout)
2. [One-time setup on your computer](#2-one-time-setup-on-your-computer)
3. [One-time setup on the watch (developer mode + wireless ADB)](#3-one-time-setup-on-the-watch)
4. [Build and install](#4-build-and-install)
5. [Recording a session](#5-recording-a-session)
6. [Getting the files off the watch](#6-getting-the-files-off-the-watch)
7. [File formats](#7-file-formats)
8. [Analyzing a session (Phase 2)](#8-analyzing-a-session-phase-2)
9. [What to test now and what data to send back](#9-what-to-test-now-and-what-data-to-send-back)
10. [How to analyze with Claude](#10-how-to-analyze-with-claude)
11. [Battery and reliability notes](#11-battery-and-reliability-notes)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Project layout

```
WristShot/
├── app/                      Wear OS app (Kotlin + Jetpack Compose for Wear OS)
│   └── src/main/java/dev/malzbro/wristshot/
│       ├── MainActivity.kt          entry point, stem-button handling, permission prompt
│       ├── config/AppConfig.kt      UI / Android settings (one place)
│       ├── sensors/                 SENSOR LAYER: SensorManager → aligned samples
│       ├── recording/               STORAGE + SERVICE: foreground service, notification,
│       │                            recorder thread, SessionRepository (UI ↔ service state)
│       └── ui/                      UI LAYER: Home, Make/Miss and Detector debug screens, Theme
├── core/                     Pure Kotlin (no Android). Unit-testable on any computer.
│   └── src/main/kotlin/dev/malzbro/wristshot/core/
│       ├── config/RecorderConfig.kt  every recorder number (sample rate, buffers, …)
│       ├── sensor/StreamAligner.kt   merges accel/gyro/gravity/rotation by timestamp
│       ├── csv/ session/ model/      file formats, metadata, counters
│       ├── util/TimeFormat.kt        "01:15" style timer text
│       ├── detector/                 ShotDetector (live state machine) + DetectorConfig (all thresholds)
├── analysis/                 Python tools (Phase 2)
│   ├── analyze_session.py           main script: timing check, plots, detector, thresholds
│   ├── detector_proto.py            prototype detector + matching + threshold suggestions
│   ├── wristshot_io.py              loads the CSVs, adds magnitudes / pitch
│   ├── plots.py                     overview and shot-snippet figures
│   ├── make_synthetic_session.py    FAKE data, only to check the scripts run
│   └── tests/                       pytest tests (run: python -m pytest)
├── recordings/               real sessions kept as test data (the detector tests run on these)
├── docs/reading_the_plots.md how to read every graph, with your data
├── docs/excel_guide.md       make the same graphs yourself in Excel
├── excel/                    ready-to-open Excel files (all shots overlaid)
├── scripts/pull_sessions.sh  copies sessions from the watch to ./data/sessions
└── gradle/libs.versions.toml all library versions in one place
```

**Why two modules?** Everything that is just math or file formatting goes in `:core`, which has
no Android code. You can test it in a second with `./gradlew :core:test`. The Phase 3 detector
will live there too, so it can be tested against your recorded CSVs without a watch.

---

## 2. One-time setup on your computer

1. **Install Android Studio** (latest stable) from <https://developer.android.com/studio>.
   During the first-run wizard choose *Standard*. It installs the Android SDK, a JDK and `adb`.
2. **Install the SDK platform.** In Android Studio go to *Settings → Languages & Frameworks →
   Android SDK*:
   * *SDK Platforms* tab: check **Android 15 (API 35)**. The app compiles against 35.
   * *SDK Tools* tab: make sure **Android SDK Platform-Tools** is checked (that's `adb`).
3. **Put `adb` on your PATH** so you can use it from a terminal. It lives in:
   * Windows: `%LOCALAPPDATA%\Android\Sdk\platform-tools`
   * macOS: `~/Library/Android/sdk/platform-tools`
   * Linux: `~/Android/Sdk/platform-tools`

   Check it works:
   ```bash
   adb version
   ```
4. **Python 3.10+** for the analysis tools (see [section 8](#8-analyzing-a-session-phase-2)).

---

## 3. One-time setup on the watch

### 3.1 Turn on developer mode

1. On the watch: **Settings → About watch → Software information**.
2. Tap **Software version** about 5 times until it says *Developer mode turned on*.
3. Go back to **Settings**. A new **Developer options** menu is at the bottom.
4. In **Developer options**, turn on **ADB debugging**.

### 3.2 Connect the watch to Wi-Fi

* **Settings → Connections → Wi-Fi**: join the **same network as your computer**.
* Tip: the watch prefers Bluetooth to your phone and may turn Wi-Fi off. While pairing and
  installing, **turn the watch's Bluetooth off** (or move the phone away) so Wi-Fi stays up.
* Some school/campus networks block device-to-device traffic. If pairing never works, use a
  phone hotspot that both the computer and the watch join.

### 3.3 Pair over wireless debugging (only needed once per computer)

1. On the watch: **Developer options → Wireless debugging → On**, then tap **Pair new device**.
   The watch shows a **pairing code** (6 digits) and an **IP address & port**, e.g. `192.168.1.42:37155`.
2. On the computer (use the values shown on *your* watch):
   ```bash
   adb pair 192.168.1.42:37155 123456
   ```
   You should see `Successfully paired`.
3. Go back one screen on the watch. The **Wireless debugging** screen shows another
   *IP address & port*, e.g. `192.168.1.42:41235`. **This port is different from the pairing port.**
   Connect to it:
   ```bash
   adb connect 192.168.1.42:41235
   ```
4. Check:
   ```bash
   adb devices
   ```
   The watch should be listed as `device`. If it says `unauthorized`, accept the prompt on the watch.

After the first pairing you normally only repeat step 3 (`adb connect …`). The port changes
every time Wireless debugging is turned on, so read it off the watch each time.

> Android Studio can do the same thing from **Device Manager → Pair Devices Using Wi-Fi →
> Pair using pairing code**. The terminal commands above are usually more reliable for watches.

---

## 4. Build and install

### From Android Studio (easiest)

1. **File → Open** and choose this folder. Wait for *Gradle sync* to finish (first time takes a while).
2. In the toolbar, pick the run configuration **app** and your watch (it appears once
   `adb connect` worked).
3. Click **Run ▶**. The app installs and opens on the watch.

### From a terminal

Run Gradle commands **from the project folder** (the one containing `gradlew` and
`settings.gradle.kts`), not from your home folder. Easiest: in Android Studio open the
**Terminal** tab at the bottom, which already starts in the project folder.

**Windows (PowerShell):** PowerShell only runs scripts in the current folder if you prefix them with `.\`:
```powershell
cd C:\path\to\Basketball_App
```
```powershell
.\gradlew.bat :app:installDebug
```

**macOS / Linux:**
```bash
./gradlew :app:installDebug
```

Run the pure-Kotlin unit tests (no watch needed), `.\gradlew.bat :core:test` on Windows:
```bash
./gradlew :core:test
```

`installDebug` installs to whatever `adb devices` lists, so connect the watch first (section 3.3).

> **Library versions** are all in `gradle/libs.versions.toml`. If Android Studio offers to
> upgrade the Android Gradle Plugin or a library, it's safe to accept, then sync again.

### Permissions on first launch

* **Notifications → Allow.** This shows the "Recording session" notification and the
  *ongoing activity* icon on the watch face. Recording still works if you deny it, but you
  lose the quick way back into the app.
* Nothing else is asked. `HIGH_SAMPLING_RATE_SENSORS` and the foreground-service permissions
  are granted at install time. Android 14 needs one of a few permissions before an app can run
  a "health" foreground service, and `HIGH_SAMPLING_RATE_SENSORS` is the lightest one. It doesn't
  give access to heart rate or other body sensors. The app samples at 100 Hz, well under the
  200 Hz limit that permission lifts.

---

## 5. Recording a session

1. Open **WristShot**. Tap **Start session**. The app goes straight to the **Make / Miss** screen.
2. After each shot, once you see the result, tap:
   * the **top half (green, MAKE)**, or
   * the **bottom half (red, MISS)**, or
   * **Shot?** in the middle if you didn't see the outcome.

   The watch buzzes briefly to confirm, and the half you pressed flashes.
3. Tap **Done** (or swipe right) to go back to the home screen: timer, shot count, Stop.
4. Tap **Stop session** on the home screen, or **Stop** in the notification.

Details:

* **Screen off is fine.** Recording runs in a foreground service and keeps going with the
  screen off or dimmed. While the Make/Miss screen is open during a session, the app keeps the
  display on so you can label quickly (`AppConfig.KEEP_SCREEN_ON_WHILE_LABELING`). Leave that
  screen to save battery.
* **Hardware button.** Watches with programmable "stem" buttons mark a shot (`Shot?`) when you
  press one. The Galaxy Watch 7's two buttons are *Home* and *Back*, so instead there's a
  home-screen toggle **"Back key = shot"**. When it's on, pressing **Back** on the Make/Miss
  screen records a `Shot?` instead of leaving. Use **Done** or swipe right to leave.
* The **Last session** box on the home screen shows duration, shots, average sample rate and
  the number of *gaps* (moments the watch stopped delivering data for >100 ms). It should be 0.
* **Automatic shot detection (Phase 3).** While recording, the watch buzzes once (short) about
  1–2 s after each shot it detects, when your arm comes down from the follow-through. The
  home screen shows **Detected N** next to your labeled count. Keep labeling Make/Miss as
  before: the labels are how we check the detector, and the twist gesture (Phase 4) replaces
  the buttons later.
* **Detector debug screen.** Home → **Detector debug** shows, live: **ARM UP** while your arm
  is above the −45° line, the current arm angle, detected vs labeled counts, and the last arm
  raise with its numbers (flick, spike, hold) next to each threshold, plus why it didn't count
  if it didn't (`short_hold`, `weak_flick`, `no_accel_spike`, `too_soon`).
* **Uninstalling the app deletes all recordings.** Pull them first ([section 6](#6-getting-the-files-off-the-watch)).
  Reinstalling or updating from Android Studio keeps them.

---

## 6. Getting the files off the watch

Sessions are saved on the watch in:

```
/sdcard/Android/data/dev.malzbro.wristshot/files/sessions/
```

With the watch connected (`adb devices` lists it):

List the sessions on the watch:
```bash
adb shell ls -l /sdcard/Android/data/dev.malzbro.wristshot/files/sessions/
```

Copy every session to `./data/sessions/` on your computer:
```bash
adb pull /sdcard/Android/data/dev.malzbro.wristshot/files/sessions ./data
```

Copy a single session (all three files) into a folder you created first, e.g. `data/sessions`:
```bash
adb pull /sdcard/Android/data/dev.malzbro.wristshot/files/sessions/session_20261028_181502_raw.csv ./data/sessions/
```
```bash
adb pull /sdcard/Android/data/dev.malzbro.wristshot/files/sessions/session_20261028_181502_events.csv ./data/sessions/
```
```bash
adb pull /sdcard/Android/data/dev.malzbro.wristshot/files/sessions/session_20261028_181502_meta.json ./data/sessions/
```

Or use the helper script (macOS/Linux/Git Bash):
```bash
scripts/pull_sessions.sh
```

Free up space on the watch after you've copied a session:
```bash
adb shell rm /sdcard/Android/data/dev.malzbro.wristshot/files/sessions/session_20261028_181502_*
```

`data/` is in `.gitignore`, so recordings don't end up on GitHub by accident. Sessions you want
to keep as test data go in `recordings/` (committed). A zipped raw file (`_raw.zip`) works too:
the analysis reads it directly.

**Size:** about 35–40 MB of raw CSV per hour at 100 Hz.

---

## 7. File formats

Every session writes four files with the same prefix `session_YYYYMMDD_HHMMSS`.

### `_raw.csv`: one row per accelerometer sample (~100 per second)

| column | unit | meaning |
|---|---|---|
| `t_ns` | ns | sensor clock (`SystemClock.elapsedRealtimeNanos`), **the same clock as the events file** |
| `t_rel_s` | s | seconds since you pressed Start |
| `ax, ay, az` | m/s² | accelerometer, **including gravity** (≈ 9.8 total when still) |
| `gx, gy, gz` | rad/s | gyroscope (rotation speed around each watch axis) |
| `grav_x, grav_y, grav_z` | m/s² | gravity direction (Android's fused *gravity* sensor) |
| `qx, qy, qz, qw` | – | orientation quaternion (*game rotation vector*: no compass, so the steel in a gym can't disturb it) |

Android delivers each sensor separately and in batches. The recorder therefore **aligns** the
gyro, gravity and quaternion values to each accelerometer timestamp by linear interpolation
(`core/…/StreamAligner.kt`). Every row describes one instant. A missing sensor is written as `NaN`.

Watch axes, with the watch face toward you: **x** points toward 3 o'clock, **y** toward 12
o'clock, **z** out of the screen. Worn on the wrist, **x runs along your forearm**. That makes
`gx` the forearm-twist axis (Phase 4) and `grav_x` a measure of forearm pitch. Whether x points
toward your hand or your elbow depends on the wrist and the watch's orientation setting. The
Phase 4 calibration step will handle that.

### `_events.csv`: one row per label

| column | meaning |
|---|---|
| `t_ns`, `t_rel_s` | when you tapped, same clock as the raw file |
| `wall_time` | the watch's local date and time, for reading |
| `label` | `make`, `miss` or `shot` (= *Shot?*, outcome unknown) |
| `source` | `button` (screen) or `hardware_key` |

### `_detections.csv`: one row per arm raise the live detector evaluated

| column | meaning |
|---|---|
| `t_ns`, `t_rel_s` | release time (the flick), same clock as the other files |
| `is_shot` | `true` if it counted as a shot |
| `reject_reason` | empty for a shot, else e.g. `short_hold+weak_flick` |
| `peak_gyro` | release flick, rad/s |
| `release_accel` | peak acceleration within 0.3 s after the flick, m/s² |
| `hold_s` | how long the arm stayed raised (follow-through), s |
| `min_pitch_deg` | highest arm position reached (most negative angle), ° |
| `peak_accel_drive`, `min_accel_dip`, `dip_to_release_s` | motion before the release (for form scoring later) |

The same columns as the Python analysis, so you can compare them directly.

### `_meta.json`

Device model, Android version, start time, sensor names/ranges, sample count, **mean sample
rate**, **max gap** and **dropouts** (gaps >100 ms), make/miss totals, **`detected_shots`**,
and the **`detector`** thresholds that were used. It's written when the session stops normally.

---

## 8. Analyzing a session (Phase 2)

Set up Python once:

```bash
cd analysis
```
```bash
python3 -m venv .venv
```
```bash
source .venv/bin/activate
```
(On Windows: `.venv\Scripts\activate`.)
```bash
pip install -r requirements.txt
```

Run it on a session (any of the three files, the prefix, or a folder for the newest session):

```bash
python analyze_session.py ../data/sessions
```
```bash
python analyze_session.py ../data/sessions/session_20261028_181502_raw.csv --show
```

`--show` opens interactive windows where you can zoom into individual shots.

It prints four sections:

1. **Timing check.** Sample rate, gaps, and whether raw and event timestamps share the same
   clock and the events fall inside the recording. `-> timestamps line up` means the Phase 1
   acceptance test passed.
2. **Detector with current params.** The prototype detector, scored against your labels:
   detected, true/false positives, missed, **precision**, **recall**, F1.
3. **Your labeled shots vs. other arm raises.** For each label, the longest arm raise before it
   is taken as the shot. The table shows feature percentiles for those shots next to every other
   arm raise (rebounds, reaching up, unlabeled shots).
4. **Suggested thresholds.** Values that let nearly all of your labeled shots through, a sweep
   of the release threshold, and the score with the suggestions. They print as Kotlin
   `const val` lines ready for Phase 3.

Outputs are saved to `analysis/output/<session>/`. **How to read each one, with your own
data: [docs/reading_the_plots.md](docs/reading_the_plots.md).**
* `shot_anatomy.png`: one typical shot, annotated with every number the detector checks.
* `feature_histograms.png`: your shots vs other arm raises for each check, with the thresholds.
* `overview_zoom.png`: the first 3 minutes (or `--zoom START END`); `overview.png`: whole session.
* `shot_snippets.png`: all labeled shots overlaid, lined up at the release. Tight bundles of
  lines mean consistent motion.
* `labeled_shot_features.csv` and `other_arm_raises.csv` (one row per raise, open them in
  Excel), `detections.csv`, `suggested_params.txt`.

Override any detector parameter from the command line to experiment, e.g.
`--raise-pitch-max-deg -40 --hold-min-s 0.5 --release-accel-min 30`.
`--label-before 8` lets labels come up to 8 s after the release (useful if you walk to rebound
before tapping).

### How the detector works (from your first real session)

`recordings/session_20261030_163316` (43 min, 144 labeled shots, watch on the right wrist)
showed what a jump shot looks like at the wrist:

1. **Release:** a sharp wrist flick (about 12 rad/s) and acceleration spike as the arm goes up.
2. **Follow-through:** forearm pitch drops to about **−60°** and **stays there for about 1.3 s**.
3. The arm comes down. In Phase 1 you then tapped the label about 2.2 s after the release.

Dribbling, catching and walking also make big flicks and spikes, but with the forearm pointing
down (pitch around +40°). So the detector looks for **a raised arm that is held**, and uses the
flick and spike only to confirm it:

| Parameter | Value | Meaning |
|---|---|---|
| `raise_pitch_max_deg` | −45° | arm counts as raised below this pitch |
| `hold_min_s` | 0.3 s | arm must stay raised at least this long |
| `release_gyro_min` | 8 rad/s | wrist flick within 0.5 s before the arm goes up |
| `release_accel_min` | 20 m/s² | acceleration spike right after the flick |
| `min_quiet_s` | 2 s | minimum time between shots |

On that session: **139 of 144 shots detected (recall 97%), precision 85%, F1 0.90**. Tuned on
the first half and tested on the second half, it scored F1 0.91, so it isn't just memorizing one
stretch. Several of the "false positives" look exactly like shots and are probably shots that
weren't labeled.

> ⚠️ **Tuned on one session, by one shooter.** The pitch sign depends on which wrist the watch
> is on and which way round it's worn (Phase 4 adds a calibration step). Check every new session
> with `analyze_session.py`. `python -m pytest` fails if F1 on the recorded session drops below 0.85.

To check the scripts run without a watch, generate a **fake** session (invented motion, never use
it to pick thresholds):
```bash
python make_synthetic_session.py --out ../data/synthetic
```
```bash
python analyze_session.py ../data/synthetic
```

Run the analysis tests (from the `analysis/` folder):
```bash
python -m pytest
```

---

## 9. What to test now and what data to send back

### Phase 3 check on the watch (do this next)

- [ ] Rebuild and install (Run ▶). On the first session after updating, nothing new is asked.
- [ ] Open **Detector debug** while recording. Raise your arm in a shooting follow-through:
      it shows **ARM UP** and the angle goes below −45°. Arm down: **idle**.
- [ ] Shoot normally. About 1–2 s after each shot (when your arm comes down) the watch gives
      one short buzz, and **Detected** goes up. Keep labeling Make/Miss as before.
- [ ] Dribble, pass and walk for a few minutes without shooting: the watch should (almost)
      never buzz. If it does, check the debug screen's last raise to see why it counted.
- [ ] Record a normal session (20+ minutes, labeling every shot), pull all four files and
      run `analyze_session.py` on it. Compare `detected_shots` in `meta.json` with your
      label count.

### Phase 1 check on the watch (done)

- [ ] App installs and opens. Start → Make/Miss screen appears, the notification shows, and an
      ongoing-activity icon with a running timer appears on the watch face.
- [ ] Tapping MAKE/MISS/Shot? buzzes and the counter goes up.
- [ ] Turn the screen off (cover it with your palm) for a few minutes mid-session. Recording
      continues: after stopping, *Last session* shows **0 gaps** and ~100 Hz.
- [ ] Stop from the notification works too.
- [ ] Record **10 minutes**, pull the files, run `analyze_session.py`. Section 1 says
      `-> timestamps line up`. **This is the Phase 1 acceptance test.**
- [ ] Optional: a **1-hour** session to check battery drain (note the battery % before and after).

### Recordings to bring back

The first shooting session is in `recordings/`. What helps most now:

Wear the watch the way you always will (right wrist, same strap hole, same orientation) and
write that down. Then record:

| Session | What to do | Why |
|---|---|---|
| **A: Shooting** (15–20 min) | 40–60 normal shots from mixed spots and distances. Rebound, dribble, walk back between shots as you normally would. **Label every shot** (Make/Miss) right after you see the result. | Real shot motion vs. everything around it |
| **B: No shooting** (5–10 min) | Dribbling drills, passing, catching, walking, jogging, retying shoes. **No labels.** | Measures false positives |
| **C: Shooting again** (10 min, another day if possible) | Same as A | Checks the thresholds on data they weren't tuned on |

Send back, for each session:
1. All four files (`_raw.csv`, `_events.csv`, `_detections.csv`, `_meta.json`). Zip the raw
   CSV if it's over 25 MB (the GitHub website limit); the analysis reads `_raw.zip` directly.
2. The full printout of `python analyze_session.py <session>`.
3. `shot_anatomy.png`, `feature_histograms.png` and `shot_snippets.png`.
4. Notes: wrist and orientation, any shots you forgot to label or mislabeled, and anything odd
   (e.g. "minutes 4–5 I was just shooting free throws").

---

## 10. How to analyze with Claude

What to upload today (Phase 1/2):
* `session_…_events.csv` and `session_…_meta.json` (small).
* `analysis/output/<session>/labeled_shot_features.csv` and the two PNGs.
* The raw CSV only if Claude can take the file size. Otherwise describe it, or send a section
  cut around a few shots.

Good questions to ask:
* "Here's my session. What's my FG% overall and how did it change from the first half to the second?"
* "Using `labeled_shot_features.csv`, do my makes and misses differ in any wrist feature?
  Be careful about sample size."
* "Look at `shot_snippets.png`. Which part of my wrist motion is least consistent?"
* "These are the suggested detector thresholds and the precision/recall. What would you change?"

From Phase 7 the app will export `shots.csv` (one row per shot with spot, label, form score and
features) and `session_summary.json` (totals, FG% by spot, FG% for high vs low form score). Those
two files will be all you need to upload.

---

## 11. Battery and reliability notes

* **Batching:** sensors are sampled at 100 Hz, and the sensor hardware can hold samples for up
  to 200 ms (`RecorderConfig.MAX_REPORT_LATENCY_US`) before handing them over in one burst.
* **Buffered writes:** rows collect in a 64 KB memory buffer and are written in chunks, with a
  forced flush every 2 s (so a crash loses at most ~2 s). Labels are written to storage immediately.
* **Wake lock:** with the screen off, the watch CPU would go to sleep and samples would be lost.
  The app holds a *partial* wake lock (CPU only, never the screen) **only while recording**. It's
  released on Stop, and it has a 3-hour safety timeout in case you forget to stop.
* **Graceful stop:** Stop unregisters the sensors, writes the remaining samples, closes both
  CSVs and writes `meta.json`. The same happens if Android shuts the service down.
* All of these numbers are in `core/…/config/RecorderConfig.kt` and `app/…/config/AppConfig.kt`.

---

## 12. Troubleshooting

| Problem | Fix |
|---|---|
| `adb pair` times out | Same Wi-Fi? Watch Bluetooth off? Try a phone hotspot. Re-open *Pair new device* for a fresh code. |
| `adb connect` fails after it worked before | The port changes every time Wireless debugging is turned on. Read the new one off the watch. |
| Watch disappears from `adb devices` | It turned Wi-Fi off to save power. Wake it, check Wi-Fi, `adb connect` again. |
| Gradle sync fails on a version | Accept Android Studio's upgrade suggestion, or bump the version in `gradle/libs.versions.toml`. |
| No notification / ongoing icon | You denied notifications. Watch *Settings → Apps → WristShot → Notifications*. |
| `meta.json` shows dropouts > 0 | Battery saver or a power mode may be limiting background work. Turn power saving off for the session. |
| `adb pull` says *Permission denied* | Use the exact path above. If it still fails, tell me your Wear OS version. |
