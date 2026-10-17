package lumen.app.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import lumen.core.MSample
import lumen.core.MotionWindow
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Motion Photo clip source: an H.264 surface encoder that runs while the session is open and keeps the last
 * few seconds of encoded samples. [export] cuts PRE_US before / POST_US after a shutter time and muxes an MP4.
 * Selection rules live in :core (MotionWindow). Encoder callbacks never touch the camera thread.
 */
class MotionRecorder private constructor(private val codec: MediaCodec, val surface: Surface) {
    class Clip(val mp4: ByteArray, val presentationUs: Long)
    private class Sample(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    private val samples = ArrayList<Sample>()           // guarded by itself
    @Volatile private var format: MediaFormat? = null
    @Volatile private var running = true
    @Volatile var lastPtsUs = Long.MIN_VALUE
        private set
    private val thread = Thread({ drain() }, "motion-enc")

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val i = codec.dequeueOutputBuffer(info, 10_000)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { format = codec.outputFormat; continue }
                if (i < 0) continue
                val buf = codec.getOutputBuffer(i)
                if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size); buf.get(data)
                    add(Sample(data, info.presentationTimeUs, (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0))
                }
                codec.releaseOutputBuffer(i, false)
            }
        } catch (_: Exception) { /* codec stopped or failed: export() works with what was buffered */ }
    }

    private fun add(s: Sample) {
        synchronized(samples) {
            samples.add(s)
            lastPtsUs = s.ptsUs
            if (s.key) {   // trim once per key frame
                val k = MotionWindow.keepFrom(samples.map { MSample(it.ptsUs, it.key) }, s.ptsUs, RETAIN_US)
                if (k > 0) samples.subList(0, k).clear()
            }
        }
    }

    /** Stops the encoder. Buffered samples stay available to [export]. */
    fun stop() {
        if (!running) return
        running = false
        runCatching { thread.join(800) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
    }

    /**
     * Blocks (call from the save thread) until the post-roll is buffered or a timeout passes, then muxes the clip.
     * [anchorUs] is the still's sensor timestamp; if it is not in the encoder's time base the latest frame is used.
     */
    fun export(anchorUs: Long, orientation: Int, dir: File): Clip? {
        val last0 = lastPtsUs
        if (last0 == Long.MIN_VALUE) return null
        val anchor = if (abs(anchorUs - last0) > 8_000_000L) last0 else anchorUs
        val deadline = SystemClock.elapsedRealtime() + POST_US / 1000 + 1500
        while (running && lastPtsUs < anchor + POST_US && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        val snap = synchronized(samples) { ArrayList(samples) }
        val fmt = format ?: return null
        val r = MotionWindow.select(snap.map { MSample(it.ptsUs, it.key) }, anchor, PRE_US, POST_US) ?: return null
        val clip = snap.subList(r.first, r.last + 1)
        val t0 = clip.first().ptsUs
        val tmp = File.createTempFile("mp", ".mp4", dir)
        try {
            val mux = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            try {
                mux.setOrientationHint(orientation)
                val track = mux.addTrack(fmt)
                mux.start(); started = true
                val bi = MediaCodec.BufferInfo()
                for (s in clip) {
                    bi.set(0, s.data.size, s.ptsUs - t0, if (s.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(track, ByteBuffer.wrap(s.data), bi)
                }
            } finally {
                if (started) runCatching { mux.stop() }
                runCatching { mux.release() }
            }
            return Clip(tmp.readBytes(), anchor.coerceIn(t0, clip.last().ptsUs) - t0)
        } finally { tmp.delete() }
    }

    companion object {
        const val PRE_US = 1_500_000L
        const val POST_US = 1_500_000L
        private const val RETAIN_US = PRE_US + POST_US + 1_000_000L

        fun create(w: Int, h: Int): Result<MotionRecorder> = runCatching {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 3).coerceIn(4_000_000, 20_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val s = c.createInputSurface()
                c.start()
                MotionRecorder(c, s).also { it.thread.start() }
            } catch (e: Throwable) { runCatching { c.release() }; throw e }
        }
    }
}
