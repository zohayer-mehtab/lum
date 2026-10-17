package lumen.core

import org.junit.Assert.*
import org.junit.Test

private fun realSnapshot(): Snapshot =
    snapshotFromJson(Snapshot::class.java.getResource("/fixtures/lumen-snapshot.json")!!.readText())

class AspectTest {
    @Test fun realMainCamera() {
        val main = realSnapshot().cameras.first { it.id == "0" }
        val o = aspectOptions(main.jpegSizes)
        assertEquals(listOf("4:3", "16:9", "1:1", "20:9"), o.map { it.id })
        assertEquals(Sz(4000, 3000), o[0].jpeg)
        assertEquals(Sz(4000, 2252), o[1].jpeg)      // not exactly 16:9, accepted within 1%
        assertEquals(Sz(2992, 2992), o[2].jpeg)
        assertEquals(Sz(4000, 1800), o[3].jpeg)
        assertEquals("Full", o[3].label)
    }
    @Test fun realFrontCamera() {
        val front = realSnapshot().cameras.first { it.id == "1" }
        assertEquals(listOf(Sz(3648, 2736), Sz(3648, 2048), Sz(2736, 2736), Sz(3648, 1640)), aspectOptions(front.jpegSizes).map { it.jpeg })
    }
    @Test fun missingAspectsAreNotOffered() = assertEquals(listOf("4:3"), aspectOptions(listOf("4000x3000", "1920x1440")).map { it.id })
    @Test fun previewMatchesAspect() {
        val main = realSnapshot().cameras.first { it.id == "0" }
        assertEquals(Sz(1440, 1080), pickPreviewSize(main.previewSizes, Sz(4000, 3000)))
        assertEquals(Sz(1920, 1080), pickPreviewSize(main.previewSizes, Sz(4000, 2252)))
        assertEquals(Sz(1088, 1088), pickPreviewSize(main.previewSizes, Sz(2992, 2992)))
        assertEquals(Sz(2400, 1080), pickPreviewSize(main.previewSizes, Sz(4000, 1800)))   // nothing under 1080p area: smallest of pool
    }
    @Test fun motionSizes() {
        val main = realSnapshot().cameras.first { it.id == "0" }
        assertEquals(Sz(1920, 1440), pickMotionSize(main.videoSizes, Sz(4000, 3000)))
        assertEquals(Sz(1920, 1080), pickMotionSize(main.videoSizes, Sz(4000, 2252)))
        assertEquals(Sz(1088, 1088), pickMotionSize(main.videoSizes, Sz(2992, 2992)))
        assertEquals(Sz(2400, 1080), pickMotionSize(main.videoSizes, Sz(4000, 1800)))
        assertNull(pickMotionSize(emptyList(), Sz(4000, 3000)))
    }
}

class FilterTest {
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> = Triple(
        m[0] * r + m[1] * g + m[2] * b + m[4], m[5] * r + m[6] * g + m[7] * b + m[9], m[10] * r + m[11] * g + m[12] * b + m[14])

    @Test fun allWellFormed() { Filters.all.forEach { assertEquals(it.id, 20, it.matrix.size) }; assertEquals(Filters.all.size, Filters.all.map { it.id }.toSet().size) }
    @Test fun identityIsNeutral() {
        val a = Filters.contrast(1.3f)
        assertArrayEquals(a, Filters.concat(Filters.identity(), a), 1e-4f)
        assertArrayEquals(a, Filters.concat(a, Filters.identity()), 1e-4f)
    }
    @Test fun concatOrderAndOffsets() {
        val a = Filters.identity().also { it[0] = 2f; it[4] = 10f }   // R' = 2R + 10
        val b = Filters.identity().also { it[0] = 3f }                // R'' = 3R'
        val c = Filters.concat(a, b)
        assertEquals(6f, c[0], 1e-4f); assertEquals(30f, c[4], 1e-4f)
    }
    @Test fun monoKeepsGrey() {
        val (r, g, b) = apply(Filters.byId("mono").matrix, 100f, 100f, 100f)
        assertEquals(100f, r, 0.1f); assertEquals(100f, g, 0.1f); assertEquals(100f, b, 0.1f)
        val (r2, g2, b2) = apply(Filters.byId("mono").matrix, 200f, 50f, 10f)
        assertEquals(r2, g2, 0.01f); assertEquals(g2, b2, 0.01f)
    }
    @Test fun contrastKeepsMidGrey() = assertEquals(127.5f, apply(Filters.contrast(1.5f), 127.5f, 127.5f, 127.5f).first, 0.1f)
    @Test fun lookup() { assertTrue(Filters.byId("nope").isIdentity); assertTrue(Filters.byId(null).isIdentity); assertEquals("Sepia", Filters.byId("sepia").label) }
}

class FeatureTest {
    @Test fun realDevice() {
        val s = realSnapshot()
        val main = featuresOf(s.cameras.first { it.id == "0" })
        assertEquals(setOf(Feature.TIMER, Feature.FILTERS, Feature.ASPECT, Feature.MOTION_PHOTO), main)
        assertEquals(setOf(Feature.TIMER, Feature.FILTERS, Feature.ASPECT, Feature.MOTION_PHOTO), featuresOf(s.cameras.first { it.id == "2" }))
    }
    @Test fun noVideoNoMotion() = assertFalse(Feature.MOTION_PHOTO in featuresOf(MAIN.copy(jpegSizes = listOf("4000x3000"))))
    @Test fun slowFpsNoMotion() =
        assertFalse(supportsMotion(MAIN.copy(jpegSizes = listOf("4000x3000"), videoSizes = listOf("1920x1440"), fpsRanges = listOf("7-15"))))
}

class MotionTest {
    private fun s(vararg p: Pair<Long, Boolean>) = p.map { MSample(it.first * 1000, it.second) }

    @Test fun selectsKeyBeforePreRoll() {
        // key frames at 0, 1000, 2000, 3000 ms; frames every 500 ms
        val l = s(0L to true, 500L to false, 1000L to true, 1500L to false, 2000L to true, 2500L to false, 3000L to true, 3500L to false, 4000L to false)
        val r = MotionWindow.select(l, anchorUs = 3_000_000, preUs = 1_500_000, postUs = 500_000)!!
        assertEquals(2, r.first)           // key at 1000 ms <= 3000-1500
        assertEquals(7, r.last)            // 3500 ms is the last <= 4000
    }
    @Test fun firstKeyWhenBufferTooShort() {
        val l = s(100L to true, 600L to false, 1100L to false)
        val r = MotionWindow.select(l, 500_000, 1_500_000, 1_500_000)!!
        assertEquals(0..2, r)
    }
    @Test fun anchorOutsideBufferIsClamped() {
        val l = s(0L to true, 1000L to true, 2000L to false)
        assertEquals(1..2, MotionWindow.select(l, 999_000_000, 1_000_000, 0))
        assertEquals(0..0, MotionWindow.select(l, -5, 1_000_000, 500_000))
    }
    @Test fun noKeyFrameNoClip() { assertNull(MotionWindow.select(s(0L to false, 100L to false), 0, 1, 1)); assertNull(MotionWindow.select(emptyList(), 0, 1, 1)) }
    @Test fun ringKeepsKeyFrameBeforeWindow() {
        val l = s(0L to true, 1000L to true, 2000L to true, 3000L to true, 3500L to false)
        assertEquals(1, MotionWindow.keepFrom(l, 3_500_000, 2_000_000))   // keep from the key at 1000 ms (<= 1500)
        assertEquals(0, MotionWindow.keepFrom(l, 3_500_000, 99_000_000))
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE1.toByte(), 0, 8, 'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
        0xFF.toByte(), 0xDB.toByte(), 0, 4, 1, 2, 0xFF.toByte(), 0xD9.toByte())
    private val mp4 = ByteArray(1000) { (it % 251).toByte() }

    @Test fun containerLayout() {
        val out = MotionContainer.build(jpeg, mp4, 1_500_000)!!
        assertEquals(0xFF, out[0].toInt() and 0xFF); assertEquals(0xD8, out[1].toInt() and 0xFF)
        assertEquals(0xE1, out[3].toInt() and 0xFF)                              // Exif kept first
        assertArrayEquals(mp4, out.copyOfRange(out.size - mp4.size, out.size))   // video appended
        val text = String(out, Charsets.ISO_8859_1)
        val idx = text.indexOf("http://ns.adobe.com/xap/1.0/")
        assertTrue(idx > 12)
        val segStart = idx - 4
        assertEquals(0xFF, out[segStart].toInt() and 0xFF); assertEquals(0xE1, out[segStart + 1].toInt() and 0xFF)
        val len = ((out[segStart + 2].toInt() and 0xFF) shl 8) or (out[segStart + 3].toInt() and 0xFF)
        assertEquals(0xFF, out[segStart + 2 + len].toInt() and 0xFF)             // next segment (DQT) follows directly
        assertEquals(0xDB, out[segStart + 3 + len].toInt() and 0xFF)
        assertTrue(text.contains("GCamera:MicroVideoOffset=\"1000\""))
        assertTrue(text.contains("Item:Length=\"1000\""))
        assertTrue(text.contains("MotionPhotoPresentationTimestampUs=\"1500000\""))
    }
    @Test fun replacesExistingXmp() {
        val once = MotionContainer.insertXmp(jpeg, "<a/>")!!
        val twice = MotionContainer.insertXmp(once, "<b/>")!!
        val t = String(twice, Charsets.ISO_8859_1)
        assertEquals(1, Regex("ns.adobe.com/xap/1.0/").findAll(t).count())
        assertTrue(t.contains("<b/>")); assertFalse(t.contains("<a/>"))
    }
    @Test fun rejectsNonJpeg() { assertNull(MotionContainer.build(byteArrayOf(1, 2, 3, 4, 5), mp4, 0)) }
}
