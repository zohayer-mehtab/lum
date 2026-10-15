package lumen.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.hypot

enum class Role { ULTRA_WIDE, MAIN, TELE_3X, PERISCOPE, FRONT, LOGICAL, UNKNOWN }
enum class Confidence { HIGH, MEDIUM, LOW }

/** Roles an app session may ever open. PERISCOPE/LOGICAL/UNKNOWN are never usable in Phase 2. */
val USABLE_ROLES = setOf(Role.ULTRA_WIDE, Role.MAIN, Role.TELE_3X, Role.FRONT)

@Serializable
data class CameraInfo(
    val id: String,
    val facing: String,                 // BACK / FRONT / EXTERNAL / UNKNOWN
    val isLogical: Boolean,
    val physicalIds: List<String> = emptyList(),
    val listedInCameraIdList: Boolean = true,
    val capabilities: List<Int> = emptyList(),
    val hardwareLevel: Int = -1,
    val focalLengths: List<Float> = emptyList(),
    val sensorWidthMm: Float = 0f,
    val sensorHeightMm: Float = 0f,
    val pixelWidth: Int = 0,
    val pixelHeight: Int = 0,
    val activeLeft: Int = 0, val activeTop: Int = 0,
    val activeWidth: Int = 0, val activeHeight: Int = 0,
    val sensorOrientation: Int = 0,
    val zoomRatioMin: Float? = null,
    val zoomRatioMax: Float? = null,
    val maxDigitalZoom: Float = 1f,
    val jpegSizes: List<String> = emptyList(),
    val previewSizes: List<String> = emptyList(),
    val yuvSizes: List<String> = emptyList(),
    val maxResJpegSizes: List<String> = emptyList(),
    val videoSizes: List<String> = emptyList(),
    val fpsRanges: List<String> = emptyList(),
    val flashAvailable: Boolean = false,
    val afModes: List<Int> = emptyList(),
    val aeModes: List<Int> = emptyList(),
    val aeCompMin: Int = 0, val aeCompMax: Int = 0, val aeCompStep: Float = 0f,
    val isoMin: Int? = null, val isoMax: Int? = null,
    val exposureMinNs: Long? = null, val exposureMaxNs: Long? = null,
    val minFocusDistance: Float = 0f,
    val readError: String? = null,
) {
    val sensorDiagMm: Float get() = hypot(sensorWidthMm, sensorHeightMm)

    /** 35mm-equivalent of the first focal length; 0 if data is missing. */
    val equiv35: Float
        get() {
            val f = focalLengths.firstOrNull() ?: return 0f
            val d = sensorDiagMm
            return if (d > 0f) f * FULL_FRAME_DIAG_MM / d else 0f
        }

    val sensorAreaMm2: Float get() = sensorWidthMm * sensorHeightMm

    companion object { const val FULL_FRAME_DIAG_MM = 43.2666f }
}

@Serializable
data class Snapshot(
    val format: Int = 1,
    val fingerprint: String,
    val device: String,
    val sdk: Int,
    val createdAt: Long = 0L,
    val listedIds: List<String>,
    val cameras: List<CameraInfo>,
    val concurrentSets: List<List<String>> = emptyList(),
    val neverTouch: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

val SnapshotJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

fun Snapshot.toJson(): String = SnapshotJson.encodeToString(Snapshot.serializer(), this)
fun snapshotFromJson(s: String): Snapshot = SnapshotJson.decodeFromString(Snapshot.serializer(), s)
