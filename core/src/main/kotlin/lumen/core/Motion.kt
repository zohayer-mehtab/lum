package lumen.core

import java.io.ByteArrayOutputStream

/** Metadata of one encoded video sample. */
class MSample(val ptsUs: Long, val key: Boolean)

/** Pure selection rules for the Motion Photo ring buffer. */
object MotionWindow {
    /**
     * Indices (inclusive) of the clip around [anchorUs]: starts at the latest key frame at or before
     * anchor-[preUs] (else the first key frame), ends at the last sample at or before anchor+[postUs].
     * The anchor is clamped into the buffered range. Null when there is no key frame.
     */
    fun select(s: List<MSample>, anchorUs: Long, preUs: Long, postUs: Long): IntRange? {
        if (s.isEmpty()) return null
        val a = anchorUs.coerceIn(s.first().ptsUs, s.last().ptsUs)
        var start = -1
        for (i in s.indices) if (s[i].key && s[i].ptsUs <= a - preUs) start = i
        if (start < 0) start = s.indexOfFirst { it.key }
        if (start < 0) return null
        var end = start
        for (i in start until s.size) if (s[i].ptsUs <= a + postUs) end = i
        return start..end
    }

    /** Index of the first sample to keep: the latest key frame at or before latest-[retainUs] (0 if none). */
    fun keepFrom(s: List<MSample>, latestUs: Long, retainUs: Long): Int {
        var k = 0
        for (i in s.indices) if (s[i].key && s[i].ptsUs <= latestUs - retainUs) k = i
        return k
    }
}

/** Google Motion Photo (container v1 + legacy MicroVideo tags): JPEG with an XMP APP1 and the MP4 appended. */
object MotionContainer {
    private const val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000"

    fun xmp(videoLen: Int, presentationUs: Long): String = """<?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Lumen">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about=""
 xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
 xmlns:Container="http://ns.google.com/photos/1.0/container/"
 xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
 GCamera:MotionPhoto="1"
 GCamera:MotionPhotoVersion="1"
 GCamera:MotionPhotoPresentationTimestampUs="$presentationUs"
 GCamera:MicroVideo="1"
 GCamera:MicroVideoVersion="1"
 GCamera:MicroVideoOffset="$videoLen"
 GCamera:MicroVideoPresentationTimestampUs="$presentationUs">
<Container:Directory>
<rdf:Seq>
<rdf:li rdf:parseType="Resource">
<Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
</rdf:li>
<rdf:li rdf:parseType="Resource">
<Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLen" Item:Padding="0"/>
</rdf:li>
</rdf:Seq>
</Container:Directory>
</rdf:Description>
</rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""

    private fun u8(b: Byte) = b.toInt() and 0xFF

    /** Inserts [xmp] as an APP1 after the leading APP0/APP1 segments, dropping any existing XMP. Null if not a JPEG. */
    fun insertXmp(jpeg: ByteArray, xmp: String): ByteArray? {
        if (jpeg.size < 4 || u8(jpeg[0]) != 0xFF || u8(jpeg[1]) != 0xD8) return null
        val out = ByteArrayOutputStream(jpeg.size + 2048)
        out.write(jpeg, 0, 2)
        var p = 2
        while (p + 4 <= jpeg.size && u8(jpeg[p]) == 0xFF) {
            val m = u8(jpeg[p + 1])
            if (m != 0xE0 && m != 0xE1) break
            val len = (u8(jpeg[p + 2]) shl 8) or u8(jpeg[p + 3])
            if (len < 2 || p + 2 + len > jpeg.size) return null
            val isXmp = m == 0xE1 && len - 2 >= XMP_ID.length &&
                String(jpeg, p + 4, XMP_ID.length, Charsets.ISO_8859_1) == XMP_ID
            if (!isXmp) out.write(jpeg, p, 2 + len)
            p += 2 + len
        }
        val payload = XMP_ID.toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8)
        if (payload.size + 2 > 0xFFFF) return null
        val segLen = payload.size + 2
        out.write(0xFF); out.write(0xE1); out.write(segLen shr 8); out.write(segLen and 0xFF)
        out.write(payload)
        out.write(jpeg, p, jpeg.size - p)
        return out.toByteArray()
    }

    /** JPEG(+XMP) followed by the MP4. Null if [jpeg] is not a JPEG. */
    fun build(jpeg: ByteArray, mp4: ByteArray, presentationUs: Long): ByteArray? {
        val j = insertXmp(jpeg, xmp(mp4.size, presentationUs)) ?: return null
        return j + mp4
    }
}
