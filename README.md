# Lumen

Native Kotlin/Camera2 camera for a Galaxy S21 Ultra with a dead periscope. Phases 1 (diagnostics), 2 (stable MVP) and 3 (Drop A: aspect ratios, timer, front/rear flip, filters, Motion Photo) are implemented. Phase 3 code has **never been compiled or run by the author** (no build in the authoring environment): CI is the first compile. Send back compiler errors verbatim if any.

## Build
Push to GitHub; Actions (`.github/workflows/build.yml`) builds `app-debug.apk` (artifact `lumen-debug-apk`) and runs `:core:test` (artifact `test-reports`). Install the APK, no INTERNET permission.
Pinned: Gradle 8.9, AGP 8.7.0, Kotlin 2.0.20 (+compose plugin, serialization), Compose BOM 2024.10.00 (Material 3), activity-compose 1.9.3, lifecycle 2.8.7, core-ktx 1.13.1, exifinterface 1.3.7 (new in Phase 3: copies EXIF after a filter re-encode), coroutines 1.8.1, serialization-json 1.7.2, JUnit 4.13.2. compileSdk/targetSdk 35, minSdk 30. No other libraries.

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

## Phase 3, Drop A (what this phone's own snapshot allows)
Device facts from your `lumen-snapshot.json` (data, not assumed): IDs 0 main, 1 front, 2 ultra-wide, 3 second front (unclassified); **no 3x telephoto, no periscope, no logical camera exposed**; no max-resolution (108MP) sizes and no ULTRA_HIGH_RES capability; largest video size 3840x2160 and AE fps ranges top out at 30; concurrent sets only (0,1) and (0,3).
Consequences: zoom above 1x on this firmware is a digital crop of the main camera (device cap 8x; app cap 5x); 108MP, 8K and 60fps are not offered because the API does not expose them.

| Feature | How it works | Gated by |
|---|---|---|
| Aspect 4:3 / 16:9 / 1:1 / Full(20:9) | Real JPEG size per aspect from the stream map (<= 12.5MP); preview and clip match. Tap-to-focus accounts for the cropped view. Changing it reopens the camera (brief scrim). | >= 2 aspects in `jpegSizes` |
| Timer Off/2/5/10s | Countdown on the camera thread; shutter tap cancels. Cleared on any lens/format change. | always |
| Flip | Rear <-> front through the normal guarded open; front preview is mirrored, saved photo is not. | verified front + verified rear |
| Filters (8) | Live: `TextureView.setLayerPaint` colour matrix. Saved: decode -> same matrix -> JPEG q95 -> EXIF copied. Matrices live in :core. | always |
| Motion Photo | H.264 surface encoder keeps the last ~4s; shutter cuts 1.5s before + 1.5s after, muxes MP4, appends it to the JPEG with Google Motion Photo XMP (container v1 + MicroVideo tags). Off by default. | 30fps + matching video size |
| Storage | Photos and Motion Photos go to `DCIM/Camera` via MediaStore (`LUMEN_*.jpg`, `_MP` suffix for Motion Photo). | - |

Safety: no new camera-open path. Aspect/Motion/flip changes call the same `openRole` -> `CameraGate.open`. If a session with the Motion Photo stream fails to configure, Motion Photo is switched off for that camera id (not counted as a camera failure) and the session is rebuilt once without it.

### Phase 3 test checklist (nothing below is confirmed yet)
1. Cycle the aspect pill on rear 1x and on the ultra-wide: preview shape changes, photo size matches (check Details in Gallery: 12MP, 9MP, 9MP, 7.2MP).
2. In 16:9/Full, tap-to-focus near the edges of the visible preview.
3. Timer 5s: countdown shows, second tap cancels, photo taken at 0.
4. Flip to front and back; selfie preview is mirrored, saved photo is not; pills/zoom hidden on front.
5. Each filter: preview tint matches the saved JPEG; orientation correct in all 4 holds; EXIF date/model present.
6. Motion Photo on main, ultra-wide and front: does the session configure? (Ultra-wide is LIMITED hardware level; if it fails you get a notice and Motion turns off there.) Does Google Photos play it? Does Samsung Gallery? (Samsung Gallery may show a still: it has its own trailer format, which is not implemented.)
7. Send back the Event log if anything fails.

## Not built (Phases 4-5)
Video (4K/FHD 30), Pro and Pro Video, Night, Portrait, Single Take, More tab, PiP navigator, macro auto-switch, scene indicator, Director's View. Absent from the UI by design.
