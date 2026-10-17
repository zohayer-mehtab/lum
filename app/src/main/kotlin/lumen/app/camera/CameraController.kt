package lumen.app.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedQueue
import lumen.app.LumenApp
import lumen.core.AspectOpt
import lumen.core.CameraInfo
import lumen.core.Feature
import lumen.core.Filters
import lumen.core.Lens
import lumen.core.Purpose
import lumen.core.RecoveryAction
import lumen.core.RecoveryPolicy
import lumen.core.Role
import lumen.core.ZoomPlan
import lumen.core.aspectOptions
import lumen.core.featuresOf
import lumen.core.pickJpegSize
import lumen.core.pickMotionSize
import lumen.core.pickPreviewSize
import lumen.core.presetsFor
import lumen.core.snapshotFromJson

sealed interface CamUi {
    data object Idle : CamUi
    data class Opening(val role: Role, val msg: String) : CamUi
    data class Live(val role: Role) : CamUi
    data class Error(val msg: String) : CamUi
}

data class ViewState(
    val ui: CamUi = CamUi.Idle,
    val zoom: Float = 1f,
    val zoomFloor: Float = 1f,
    val zoomCeil: Float = 1f,
    val presets: List<Float> = emptyList(),
    val lensPills: List<Float> = emptyList(),
    val flash: FlashMode = FlashMode.OFF,
    val flashAvailable: Boolean = false,
    val ev: Int = 0, val evMin: Int = 0, val evMax: Int = 0, val evStep: Float = 0f,
    val notice: String? = null,
    val capturing: Boolean = false,
    val lastSaved: String? = null,
    val front: Boolean = false,
    val aspect: Float = 0.75f,   // portrait width/height of the preview
    val aspects: List<AspectOpt> = emptyList(),
    val aspectId: String = "4:3",
    val timer: Int = 0,
    val countdown: Int? = null,
    val filterId: String = Filters.NONE_ID,
    val motionAvailable: Boolean = false,
    val motion: Boolean = false,
    val canFlip: Boolean = false,
)

/**
 * Phase 2 camera pipeline. All mutable state is touched only on the camera thread (app.camHandler);
 * public methods post to it. A generation counter invalidates callbacks from superseded devices.
 */
class CameraController(private val app: LumenApp) {
    private val h: Handler = app.camHandler
    private val gate = app.gate
    private val log = app.log
    private val saveThread = HandlerThread("save").also { it.start() }
    private val saveHandler = Handler(saveThread.looper)
    private val settings = app.settings

    private val _state = MutableStateFlow(
        ViewState(aspectId = settings.aspectId, timer = settings.timer, filterId = settings.filterId, motion = settings.motion)
    )
    val state: StateFlow<ViewState> = _state

    // ---- camera-thread state ----
    private var infos: Map<String, CameraInfo> = emptyMap()
    private var roleToId: Map<Role, String> = emptyMap()
    private var plan: ZoomPlan? = null
    private var recovery = RecoveryPolicy()
    private var started = false
    private var surfaceTexture: SurfaceTexture? = null
    private var gen = 0
    private var opening = false
    private var queuedRole: Role? = null
    private var pendingAfterClose: (() -> Unit)? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var ticket: Ticket? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var curRole: Role? = null
    private var curId: String? = null
    private var firstFrame = false
    private var controls = ControlState()
    private var zoom = 1f
    private var deviceRotation = 0
    private var capturePhase = Phase.IDLE
    private var sawPrecapture = false
    private var aspectId = settings.aspectId
    private var timerSec = settings.timer
    private var filterId = settings.filterId
    private var motionWanted = settings.motion
    private val motionBlocked = HashSet<String>()     // camera ids whose session failed with the Motion Photo stream
    private var recorder: MotionRecorder? = null
    private var streamAspect = 0f                      // w/h of the still stream in sensor space
    private var countdown = 0
    private val shots = ConcurrentLinkedQueue<Shot>()
    private enum class Phase { IDLE, PRECAPTURE, STILL }

    private val firstFrameWatchdog = Runnable { fail("no frames within ${FIRST_FRAME_MS}ms") }
    private val captureTimeout = Runnable { capturePhase = Phase.IDLE; _state.update { it.copy(capturing = false, notice = "Capture timed out") } }
    private val focusReset = Runnable { controls = controls.copy(region = null); pushRepeating() }
    private val closeWatchdog = Runnable { runPendingAfterClose() }
    private val tick = object : Runnable {
        override fun run() {
            countdown--
            if (countdown <= 0) { countdown = 0; _state.update { it.copy(countdown = null) }; captureNow() }
            else { _state.update { it.copy(countdown = countdown) }; h.postDelayed(this, 1000) }
        }
    }

    // ------------------------------------------------------------------ public API
    fun attachSurface(st: SurfaceTexture) = h.post { surfaceTexture = st; if (started) openForCurrentZoom() }
    fun detachSurface(st: SurfaceTexture) = h.post {
        if (surfaceTexture === st) {
            surfaceTexture = null
            closeCurrent { runCatching { st.release() } }
            _state.update { it.copy(ui = CamUi.Idle) }
        } else runCatching { st.release() }
    }
    fun dismissNotice() = h.post { _state.update { it.copy(notice = null) } }
    fun start() = h.post { if (!started) { started = true; if (reloadMapping()) { if (surfaceTexture != null) openForCurrentZoom() } } }
    fun stop() = h.post { started = false; queuedRole = null; closeCurrent { }; _state.update { it.copy(ui = CamUi.Idle) } }
    fun retry() = h.post { recovery.manualRetry(); if (reloadMapping() && surfaceTexture != null) openForCurrentZoom() }
    fun setDeviceRotation(deg: Int) = h.post { deviceRotation = deg }

    fun requestZoom(z: Float) = h.post {
        val p = plan ?: return@post
        if (curRole == Role.FRONT) return@post
        zoom = p.clamp(z)
        _state.update { it.copy(zoom = zoom) }
        val r = p.route(zoom, curRole)
        if (curRole != null && r.role != curRole && !opening) openRole(r.role, "Switching lens")
        else if (curRole == r.role) { controls = controls.copy(crop = r.crop); pushRepeating() }
        else if (opening) queuedRole = r.role
    }

    fun setFlash(f: FlashMode) = h.post { controls = controls.copy(flash = f); _state.update { it.copy(flash = f) }; pushRepeating() }
    fun setEv(i: Int) = h.post { controls = controls.copy(ev = i); _state.update { it.copy(ev = i) }; pushRepeating() }

    fun tapFocus(u: Float, v: Float) = h.post {
        val info = curInfo() ?: return@post
        val sess = session ?: return@post
        val dev = device ?: return@post
        val rect = Controls.meteringRect(info, controls.crop, u, v, streamAspect, mirrored = info.facing == "FRONT")
        controls = controls.copy(region = rect)
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(previewSurface!!) }
            Controls.apply(b, info, controls)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            sess.capture(b.build(), null, h)
            pushRepeating()
            h.removeCallbacks(focusReset); h.postDelayed(focusReset, 4000)
        } catch (e: Exception) { log.log("WARN", curId, "tap focus failed: $e") }
    }

    /** Shutter button: starts the timer countdown (if set), cancels a running countdown, else captures. */
    fun shutter() = h.post {
        if (countdown > 0) { cancelCountdown(); return@post }
        if (session == null || capturePhase != Phase.IDLE || !firstFrame) return@post
        if (timerSec > 0) { countdown = timerSec; _state.update { it.copy(countdown = countdown) }; h.postDelayed(tick, 1000) }
        else captureNow()
    }

    fun setAspect(id: String) = h.post {
        if (id == aspectId) return@post
        aspectId = id; settings.aspectId = id
        _state.update { it.copy(aspectId = id) }
        reopenCurrent("Changing format")
    }

    fun setTimer(sec: Int) = h.post { cancelCountdown(); timerSec = sec; settings.timer = sec; _state.update { it.copy(timer = sec) } }

    fun setFilter(id: String) = h.post { filterId = id; settings.filterId = id; _state.update { it.copy(filterId = id) } }

    fun setMotion(on: Boolean) = h.post {
        motionWanted = on; settings.motion = on
        _state.update { it.copy(motion = on) }
        reopenCurrent(if (on) "Enabling Motion Photo" else "Disabling Motion Photo")
    }

    /** Rear <-> front. Goes through the same guarded open as every lens change. */
    fun flip() = h.post {
        if (!started || surfaceTexture == null || opening) return@post
        val target = if (curRole == Role.FRONT) plan?.route(zoom, null)?.role
        else Role.FRONT.takeIf { r -> roleToId[r]?.let { !recovery.isUnavailable(it) } == true }
        if (target == null || roleToId[target] == null) { _state.update { it.copy(notice = "No other camera available") }; return@post }
        openRole(target, if (target == Role.FRONT) "Switching to front camera" else "Switching to rear camera")
    }

    private fun reopenCurrent(msg: String) {
        val r = curRole ?: return
        if (started && surfaceTexture != null && !opening) openRole(r, msg)   // while opening, createSession reads the new values
    }

    private fun cancelCountdown() {
        h.removeCallbacks(tick)
        if (countdown != 0) { countdown = 0; _state.update { it.copy(countdown = null) } }
    }

    private fun captureNow() {
        val info = curInfo() ?: return
        if (session == null || capturePhase != Phase.IDLE || !firstFrame) return
        _state.update { it.copy(capturing = true) }
        h.removeCallbacks(captureTimeout); h.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)
        if (Controls.flashFires(info, controls.flash)) startPrecapture(info) else doStill(info)
    }

    // ------------------------------------------------------------------ mapping / plan
    private fun reloadMapping(): Boolean {
        val snap = app.snapshots.load()
        if (snap == null || snap.fingerprint != android.os.Build.FINGERPRINT) { setError("No diagnostics for this firmware. Open diagnostics."); return false }
        infos = snap.cameras.associateBy { it.id }
        roleToId = app.gateCore.usableByRole().filter { (_, id) -> infos.containsKey(id) }
        if (roleToId.isEmpty()) { setError("No verified camera. Open diagnostics and run the probes."); return false }
        rebuildPlan()
        return true
    }

    private fun availableRoles() = roleToId.filter { !recovery.isUnavailable(it.value) }

    private fun rebuildPlan() {
        val av = availableRoles()
        val hasTele = Role.TELE_3X in av
        val ratios = measuredRatios()
        val lenses = av.mapNotNull { (role, id) ->
            if (role == Role.FRONT) return@mapNotNull null
            val md = infos[id]?.maxDigitalZoom ?: 1f
            val cap = when (role) { Role.TELE_3X -> 10f; Role.MAIN -> 5f; else -> 3f }
            Lens(role, ratios[role] ?: nominal(role), md.coerceIn(1f, cap))
        }
        plan = if (lenses.isEmpty()) null else ZoomPlan(lenses)
        val p = plan
        val notices = ArrayList<String>()
        if (p != null) {
            if (!hasTele) notices += "Telephoto unavailable: zoom limited to %.0fx".format(p.ceiling)
            else if (p.ceiling < 29.5f) notices += "Device limits digital zoom to %.1fx".format(p.ceiling)
            zoom = p.clamp(if (zoom <= 0f) 1f else zoom)
        }
        _state.update {
            it.copy(
                zoomFloor = p?.floor ?: 1f, zoomCeil = p?.ceiling ?: 1f, zoom = zoom,
                presets = p?.let { pl -> presetsFor(pl) }.orEmpty(),
                lensPills = p?.lenses?.map { l -> l.nativeRatio }.orEmpty(),
                canFlip = Role.FRONT in av && av.keys.any { r -> r != Role.FRONT },
                notice = notices.firstOrNull() ?: it.notice?.takeIf { n -> n.startsWith("Saved") },
            )
        }
    }

    /** Native ratio per role from measured 35mm-equivalent focal lengths, relative to the main camera. */
    private fun measuredRatios(): Map<Role, Float> {
        val mainEq = roleToId[Role.MAIN]?.let { infos[it]?.equiv35 } ?: 0f
        if (mainEq <= 0f) return emptyMap()
        return roleToId.mapNotNull { (r, id) -> infos[id]?.equiv35?.takeIf { it > 0f }?.let { r to (it / mainEq) } }.toMap()
            .mapValues { (r, v) -> snap(r, v) }
    }

    /** Within 12% of the nominal ratio use the nominal (0.6/1/3), otherwise keep the measured value. */
    private fun snap(r: Role, v: Float): Float { val n = nominal(r); return if (kotlin.math.abs(v / n - 1f) <= 0.12f) n else v }
    private fun nominal(r: Role) = when (r) { Role.ULTRA_WIDE -> 0.6f; Role.TELE_3X -> 3f; else -> 1f }

    private fun curInfo() = curId?.let { infos[it] }

    // ------------------------------------------------------------------ open / close
    private fun openForCurrentZoom() {
        val p = plan
        val role = if (p != null && roleToId.containsKey(p.route(zoom, curRole).role)) p.route(zoom, curRole).role
        else availableRoles().keys.firstOrNull() ?: run { setError("No usable camera available."); return }
        openRole(role, "Opening camera")
    }

    private fun openRole(role: Role, msg: String) {
        _state.update { it.copy(ui = CamUi.Opening(role, msg)) }
        if (opening) { queuedRole = role; return }
        closeCurrent { doOpen(role) }
    }

    private fun doOpen(role: Role) {
        val st = surfaceTexture ?: return
        val id = roleToId[role]?.takeIf { !recovery.isUnavailable(it) }
        if (id == null) { fallbackFrom(role, "no verified camera for $role"); return }
        val info = infos[id] ?: run { fail("no snapshot data for $id"); return }
        val myGen = ++gen
        opening = true; firstFrame = false
        curRole = role; curId = id
        gate.open(id, Purpose.OPEN, object : DeviceListener {
            override fun onOpened(device: CameraDevice, ticket: Ticket) {
                opening = false
                if (myGen != gen || !started || surfaceTexture == null) {
                    gate.finish(ticket, false, "superseded"); pendingAfterClose = { runQueued() }; device.close(); return
                }
                this@CameraController.device = device; this@CameraController.ticket = ticket
                val q = queuedRole; queuedRole = null
                if (q != null && q != role) { openRole(q, "Switching lens"); return }
                createSession(device, info, st, myGen)
            }
            override fun onOpenFailed(reason: String) {
                opening = false
                if (myGen != gen) { runQueued(); return }
                log.log("WARN", id, "open failed: $reason")
                if (reason.startsWith("refused")) fallbackFrom(role, reason) else fail(reason)
            }
            override fun onDisconnected(device: CameraDevice) { if (myGen == gen) fail("disconnected") }
            override fun onError(device: CameraDevice, code: Int) { if (myGen == gen) fail("camera error $code") }
            override fun onClosed() { runPendingAfterClose() }
        })
    }

    private fun createSession(dev: CameraDevice, info: CameraInfo, st: SurfaceTexture, myGen: Int) {
        try {
            val opts = aspectOptions(info.jpegSizes)
            val opt = opts.firstOrNull { it.id == aspectId } ?: opts.firstOrNull { it.id == "4:3" } ?: opts.firstOrNull()
            val jpeg = opt?.jpeg ?: pickJpegSize(info.jpegSizes) ?: error("no JPEG size")
            streamAspect = jpeg.w.toFloat() / jpeg.h
            val prev = pickPreviewSize(info.previewSizes, jpeg) ?: error("no preview size")
            st.setDefaultBufferSize(prev.w, prev.h)
            val swap = info.sensorOrientation % 180 == 90
            _state.update { it.copy(aspect = if (swap) prev.h.toFloat() / prev.w else prev.w.toFloat() / prev.h) }
            val ps = Surface(st); previewSurface = ps
            val reader = ImageReader.newInstance(jpeg.w, jpeg.h, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                val shot = shots.poll()
                val ts = img.timestamp
                val res = runCatching { StillSaver.bytesAndClose(img) }
                    .fold({ bytes -> PhotoPipeline.process(app, bytes, ts, shot) }, { e -> Result.failure<SaveResult>(e) })
                _state.update { cur ->
                    res.fold(
                        { ok -> cur.copy(lastSaved = ok.name, notice = "Saved ${ok.name}" + (ok.warn?.let { w -> " ($w)" } ?: "")) },
                        { e -> cur.copy(notice = "Save failed: ${e.message}") },
                    )
                }
            }, saveHandler)
            jpegReader = reader
            var rec: MotionRecorder? = null
            if (motionWanted && Feature.MOTION_PHOTO in featuresOf(info) && info.id !in motionBlocked) {
                val ms = pickMotionSize(info.videoSizes, jpeg)
                if (ms != null) {
                    MotionRecorder.create(ms.w, ms.h)
                        .onSuccess { rec = it }
                        .onFailure { e ->
                            log.log("WARN", info.id, "motion encoder: $e")
                            _state.update { it.copy(notice = "Motion Photo unavailable: ${e.message}") }
                        }
                }
            }
            recorder = rec
            val outputs = arrayListOf(OutputConfiguration(ps), OutputConfiguration(reader.surface))
            rec?.let { outputs += OutputConfiguration(it.surface) }
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                { h.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (myGen != gen) { runCatching { s.close() }; return }
                        session = s
                        val role = curRole!!
                        applyRouteForZoom(role)
                        pushRepeating()
                        h.removeCallbacks(firstFrameWatchdog); h.postDelayed(firstFrameWatchdog, FIRST_FRAME_MS)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (myGen != gen) return
                        val id = curId; val role = curRole
                        if (recorder != null && id != null && role != null) {
                            // Not a camera fault: this lens/stream combination is unsupported. Retry once without Motion Photo.
                            motionBlocked += id
                            log.log("WARN", id, "session with Motion Photo stream failed; Motion Photo disabled for this camera")
                            _state.update { it.copy(notice = "Motion Photo isn't supported on this lens; off here") }
                            openRole(role, "Reconfiguring")
                        } else fail("session configure failed")
                    }
                })
            dev.createCaptureSession(cfg)
        } catch (e: Exception) { fail("session setup: $e") }
    }

    private fun applyRouteForZoom(role: Role) {
        val info = curInfo() ?: return
        val p = plan
        val crop = if (role == Role.FRONT || p == null) 1f else p.route(zoom, role).crop
        controls = controls.copy(crop = crop)
        val opts = aspectOptions(info.jpegSizes)
        val shown = opts.firstOrNull { it.id == aspectId } ?: opts.firstOrNull { it.id == "4:3" } ?: opts.firstOrNull()
        _state.update {
            it.copy(front = role == Role.FRONT, flashAvailable = info.flashAvailable,
                aspects = if (Feature.ASPECT in featuresOf(info)) opts else emptyList(), aspectId = shown?.id ?: it.aspectId,
                motionAvailable = Feature.MOTION_PHOTO in featuresOf(info) && info.id !in motionBlocked,
                evMin = info.aeCompMin, evMax = info.aeCompMax, evStep = info.aeCompStep,
                ev = controls.ev.coerceIn(info.aeCompMin, info.aeCompMax),
                flash = if (info.flashAvailable) controls.flash else FlashMode.OFF)
        }
        if (!info.flashAvailable) controls = controls.copy(flash = FlashMode.OFF)
    }

    private val previewCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            if (!firstFrame && s === session) {
                firstFrame = true
                h.removeCallbacks(firstFrameWatchdog)
                ticket?.let { gate.finish(it, true, "first frame") }
                val role = curRole ?: return
                _state.update { it.copy(ui = CamUi.Live(role)) }
                // user may have pinched across a boundary while we were opening
                val want = plan?.route(zoom, role)?.role
                if (want != null && want != role && role != Role.FRONT) openRole(want, "Switching lens")
            }
            if (capturePhase == Phase.PRECAPTURE) {
                val ae = res.get(CaptureResult.CONTROL_AE_STATE)
                if (ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) sawPrecapture = true
                else if (sawPrecapture) curInfo()?.let { doStill(it) }
            }
        }
    }

    private fun pushRepeating() {
        val dev = device ?: return; val sess = session ?: return; val info = curInfo() ?: return
        val ps = previewSurface ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(ps); recorder?.let { addTarget(it.surface) } }
            Controls.apply(b, info, controls)
            sess.setRepeatingRequest(b.build(), previewCb, h)
        } catch (e: Exception) { log.log("WARN", curId, "setRepeatingRequest: $e") }
    }

    // ------------------------------------------------------------------ still capture
    private fun startPrecapture(info: CameraInfo) {
        val dev = device ?: return; val sess = session ?: return
        capturePhase = Phase.PRECAPTURE; sawPrecapture = false
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(previewSurface!!) }
            Controls.apply(b, info, controls)
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            sess.capture(b.build(), null, h)
            h.postDelayed({ if (capturePhase == Phase.PRECAPTURE) doStill(info) }, 1500)   // AE never signalled: shoot anyway
        } catch (e: Exception) { capturePhase = Phase.IDLE; doStill(info) }
    }

    private fun doStill(info: CameraInfo) {
        if (capturePhase == Phase.STILL) return
        val dev = device ?: return; val sess = session ?: return; val reader = jpegReader ?: return
        capturePhase = Phase.STILL
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader.surface) }
            Controls.apply(b, info, controls)
            b.set(CaptureRequest.CONTROL_AF_MODE, Controls.afMode(info))
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            val rot = deviceRotation
            val orientation = if (info.facing == "FRONT") (info.sensorOrientation - rot + 360) % 360 else (info.sensorOrientation + rot) % 360
            b.set(CaptureRequest.JPEG_ORIENTATION, orientation)
            shots.add(Shot(filterId, recorder, orientation))
            sess.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) { endCapture(null) }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) { shots.poll(); endCapture("Capture failed (reason ${f.reason})") }
            }, h)
        } catch (e: Exception) { shots.poll(); endCapture("Capture error: ${e.message}") }
    }

    private fun endCapture(err: String?) {
        capturePhase = Phase.IDLE; h.removeCallbacks(captureTimeout)
        _state.update { it.copy(capturing = false, notice = err ?: it.notice) }
    }

    // ------------------------------------------------------------------ failure / recovery
    private fun fail(why: String) {
        val id = curId ?: return setError("Camera failed: $why")
        val role = curRole
        log.log("FAILURE", id, why)
        ticket?.let { gate.finish(it, false, why) }
        val candidates = listOf(Role.MAIN, Role.ULTRA_WIDE, Role.TELE_3X, Role.FRONT).mapNotNull { roleToId[it] }
        when (val a = recovery.onFailure(id, candidates)) {
            is RecoveryAction.Restart -> {
                _state.update { it.copy(ui = CamUi.Opening(role ?: Role.MAIN, "Restarting camera…")) }
                closeCurrent { h.postDelayed({ if (started && surfaceTexture != null && !opening) role?.let { doOpen(it) } }, a.backoffMs) }
            }
            is RecoveryAction.Fallback -> {
                val next = roleToId.entries.firstOrNull { it.value == a.id }?.key
                if (next == null) { closeCurrent { }; setError("Camera failed: $why") }
                else {
                    rebuildPlan()
                    _state.update { it.copy(notice = "$role failed ($why). Using $next.") }
                    closeCurrent { doOpen(next) }
                }
            }
            RecoveryAction.GiveUp -> { rebuildPlan(); closeCurrent { }; setError("No camera could be opened ($why). Tap Retry or open diagnostics.") }
        }
    }

    private fun fallbackFrom(role: Role, why: String) {
        val next = listOf(Role.MAIN, Role.ULTRA_WIDE, Role.TELE_3X, Role.FRONT).firstOrNull { it != role && roleToId[it]?.let { id -> !recovery.isUnavailable(id) } == true }
        if (next == null) { closeCurrent { }; setError("No usable camera ($why)."); return }
        rebuildPlan()
        _state.update { it.copy(notice = "$role not available ($why). Using $next.") }
        doOpen(next)
    }

    private fun setError(msg: String) { _state.update { it.copy(ui = CamUi.Error(msg)) } }

    private fun closeCurrent(then: () -> Unit) {
        gen++
        h.removeCallbacks(firstFrameWatchdog); h.removeCallbacks(captureTimeout); h.removeCallbacks(focusReset)
        cancelCountdown()
        capturePhase = Phase.IDLE
        shots.clear()
        ticket?.let { gate.finish(it, firstFrame, "closing") }; ticket = null
        runCatching { session?.close() }; session = null
        runCatching { jpegReader?.close() }; jpegReader = null
        recorder?.stop(); recorder = null
        runCatching { previewSurface?.release() }; previewSurface = null
        val d = device; device = null; firstFrame = false
        if (d == null) { pendingAfterClose = null; then(); return }
        pendingAfterClose = then
        h.removeCallbacks(closeWatchdog); h.postDelayed(closeWatchdog, 2000)
        runCatching { d.close() }
    }

    private fun runPendingAfterClose() {
        h.removeCallbacks(closeWatchdog)
        val p = pendingAfterClose; pendingAfterClose = null; p?.invoke()
    }

    private fun runQueued() { val q = queuedRole; queuedRole = null; if (q != null && started) openRole(q, "Switching lens") }

    private companion object { const val FIRST_FRAME_MS = 5000L; const val CAPTURE_TIMEOUT_MS = 6000L }
}
