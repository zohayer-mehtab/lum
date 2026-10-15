package lumen.app.camera

import android.graphics.Rect
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import lumen.core.CameraInfo

enum class FlashMode { OFF, AUTO, ON }

/** Mutable user intent shared by preview and still requests. */
data class ControlState(
    val crop: Float = 1f,
    val flash: FlashMode = FlashMode.OFF,
    val ev: Int = 0,
    val region: MeteringRectangle? = null,
)

object Controls {
    fun activeRect(i: CameraInfo) = Rect(i.activeLeft, i.activeTop, i.activeLeft + i.activeWidth, i.activeTop + i.activeHeight)

    /** Centered crop of the active array by [crop] (>= 1). */
    fun cropRect(i: CameraInfo, crop: Float): Rect {
        val c = crop.coerceAtLeast(1f)
        val w = (i.activeWidth / c).toInt().coerceAtLeast(2) and 1.inv()
        val h = (i.activeHeight / c).toInt().coerceAtLeast(2) and 1.inv()
        val l = i.activeLeft + (i.activeWidth - w) / 2
        val t = i.activeTop + (i.activeHeight - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    fun afMode(i: CameraInfo): Int = when {
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in i.afModes -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        CameraMetadata.CONTROL_AF_MODE_AUTO in i.afModes -> CameraMetadata.CONTROL_AF_MODE_AUTO
        else -> CameraMetadata.CONTROL_AF_MODE_OFF
    }

    fun aeMode(i: CameraInfo, f: FlashMode): Int {
        val want = when {
            !i.flashAvailable -> CameraMetadata.CONTROL_AE_MODE_ON
            f == FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            f == FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            else -> CameraMetadata.CONTROL_AE_MODE_ON
        }
        return if (want in i.aeModes || i.aeModes.isEmpty()) want else CameraMetadata.CONTROL_AE_MODE_ON
    }

    fun flashFires(i: CameraInfo, f: FlashMode) = i.flashAvailable && f != FlashMode.OFF &&
        aeMode(i, f) != CameraMetadata.CONTROL_AE_MODE_ON

    fun apply(b: CaptureRequest.Builder, i: CameraInfo, c: ControlState) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, afMode(i))
        b.set(CaptureRequest.CONTROL_AE_MODE, aeMode(i, c.flash))
        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)   // flash fires via AE mode on still only
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.ev.coerceIn(i.aeCompMin, i.aeCompMax))
        b.set(CaptureRequest.SCALER_CROP_REGION, cropRect(i, c.crop))
        c.region?.let {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
        }
    }

    /**
     * Maps a tap in the displayed (portrait, natural orientation) preview, normalized 0..1, to a metering
     * rectangle in active-array coordinates, accounting for sensor orientation, front mirroring and crop.
     */
    fun meteringRect(i: CameraInfo, crop: Float, u0: Float, v: Float): MeteringRectangle {
        val u = if (i.facing == "FRONT") 1f - u0 else u0
        val (su, sv) = when (i.sensorOrientation) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }
        val r = cropRect(i, crop)
        val cx = r.left + su.coerceIn(0f, 1f) * r.width()
        val cy = r.top + sv.coerceIn(0f, 1f) * r.height()
        val half = (minOf(i.activeWidth, i.activeHeight) * 0.07f).toInt().coerceAtLeast(8)
        val a = activeRect(i)
        val l = (cx - half).toInt().coerceIn(a.left, a.right - 2 * half)
        val t = (cy - half).toInt().coerceIn(a.top, a.bottom - 2 * half)
        return MeteringRectangle(l, t, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX)
    }
}
