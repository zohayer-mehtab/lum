package lumen.core

/** Features gated by queried hardware data. UI shows a feature only when it is in the set (and implemented). */
enum class Feature { ASPECT, TIMER, FILTERS, MOTION_PHOTO }

private fun maxFps(range: String): Int = range.substringAfterLast('-').toIntOrNull() ?: 0

/** Motion Photo needs a 30 fps video-capable size that matches a photo aspect. */
fun supportsMotion(i: CameraInfo): Boolean =
    i.fpsRanges.any { maxFps(it) >= 30 } && aspectOptions(i.jpegSizes).any { pickMotionSize(i.videoSizes, it.jpeg) != null }

fun featuresOf(i: CameraInfo): Set<Feature> = buildSet {
    add(Feature.TIMER); add(Feature.FILTERS)
    if (aspectOptions(i.jpegSizes).size >= 2) add(Feature.ASPECT)
    if (supportsMotion(i)) add(Feature.MOTION_PHOTO)
}
