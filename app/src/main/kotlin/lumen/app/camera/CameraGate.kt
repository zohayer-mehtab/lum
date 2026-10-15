package lumen.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import lumen.app.data.EventLog
import lumen.core.Decision
import lumen.core.GateCore
import lumen.core.Purpose
import java.util.concurrent.atomic.AtomicBoolean

/** Proof that GateCore allowed a touch and wrote the crash marker. Must be finished exactly once. */
class Ticket internal constructor(val id: String, val purpose: Purpose) {
    internal val done = AtomicBoolean(false)
}

interface DeviceListener {
    fun onOpened(device: CameraDevice, ticket: Ticket)
    fun onOpenFailed(reason: String)                 // refused, or failed before open (ticket already ended)
    fun onDisconnected(device: CameraDevice)         // after a successful open
    fun onError(device: CameraDevice, code: Int)     // after a successful open
    fun onClosed()
}

/**
 * THE single choke point: the only file allowed to call the camera-open API or select physical streams
 * (enforced by the Gradle task checkGateInvariant). Every touch is decided by GateCore first and
 * brackets a durable crash-loop marker.
 */
class CameraGate(ctx: Context, val core: GateCore, private val log: EventLog, private val handler: Handler) {
    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** ID list only; no per-camera contact. */
    fun listIds(): List<String> = mgr.cameraIdList.toList()

    fun concurrentIds(): List<List<String>> = runCatching {
        if (Build.VERSION.SDK_INT >= 30) mgr.concurrentCameraIds.map { it.sorted() } else emptyList()
    }.onFailure { log.log("INFO", null, "concurrentCameraIds failed: $it") }.getOrDefault(emptyList())

    /** Guarded characteristics read for diagnostics. Marker is written before, cleared after. */
    fun readCharacteristics(id: String): Result<CameraCharacteristics> {
        val d = core.begin(id, Purpose.DIAG_READ)
        if (d is Decision.Refuse) return Result.failure(IllegalStateException("refused: ${d.reason}"))
        return try {
            val c = mgr.getCameraCharacteristics(id)
            core.end(id, Purpose.DIAG_READ, true)
            Result.success(c)
        } catch (e: Exception) {
            core.end(id, Purpose.DIAG_READ, false, e.toString())
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    fun open(id: String, purpose: Purpose, listener: DeviceListener, timeoutMs: Long = 10_000) {
        require(purpose != Purpose.DIAG_READ)
        val d = core.begin(id, purpose)
        if (d is Decision.Refuse) { listener.onOpenFailed("refused: ${d.reason}"); return }
        val ticket = Ticket(id, purpose)
        val settled = AtomicBoolean(false)     // exactly one of: opened / failed / timed out
        var opened = false
        val timeout = Runnable {
            if (settled.compareAndSet(false, true)) {
                core.quarantine(id, "open() gave no callback within ${timeoutMs}ms")
                finish(ticket, false, "open timeout")
                listener.onOpenFailed("open timeout")
            }
        }
        val cb = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!settled.compareAndSet(false, true)) { camera.close(); return }   // arrived after timeout
                handler.removeCallbacks(timeout)
                opened = true; log.log("OPENED", id, purpose.name); listener.onOpened(camera, ticket)
            }
            override fun onDisconnected(camera: CameraDevice) {
                log.log("DISCONNECTED", id, "opened=$opened")
                if (opened) { listener.onDisconnected(camera); return }
                camera.close()
                if (settled.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout); finish(ticket, false, "disconnected before open"); listener.onOpenFailed("disconnected")
                }
            }
            override fun onError(camera: CameraDevice, error: Int) {
                log.log("ERROR", id, "code=$error opened=$opened")
                if (opened) { listener.onError(camera, error); return }
                camera.close()
                if (settled.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout); finish(ticket, false, "error $error before open"); listener.onOpenFailed("error $error")
                }
            }
            override fun onClosed(camera: CameraDevice) { log.log("CLOSED", id, ""); listener.onClosed() }
        }
        handler.postDelayed(timeout, timeoutMs)
        try {
            mgr.openCamera(id, cb, handler)
        } catch (e: Exception) {   // SecurityException, CameraAccessException, IllegalArgumentException
            if (settled.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                finish(ticket, false, e.toString())
                listener.onOpenFailed(e.javaClass.simpleName + ": " + e.message)
            }
        }
    }

    /** Clears the crash marker. ok=true for OPEN means the first frame arrived; for PROBE, the full probe passed. */
    fun finish(ticket: Ticket, ok: Boolean, note: String = "") {
        if (ticket.done.compareAndSet(false, true)) core.end(ticket.id, ticket.purpose, ok, note)
    }
}
