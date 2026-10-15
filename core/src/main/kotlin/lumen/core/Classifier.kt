package lumen.core

import kotlin.math.abs

data class Classification(
    val id: String,
    val role: Role,
    val confidence: Confidence,
    val ratio: Float,       // 35mm-equiv relative to the reference main camera; 0 if unknown
    val reason: String,
)

/**
 * Role classification from computed optics only; never from ID numbers.
 * Reference "main" = back, non-logical camera with the largest sensor area
 * (ties: equiv focal closest to 26mm). All ratios are relative to it.
 */
object Classifier {
    private val NOMINAL = listOf(Role.ULTRA_WIDE to 0.6f, Role.MAIN to 1f, Role.TELE_3X to 3f, Role.PERISCOPE to 10f)

    fun classify(cameras: List<CameraInfo>): List<Classification> {
        val backPhysical = cameras.filter { it.facing == "BACK" && !it.isLogical && it.equiv35 > 0f && it.readError == null }
        val ref = backPhysical.maxWithOrNull(
            compareBy<CameraInfo> { it.sensorAreaMm2 }.thenBy { -abs(it.equiv35 - 26f) }
        )
        val out = cameras.map { c -> classifyOne(c, ref) }
        return demoteDuplicates(out)
    }

    private fun classifyOne(c: CameraInfo, ref: CameraInfo?): Classification {
        if (c.readError != null) return Classification(c.id, Role.UNKNOWN, Confidence.LOW, 0f, "characteristics unreadable: ${c.readError}")
        if (c.facing == "FRONT") return Classification(c.id, Role.FRONT, Confidence.HIGH, 0f, "facing=FRONT")
        if (c.isLogical) return Classification(c.id, Role.LOGICAL, Confidence.HIGH, 0f,
            "logical multi-camera (physical: ${c.physicalIds.joinToString()}); never opened in Phase 2")
        if (c.facing != "BACK") return Classification(c.id, Role.UNKNOWN, Confidence.LOW, 0f, "facing=${c.facing}")
        if (ref == null || c.equiv35 <= 0f) return Classification(c.id, Role.UNKNOWN, Confidence.LOW, 0f, "missing focal/sensor data")
        val ratio = c.equiv35 / ref.equiv35
        val (role, nominal) = NOMINAL.minBy { abs(ratio / it.second - 1f) }
        val dev = abs(ratio / nominal - 1f)
        val conf = when { dev <= 0.15f -> Confidence.HIGH; dev <= 0.30f -> Confidence.MEDIUM; else -> Confidence.LOW }
        val r = if (conf == Confidence.LOW) Role.UNKNOWN else role
        return Classification(c.id, r, conf, ratio,
            "equiv=%.1fmm ratio=%.2f nearest=%s(%.1fx) dev=%.0f%%".format(c.equiv35, ratio, role, nominal, dev * 100))
    }

    /** Two cameras claiming the same non-front role cannot both be trusted. */
    private fun demoteDuplicates(list: List<Classification>): List<Classification> {
        val dup = list.filter { it.role in setOf(Role.ULTRA_WIDE, Role.MAIN, Role.TELE_3X, Role.PERISCOPE) }
            .groupBy { it.role }.filterValues { it.size > 1 }.keys
        return list.map {
            if (it.role in dup) it.copy(confidence = Confidence.LOW, reason = it.reason + "; DUPLICATE role claim") else it
        }
    }
}
