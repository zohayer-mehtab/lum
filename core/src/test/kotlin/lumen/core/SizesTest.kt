package lumen.core

import org.junit.Assert.*
import org.junit.Test

class SizesTest {
    @Test fun jpegDefault() {
        val s = listOf("12000x9000", "4000x3000", "4032x3024", "4000x2250", "3000x4000")
        assertEquals(Sz(4032, 3024), pickJpegSize(s))
    }
    @Test fun jpegFallbackNo43() = assertEquals(Sz(4000, 2250), pickJpegSize(listOf("4000x2250", "1920x1080", "20000x10000")))
    @Test fun previewPrefers43UnderCap() {
        val p = listOf("1920x1080", "1600x1200", "1440x1080", "4000x3000")
        assertEquals(Sz(1600, 1200), pickPreviewSize(p, Sz(4000, 3000)))
    }
    @Test fun probeSmall() = assertEquals(Sz(320, 240), pickProbeSize(listOf("1920x1080", "320x240", "176x144")))
    @Test fun badInput() { assertNull(parseSize("abc")); assertNull(pickJpegSize(emptyList())) }
}
