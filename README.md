# Lumen

Native Kotlin/Camera2 camera for a Galaxy S21 Ultra with a dead periscope. Phases 1 (diagnostics) and 2 (stable MVP) are implemented. **Never compiled or run by the author** (no build in the authoring environment): CI is the first compile. Send back compiler errors verbatim if any.

## Build
Push to GitHub; Actions (`.github/workflows/build.yml`) builds `app-debug.apk` (artifact `lumen-debug-apk`) and runs `:core:test` (artifact `test-reports`). Install the APK, no INTERNET permission.
Pinned: Gradle 8.9, AGP 8.7.0, Kotlin 2.0.20 (+compose plugin, serialization), Compose BOM 2024.10.00 (Material 3), activity-compose 1.9.3, lifecycle 2.8.7, core-ktx 1.13.1, coroutines 1.8.1, serialization-json 1.7.2, JUnit 4.13.2. compileSdk/targetSdk 35, minSdk 30. No other libraries.

## First run (your test rig checklist)
1. Grant camera permission. App opens **Diagnostics** (no mapping yet).
2. *List IDs*. Switch **Never touch** on any ID you do not want contacted (e.g. one you suspect is the periscope).
3. *Read all*: one ID at a time, crash-guarded. If the app dies mid-read, relaunch: that ID is auto-quarantined (visible in the log).
4. Check each card's suggested role; confirm/correct. Confirming **Periscope** quarantines it.
5. *Probe all in order* (main, front, UW, tele). Each must show VERIFIED.
6. *Share JSON* + *Share summary* and paste them back. Drop the JSON into `core/src/test/resources/fixtures/`.
7. *Open camera*. Test: zoom presets 0.6/1/2/3/10/20/30, pinch, lens pills, flash, EV slider, tap focus, shutter (check Pictures/Lumen, orientation in all 4 holds, front and rear).
8. Open the **Event log** and confirm no line references the periscope ID except QUARANTINE/REFUSE.

## Assumptions (conservative)
- Stale marker quarantines even if the process was merely swiped away mid-open; use *Release* on the card.
- Unlisted physical sub-IDs are read on request but never probed/opened.
- Zoom ceiling per lens = min(`maxDigitalZoom`, 10x for tele / 5x main / 3x UW); tele missing -> main-only ceiling with an on-screen message.
- Native ratios snap to 0.6/1/3 when within 12% of measured, else the measured ratio is used.
- Default still: largest 4:3 JPEG size <= 12.5 MP from the stream map; preview: largest 4:3 <= 1600 px long side.

## Pending on-device verification (nothing below is confirmed)
Whether lenses are standalone IDs; periscope visibility; independent open of each camera; flash/AF behaviour per lens; JPEG orientation on every camera; whether the 3x telephoto offers a 4:3 size near 10 MP.

## Not built (Phases 3-5)
108 MP, video, Pro modes, filters, Night/Portrait, Director's View, etc. They are absent from the UI by design.
