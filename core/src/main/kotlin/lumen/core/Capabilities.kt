package lumen.core

/** Per-camera capability flags later phases gate features on. Derived only from queried data. */
data class Capabilities(
    val flash: Boolean, val manualSensor: Boolean, val raw: Boolean, val maxResolution: Boolean,
    val manualFocus: Boolean, val burst: Boolean, val logicalMulti: Boolean,
)

// android.request.availableCapabilities values (public constants, stable ints)
private const val CAP_MANUAL_SENSOR = 1
private const val CAP_BURST = 6
private const val CAP_RAW = 3
private const val CAP_LOGICAL = 11
private const val CAP_ULTRA_HIGH_RES = 16

fun CameraInfo.capabilities() = Capabilities(
    flash = flashAvailable,
    manualSensor = CAP_MANUAL_SENSOR in capabilities && isoMin != null,
    raw = CAP_RAW in capabilities,
    maxResolution = maxResJpegSizes.isNotEmpty() || CAP_ULTRA_HIGH_RES in capabilities,
    manualFocus = minFocusDistance > 0f,
    burst = CAP_BURST in capabilities,
    logicalMulti = isLogical || CAP_LOGICAL in capabilities,
)
