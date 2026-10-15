package lumen.app.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import lumen.core.CameraInfo
import lumen.core.Purpose
import lumen.core.pickProbeSize
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Verification probe: opens, receives FRAMES_NEEDED frames, closes cleanly. Blocking; call off the main thread. */
class Prober(private val gate: CameraGate, private val handler: Handler) {

    fun probe(info: CameraInfo): String? {   // null = verified; otherwise failure reason
        val size = pickProbeSize(info.yuvSizes) ?: return "no YUV size in snapshot"
        val result = CompletableFuture<String?>()
        val closed = CountDownLatch(1)
        var device: CameraDevice? = null
        var ticket: Ticket? = null
        var reader: ImageReader? = null
        var frames = 0

        val listener = object : DeviceListener {
            override fun onOpened(device0: CameraDevice, ticket0: Ticket) {
                device = device0; ticket = ticket0
                try {
                    val r = ImageReader.newInstance(size.w, size.h, ImageFormat.YUV_420_888, 2)
                    reader = r
                    r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
                    val cfg = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(r.surface)),
                        { handler.post(it) },
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                try {
                                    val req = device0.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }.build()
                                    s.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(se: CameraCaptureSession, rq: CaptureRequest, res: TotalCaptureResult) {
                                            if (++frames >= FRAMES_NEEDED) result.complete(null)
                                        }
                                        override fun onCaptureFailed(se: CameraCaptureSession, rq: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {}
                                    }, handler)
                                } catch (e: Exception) { result.complete("request failed: $e") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { result.complete("session configure failed") }
                        })
                    device0.createCaptureSession(cfg)
                } catch (e: Exception) { result.complete("session setup failed: $e") }
            }
            override fun onOpenFailed(reason: String) { result.complete("open failed: $reason"); closed.countDown() }
            override fun onDisconnected(device: CameraDevice) { result.complete("disconnected during probe") }
            override fun onError(device: CameraDevice, code: Int) { result.complete("error $code during probe") }
            override fun onClosed() { closed.countDown() }
        }

        gate.open(info.id, Purpose.PROBE, listener)
        val failure: String? = try { result.get(PROBE_TIMEOUT_S, TimeUnit.SECONDS) } catch (e: Exception) { "timeout waiting for frames" }

        val t = ticket
        if (t == null) return failure ?: "probe aborted"          // never opened: gate already ended the ticket
        handler.post { runCatching { device?.close() }; runCatching { reader?.close() } }
        val cleanClose = closed.await(CLOSE_TIMEOUT_S, TimeUnit.SECONDS)
        val finalFailure = failure ?: if (!cleanClose) "device did not close cleanly" else null
        gate.finish(t, finalFailure == null, finalFailure ?: "$frames frames, clean close")
        return finalFailure
    }

    private companion object {
        const val FRAMES_NEEDED = 5
        const val PROBE_TIMEOUT_S = 20L
        const val CLOSE_TIMEOUT_S = 4L
    }
}
