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

/** Preview: largest 4:3 size with width (long side) <= maxLong; falls back to any size with matching aspect of [still]. */
fun pickPreviewSize(sizes: List<String>, still: Sz?, maxLong: Int = 1600): Sz? {
    val all = sizes.mapNotNull(::parseSize); if (all.isEmpty()) return null
    val sameAspect = all.filter { still != null && it.w.toLong() * still.h == it.h.toLong() * still.w }
    val pool = sameAspect.ifEmpty { all.filter { it.is43() } }.ifEmpty { all }
    return pool.filter { maxOf(it.w, it.h) <= maxLong }.maxByOrNull { it.area } ?: pool.minByOrNull { it.area }
}

/** Smallest YUV size >= 320 on the long side, for probe frames. */
fun pickProbeSize(yuv: List<String>): Sz? {
    val all = yuv.mapNotNull(::parseSize)
    return all.filter { maxOf(it.w, it.h) >= 320 }.minByOrNull { it.area } ?: all.minByOrNull { it.area }
}
