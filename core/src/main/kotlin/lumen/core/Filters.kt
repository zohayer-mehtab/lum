package lumen.core

/** Colour filter as a 4x5 matrix with android.graphics.ColorMatrix semantics (row-major, offsets on a 0..255 scale). */
class FilterDef(val id: String, val label: String, val matrix: FloatArray) {
    val isIdentity: Boolean get() = id == Filters.NONE_ID
}

object Filters {
    const val NONE_ID = "none"

    fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Applies [a] first, then [b] (same as ColorMatrix.postConcat). */
    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(20)
        for (r in 0..3) for (c in 0..4) {
            var s = 0f
            for (k in 0..3) s += b[r * 5 + k] * a[k * 5 + c]
            if (c == 4) s += b[r * 5 + 4]
            o[r * 5 + c] = s
        }
        return o
    }

    fun saturation(s: Float): FloatArray {
        val inv = 1f - s
        val r = inv * 0.213f; val g = inv * 0.715f; val b = inv * 0.072f
        return floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun contrast(c: Float): FloatArray {
        val t = (0.5f - 0.5f * c) * 255f
        return floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun scale(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun lift(o: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, o,
        0f, 1f, 0f, 0f, o,
        0f, 0f, 1f, 0f, o,
        0f, 0f, 0f, 1f, 0f,
    )

    val all: List<FilterDef> = listOf(
        FilterDef(NONE_ID, "Original", identity()),
        FilterDef("vivid", "Vivid", concat(saturation(1.35f), contrast(1.12f))),
        FilterDef("warm", "Warm", concat(scale(1.08f, 1.0f, 0.90f), saturation(1.05f))),
        FilterDef("cool", "Cool", scale(0.92f, 1.0f, 1.08f)),
        FilterDef("mono", "Mono", saturation(0f)),
        FilterDef("noir", "Noir", concat(saturation(0f), contrast(1.35f))),
        FilterDef("fade", "Fade", concat(concat(saturation(0.85f), contrast(0.88f)), lift(10f))),
        FilterDef("sepia", "Sepia", floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )),
    )

    fun byId(id: String?): FilterDef = all.firstOrNull { it.id == id } ?: all.first()
}
