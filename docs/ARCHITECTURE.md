# Lumen architecture (Phases 1-3)

**Goal:** a Camera2 app that can never touch the dead 10x periscope of a Galaxy S21 Ultra. Everything device-specific comes from runtime data.

## Modules
- `:core` (pure Kotlin/JVM, unit-tested): `Model` (snapshot JSON = in-app export), `Classifier` (role from 35mm-equivalent ratios vs. the largest-sensor back camera; never from IDs), `Gate` (`GateCore`: allowlist / quarantine / never-touch / crash marker state machine), `Zoom` (pure routing + hysteresis), `Recovery` (bounded restart -> fallback -> give up), `Sizes`, `Capabilities`, `Summary`.
- `:app`: `CameraGate` (only file allowed to call `openCamera(`, `setPhysicalCameraId(`, `getCameraCharacteristics(`; Gradle task `checkGateInvariant` fails the build otherwise), `Diagnostics` + `Prober` (Phase 1), `CameraController` (Phase 2, single camera thread + generation counter), Compose UI, storage behind `GateStore`/`SnapshotStore`.

## Safety design
1. Every touch = `GateCore.begin(id, purpose)` -> refusal or a **durable marker** (`commit()` on a background executor) -> touch -> `end()`. Marker present at launch => that ID is quarantined, never auto-retried.
2. Allowlist = positive: `verified` is added only by a successful PROBE (open, 5 frames, clean close). OPEN requires verified + confirmed usable role (UW/Main/Tele/Front). Quarantine and never-touch override everything. PERISCOPE confirmation auto-quarantines; UI has no probe for it.
3. Logical cameras and unlisted sub-IDs are never opened in Phase 2 (`Role.LOGICAL` not usable). Residual risk if a lens is reachable only via logical: not supported yet (see open questions).
4. Cache keyed on `Build.FINGERPRINT`; changed fingerprint voids verification and mapping, keeps quarantine/never-touch. Later launches read **no** characteristics.
5. All events -> logcat tag `CamGuard` + in-app last-200 screen.

## Camera pipeline
Preview (TextureView, 4:3) + JPEG ImageReader in one `SessionConfiguration`. Zoom is `SCALER_CROP_REGION` (centered crop) on the routed physical camera; `CONTROL_ZOOM_RATIO` is never used (it could make a logical camera hop sensors). Lens change = close + reopen with a scrim (no frozen frame). Callbacks of superseded devices are dropped via `gen`.

## Open questions the diagnostics must answer
1. Are UW/main/tele exposed as standalone IDs, or only through a logical camera? (If only logical: Phase 2 shows "no usable camera"; next step is a guarded logical session with physical streams.)
2. Is the periscope in the ID list, only a physical sub-ID, or absent?
3. Do main/front/UW/tele open independently (probe results)?
4. Which max-resolution / video sizes / concurrent sets exist (for Phases 3-5)?
5. Does Samsung's service reject any open while the dead sensor is initialised (then even probing the main may fail)?

## Phase 3 additions (Drop A)
- `:core`: `Aspect` (`aspectOptions`, tolerant 1% aspect match), `Filters` (4x5 matrices, pure), `Features` (`featuresOf` registry per camera), `Motion` (`MotionWindow` ring/clip selection, `MotionContainer` XMP + JPEG splice), `Sizes` (`pickPreviewSize` now area-capped 1080p and aspect-tolerant, `pickMotionSize`). All unit-tested; `Phase3Test` uses the real device snapshot fixture.
- `:app`: `MotionRecorder` (surface AVC encoder + ring buffer + MediaMuxer), `PhotoPipeline` (filter -> motion container -> MediaStore, on the save thread), `Settings` (SharedPreferences), `CameraController` (aspect/timer/filter/motion/flip; JPEG metadata travels with a `Shot` queue so late-arriving images use the settings of the shutter press).
- Session = preview + JPEG (+ encoder surface when Motion Photo is on). Anything that changes the stream set reopens through `openRole`, so the generation counter, gate marker and recovery policy apply unchanged.

## Phases 4-5 hooks
`Feature`/`featuresOf` + `Capabilities` gate UI; `ZoomPlan`, `ControlState`, and session building in `CameraController` are the extension points (video adds a MediaCodec/MediaRecorder output and a template; Pro adds manual `ControlState` fields clipped to the queried ISO/exposure ranges).
