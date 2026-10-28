# 🏀 WristShot

A hands-free basketball shooting tracker for the **Samsung Galaxy Watch 7 (Wear OS 5)**.
It runs entirely on the watch. Its motion sensors will detect your shots, a wrist twist will
label each one make or miss, and each shot's motion will be scored against your own baseline.
It exports CSV/JSON you can analyze yourself or hand to Claude.

> **Current status: Phases 1 and 2 are done.** The watch records raw sensor data plus your
> manual Make/Miss labels, and a Python toolkit turns those recordings into plots, a prototype
> detector and suggested thresholds. Automatic detection (Phase 3) is waiting for your real data.

| Phase | What | Status |
|---|---|---|
| 1 | Raw recorder: sensors → CSV, manual Make/Miss labels, screen-off recording | ✅ built |
| 2 | Python analysis: plots, prototype detector, precision/recall, threshold suggestions | ✅ built |
| 3 | On-device shot detection state machine, haptics, debug screen, CSV-fixture tests | ⏳ needs your recordings |
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
│       └── ui/                      UI LAYER: Home screen, Make/Miss screen, Theme (colors)
├── core/                     Pure Kotlin (no Android). Unit-testable on any computer.
│   └── src/main/kotlin/dev/malzbro/wristshot/core/
│       ├── config/RecorderConfig.kt  every recorder number (sample rate, buffers, …)
│       ├── sensor/StreamAligner.kt   merges accel/gyro/gravity/rotation by timestamp
│       ├── csv/ session/ model/      file formats, metadata, counters
│       ├── util/TimeFormat.kt        "01:15" style timer text
│       └── (Phase 3: detector/ goes here, with its own DetectorConfig)
├── analysis/                 Python tools (Phase 2)
│   ├── analyze_session.py           main script: timing check, plots, detector, thresholds
│   ├── detector_proto.py            prototype detector + matching + threshold suggestions
│   ├── wristshot_io.py              loads the CSVs, adds magnitudes / pitch
│   ├── plots.py                     overview and shot-snippet figures
│   ├── make_synthetic_session.py    FAKE data, only to check the scripts run
│   └── tests/                       pytest tests (run: python -m pytest)
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

`data/` is in `.gitignore`, so recordings don't end up on GitHub by accident.

**Size:** about 35–40 MB of raw CSV per hour at 100 Hz.

---

## 7. File formats

Every session writes three files with the same prefix `session_YYYYMMDD_HHMMSS`.

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

### `_meta.json`

Device model, Android version, start time, sensor names/ranges, sample count, **mean sample
rate**, **max gap** and **dropouts** (gaps >100 ms), plus make/miss totals. It's written when
the session stops normally.

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
2. **Detector with current params.** The prototype detector (dip → drive → wrist-flick
   release → follow-through hold) with **initial guess** thresholds, scored against your labels:
   detected, true/false positives, missed, **precision**, **recall**, F1.
3. **Your labeled shots vs. background motion.** For each label, the biggest wrist-flick peak
   in the 6 s before it is taken as the release. The table shows feature percentiles for those
   shots next to all other wrist peaks (dribbling, walking, fidgeting).
4. **Suggested thresholds.** Values derived from that comparison, a sweep of the main release
   threshold, and the score with the suggestions. They print as Kotlin `const val` lines ready
   for Phase 3.

Outputs are saved to `analysis/output/<session>/`:
* `overview.png`: |accel|, |gyro| and forearm pitch over the whole session, with your labels
  and the detections drawn on top.
* `shot_snippets.png`: all labeled shots overlaid, aligned at the release. Tight bundles of
  lines mean consistent motion.
* `labeled_shot_features.csv`, `detections.csv`, `suggested_params.txt`.

Override any detector parameter from the command line to experiment, e.g.
`--release-gyro-min 8 --drive-accel-min 14 --hold-min-s 0.1`.
`--label-before 8` lets labels come up to 8 s after the release (useful if you walk to rebound
before tapping).

> ⚠️ **No threshold in this repo is validated yet.** Every value in `DetectorParams`
> (`analysis/detector_proto.py`) is an initial guess, and the code says so. Use the suggestions
> from **your** recordings, and check them on a second session they weren't derived from.

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

### Phase 1 check on the watch

- [ ] App installs and opens. Start → Make/Miss screen appears, the notification shows, and an
      ongoing-activity icon with a running timer appears on the watch face.
- [ ] Tapping MAKE/MISS/Shot? buzzes and the counter goes up.
- [ ] Turn the screen off (cover it with your palm) for a few minutes mid-session. Recording
      continues: after stopping, *Last session* shows **0 gaps** and ~100 Hz.
- [ ] Stop from the notification works too.
- [ ] Record **10 minutes**, pull the files, run `analyze_session.py`. Section 1 says
      `-> timestamps line up`. **This is the Phase 1 acceptance test.**
- [ ] Optional: a **1-hour** session to check battery drain (note the battery % before and after).

### Recordings to bring back for Phase 3 tuning

Wear the watch the way you always will (right wrist, same strap hole, same orientation) and
write that down. Then record:

| Session | What to do | Why |
|---|---|---|
| **A: Shooting** (15–20 min) | 40–60 normal shots from mixed spots and distances. Rebound, dribble, walk back between shots as you normally would. **Label every shot** (Make/Miss) right after you see the result. | Real shot motion vs. everything around it |
| **B: No shooting** (5–10 min) | Dribbling drills, passing, catching, walking, jogging, retying shoes. **No labels.** | Measures false positives |
| **C: Shooting again** (10 min, another day if possible) | Same as A | Checks the suggested thresholds on data they weren't tuned on |

Send back, for each session:
1. The three files (`_raw.csv`, `_events.csv`, `_meta.json`). Zip them if they're big.
2. The full printout of `python analyze_session.py <session>`.
3. `overview.png` and `shot_snippets.png`.
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
