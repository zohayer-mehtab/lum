# Lumen Engineering & Safety Specification

## System Invariants
1. Single Choke Point: `CameraGate.kt` is the ONLY file permitted to call `openCamera(`, `setPhysicalCameraId(`, or `getCameraCharacteristics(`. The Gradle task `checkGateInvariant` runs during `preBuild` and will fail the build if any other file contains these calls.
2. Architecture Division:
   - `:core` is pure Kotlin JVM (no Android dependencies). Holds `GateCore`, `Classifier`, `ZoomPlan`, `RecoveryPolicy`, and data models.
   - `:app` holds Camera2 bindings, Jetpack Compose UI, and `CameraController`.
3. Hardware Constraints (from lumen-snapshot.json for SM-G998U):
   - Standalone Lenses: ID 0 (Main 1x, BACK), ID 1 (Front, FRONT), ID 2 (Ultra-Wide 0.6x, BACK). ID 3 (Secondary Front, ignored/disabled).
   - Telephoto & Periscope: Missing from Camera2 enumeration. Maximum digital zoom is hard-capped by hardware at 8.0x on ID 0.
   - ISO Range (ID 0): 50 to 3200.
   - Exposure Time Range (ID 0): 85,248 ns to 164,064,000 ns.
   - Concurrent Camera Set: Supported for ID 0 and ID 1 simultaneously (`["0", "1"]`).
   - Maximum Resolution: Standard 12MP binned streams (`4000x3000`). 108MP raw output is not exposed by standard API.
   - Video Resolutions: 4K UHD (`3840x2160`) supported on back and front.

## State Pipeline & UI Pattern
- `CameraController.kt` handles thread dispatching via `LumenApp.camHandler`. State flows out via `StateFlow<ViewState>`.
- Camera lifecycle and lens switching must always transition safely through `CamUi.Opening` to avoid race conditions.
