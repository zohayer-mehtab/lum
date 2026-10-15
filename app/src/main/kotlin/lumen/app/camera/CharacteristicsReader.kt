package lumen.app.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics as CC
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import lumen.core.CameraInfo

object CharacteristicsReader {
    private fun List<Size>?.fmt(): List<String> =
        this.orEmpty().sortedByDescending { it.width.toLong() * it.height }.map { "${it.width}x${it.height}" }

    fun read(id: String, listed: Boolean, c: CC): CameraInfo {
        val caps = c.get(CC.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val logical = CC_LOGICAL in caps
        val map = c.get(CC.SCALER_STREAM_CONFIGURATION_MAP)
        val phys = c.get(CC.SENSOR_INFO_PHYSICAL_SIZE)
        val pix = c.get(CC.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = c.get(CC.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val zoom = c.get(CC.CONTROL_ZOOM_RATIO_RANGE)
        val aeComp = c.get(CC.CONTROL_AE_COMPENSATION_RANGE)
        val iso = c.get(CC.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = c.get(CC.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val maxRes = if (Build.VERSION.SDK_INT >= 31)
            c.get(CC.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.getOutputSizes(ImageFormat.JPEG)?.toList().fmt()
        else emptyList()
        return CameraInfo(
            id = id,
            facing = when (c.get(CC.LENS_FACING)) {
                CC.LENS_FACING_BACK -> "BACK"; CC.LENS_FACING_FRONT -> "FRONT"; CC.LENS_FACING_EXTERNAL -> "EXTERNAL"; else -> "UNKNOWN"
            },
            isLogical = logical,
            physicalIds = c.physicalCameraIds.sorted(),
            listedInCameraIdList = listed,
            capabilities = caps,
            hardwareLevel = c.get(CC.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
            focalLengths = c.get(CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
            sensorWidthMm = phys?.width ?: 0f, sensorHeightMm = phys?.height ?: 0f,
            pixelWidth = pix?.width ?: 0, pixelHeight = pix?.height ?: 0,
            activeLeft = active?.left ?: 0, activeTop = active?.top ?: 0,
            activeWidth = active?.width() ?: 0, activeHeight = active?.height() ?: 0,
            sensorOrientation = c.get(CC.SENSOR_ORIENTATION) ?: 0,
            zoomRatioMin = zoom?.lower, zoomRatioMax = zoom?.upper,
            maxDigitalZoom = c.get(CC.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().fmt(),
            previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().fmt(),
            yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().fmt(),
            maxResJpegSizes = maxRes,
            videoSizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList().fmt(),
            fpsRanges = c.get(CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { "${it.lower}-${it.upper}" }.orEmpty(),
            flashAvailable = c.get(CC.FLASH_INFO_AVAILABLE) == true,
            afModes = c.get(CC.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty(),
            aeModes = c.get(CC.CONTROL_AE_AVAILABLE_MODES)?.toList().orEmpty(),
            aeCompMin = aeComp?.lower ?: 0, aeCompMax = aeComp?.upper ?: 0,
            aeCompStep = c.get(CC.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
            isoMin = iso?.lower, isoMax = iso?.upper,
            exposureMinNs = exp?.lower, exposureMaxNs = exp?.upper,
            minFocusDistance = c.get(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
        )
    }

    private const val CC_LOGICAL = 11  // REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
}
