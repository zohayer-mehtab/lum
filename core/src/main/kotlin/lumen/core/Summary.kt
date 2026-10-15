package lumen.core

/** Plain-text summary the user pastes back. Pure; derived from the snapshot + classification. */
fun summarize(s: Snapshot, cls: List<Classification>, gate: GateState): String = buildString {
    appendLine("LUMEN DIAGNOSTICS  fp=${s.fingerprint}  device=${s.device}  sdk=${s.sdk}")
    appendLine("listedIds=${s.listedIds}  concurrent=${s.concurrentSets}")
    appendLine("neverTouch=${gate.neverTouch}  quarantined=${gate.quarantined}  verified=${gate.verified}")
    for (c in s.cameras) {
        val k = cls.firstOrNull { it.id == c.id }
        appendLine("--- id=${c.id} facing=${c.facing} logical=${c.isLogical} physical=${c.physicalIds} listed=${c.listedInCameraIdList}")
        appendLine("  role=${k?.role} conf=${k?.confidence} ratio=${k?.ratio?.let { "%.2f".format(it) }} confirmed=${gate.confirmed[c.id]}")
        appendLine("  focal=${c.focalLengths} sensor=${c.sensorWidthMm}x${c.sensorHeightMm}mm px=${c.pixelWidth}x${c.pixelHeight} equiv35=${"%.1f".format(c.equiv35)}mm")
        appendLine("  zoomRatio=${c.zoomRatioMin}..${c.zoomRatioMax} maxDigital=${c.maxDigitalZoom} hwLevel=${c.hardwareLevel} caps=${c.capabilities}")
        appendLine("  jpeg=${c.jpegSizes.take(6)} maxRes=${c.maxResJpegSizes.take(3)} video=${c.videoSizes.take(6)} fps=${c.fpsRanges}")
        appendLine("  flash=${c.flashAvailable} af=${c.afModes} ae=${c.aeModes} iso=${c.isoMin}..${c.isoMax} exp=${c.exposureMinNs}..${c.exposureMaxNs} minFocus=${c.minFocusDistance}")
        c.readError?.let { appendLine("  READ ERROR: $it") }
        k?.let { appendLine("  why: ${it.reason}") }
    }
    s.notes.forEach { appendLine("note: $it") }
}
