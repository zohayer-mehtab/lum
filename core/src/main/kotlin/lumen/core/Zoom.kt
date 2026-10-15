package lumen.core

import kotlin.math.max

data class Lens(val role: Role, val nativeRatio: Float, val maxCrop: Float)
data class Route(val role: Role, val crop: Float)

/**
 * Pure zoom routing. Lens switch UP happens at exactly the lens' native ratio; switch DOWN needs the
 * zoom to fall below native*(1-HYST), so the lens does not flap near a boundary.
 */
class ZoomPlan(lenses: List<Lens>, private val hyst: Float = 0.03f) {
    val lenses: List<Lens> = lenses.filter { it.role != Role.FRONT }.sortedBy { it.nativeRatio }

    val floor: Float get() = lenses.firstOrNull()?.nativeRatio ?: 1f
    val ceiling: Float get() = lenses.maxOfOrNull { it.nativeRatio * it.maxCrop } ?: 1f

    fun clamp(z: Float): Float = z.coerceIn(floor, ceiling)

    fun route(requested: Float, current: Role? = null): Route {
        require(lenses.isNotEmpty()) { "no lenses" }
        val z = clamp(requested)
        var idx = 0
        for (i in lenses.indices) if (z >= lenses[i].nativeRatio - 1e-4f) idx = i
        val curIdx = lenses.indexOfFirst { it.role == current }
        // hysteresis: stay on the higher current lens until z drops clearly below its native ratio
        if (curIdx > idx && z >= lenses[curIdx].nativeRatio * (1f - hyst)) idx = curIdx
        val l = lenses[idx]
        return Route(l.role, max(1f, z / l.nativeRatio))
    }
}

/** Presets shown to the user, filtered to the reachable range. */
fun presetsFor(plan: ZoomPlan, all: List<Float> = listOf(0.6f, 1f, 2f, 3f, 10f, 20f, 30f)): List<Float> =
    all.filter { it >= plan.floor * 0.99f && it <= plan.ceiling * 1.001f }
