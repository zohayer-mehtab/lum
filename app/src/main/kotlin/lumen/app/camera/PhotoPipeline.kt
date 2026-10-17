package lumen.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import lumen.core.FilterDef
import lumen.core.Filters
import lumen.core.MotionContainer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/** What was selected when the shutter fired (the JPEG arrives later, on the save thread). */
class Shot(val filterId: String, val recorder: MotionRecorder?, val orientation: Int)

class SaveResult(val name: String, val warn: String?)

/** JPEG -> optional colour filter -> optional Motion Photo container -> MediaStore. Runs on the save thread. */
object PhotoPipeline {
    fun process(ctx: Context, jpeg: ByteArray, sensorTsNs: Long, shot: Shot?): Result<SaveResult> {
        var bytes = jpeg
        val warns = ArrayList<String>()
        val filter = Filters.byId(shot?.filterId)
        if (!filter.isIdentity) {
            val f = runCatching { applyFilter(ctx, bytes, filter) }
            if (f.isSuccess) bytes = f.getOrThrow() else warns += "filter skipped"
        }
        var motion = false
        val rec = shot?.recorder
        val orientation = shot?.orientation ?: 0
        if (rec != null) {
            val clip = runCatching { rec.export(sensorTsNs / 1000, orientation, ctx.cacheDir) }.getOrNull()
            val packed = clip?.let { MotionContainer.build(bytes, it.mp4, it.presentationUs) }
            if (packed != null) { bytes = packed; motion = true } else warns += "motion clip unavailable"
        }
        return StillSaver.write(ctx, bytes, motion).map { SaveResult(it, warns.joinToString().ifEmpty { null }) }
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
    )

    /** Decodes, applies the colour matrix, re-encodes at q95 and copies the key EXIF tags (incl. orientation). */
    fun applyFilter(ctx: Context, src: ByteArray, f: FilterDef): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: error("decode failed")
        val out = try {
            val o = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(f.matrix)) }
            Canvas(o).drawBitmap(bmp, 0f, 0f, paint)
            o
        } finally { bmp.recycle() }
        val tmp = File.createTempFile("flt", ".jpg", ctx.cacheDir)
        try {
            FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            out.recycle()
            val from = ExifInterface(ByteArrayInputStream(src))
            val to = ExifInterface(tmp.absolutePath)
            for (t in EXIF_TAGS) from.getAttribute(t)?.let { to.setAttribute(t, it) }
            to.saveAttributes()
            return tmp.readBytes()
        } finally { tmp.delete() }
    }
}
