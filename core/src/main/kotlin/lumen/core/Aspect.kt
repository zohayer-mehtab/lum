package lumen.core

import kotlin.math.abs

/** A selectable photo aspect ratio backed by a real JPEG size from the stream map. [ratio] = long/short. */
data class AspectOpt(val id: String, val label: String, val ratio: Float, val jpeg: Sz)

private val TARGETS = listOf(
    Triple("4:3", "4:3", 4f / 3f),
    Triple("16:9", "16:9", 16f / 9f),
    Triple("1:1", "1:1", 1f),
    Triple("20:9", "Full", 20f / 9f),
)

/** Offered aspects: only those the camera reports a JPEG size for (largest such size at or below [maxPixels]). */
fun aspectOptions(jpegSizes: List<String>, maxPixels: Long = 12_500_000L, tol: Float = 0.01f): List<AspectOpt> {
    val all = jpegSizes.mapNotNull(::parseSize).filter { it.area <= maxPixels }
    return TARGETS.mapNotNull { (id, label, r) ->
        all.filter { abs(it.longShortRatio() / r - 1f) <= tol }.maxByOrNull { it.area }?.let { AspectOpt(id, label, r, it) }
    }
}
