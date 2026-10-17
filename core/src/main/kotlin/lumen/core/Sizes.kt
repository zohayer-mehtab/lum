package lumen.core

data class Sz(val w: Int, val h: Int) {
    val area: Long get() = w.toLong() * h
    override fun toString() = "${w}x$h"
}

fun parseSize(s: String): Sz? {
    val p = s.split('x'); if (p.size != 2) return null
    val w = p[0].toIntOrNull() ?: return null; val h = p[1].toIntOrNull() ?: return null
    return Sz(w, h)
}

private fun Sz.is43() = w * 3 == h * 4 || h * 3 == w * 4

/** Default still size: largest 4:3 at or below ~12.5MP; else largest at or below it; else smallest. */
fun pickJpegSize(sizes: List<String>, maxPixels: Long = 12_500_000L): Sz? {
    val all = sizes.mapNotNull(::parseSize); if (all.isEmpty()) return null
    val ok = all.filter { it.area <= maxPixels }
    return ok.filter { it.is43() }.maxByOrNull { it.area } ?: ok.maxByOrNull { it.area } ?: all.minByOrNull { it.area }
}

fun Sz.longShortRatio(): Float = maxOf(w, h).toFloat() / minOf(w, h)

/** Same aspect within [tol] (vendors ship sizes like 4000x2252 for 16:9, which is not exact). */
fun sameAspect(a: Sz, b: Sz, tol: Float = 0.01f) = kotlin.math.abs(a.longShortRatio() / b.longShortRatio() - 1f) <= tol

/** Preview: largest size with the still's aspect (else 4:3, else any) up to [maxArea] (1080p); else the smallest of the pool. */
fun pickPreviewSize(sizes: List<String>, still: Sz?, maxArea: Long = 2_073_600L): Sz? {
    val all = sizes.mapNotNull(::parseSize); if (all.isEmpty()) return null
    val pool = all.filter { still != null && sameAspect(it, still) }.ifEmpty { all.filter { it.is43() } }.ifEmpty { all }
    return pool.filter { it.area <= maxArea }.maxByOrNull { it.area } ?: pool.minByOrNull { it.area }
}

/** Motion Photo clip size: largest video size with the still's aspect up to [maxArea]; null if none. */
fun pickMotionSize(videoSizes: List<String>, still: Sz?, maxArea: Long = 2_800_000L): Sz? {
    if (still == null) return null
    return videoSizes.mapNotNull(::parseSize).filter { sameAspect(it, still) && it.area <= maxArea }.maxByOrNull { it.area }
}

/** Smallest YUV size >= 320 on the long side, for probe frames. */
fun pickProbeSize(yuv: List<String>): Sz? {
    val all = yuv.mapNotNull(::parseSize)
    return all.filter { maxOf(it.w, it.h) >= 320 }.minByOrNull { it.area } ?: all.minByOrNull { it.area }
}
