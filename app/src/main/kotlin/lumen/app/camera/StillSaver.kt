package lumen.app.camera

import android.content.ContentValues
import android.content.Context
import android.media.Image
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes a JPEG image to MediaStore (Pictures/Lumen). Runs on the caller's (save) thread; closes the image. */
object StillSaver {
    fun save(ctx: Context, image: Image): Result<String> {
        val bytes = try {
            val buf = image.planes[0].buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        } finally { image.close() }
        return runCatching {
            val name = "LUMEN_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumen")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("MediaStore insert returned null")
            try {
                r.openOutputStream(uri)!!.use { it.write(bytes) }
                v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0)
                r.update(uri, v, null, null)
            } catch (e: Exception) { r.delete(uri, null, null); throw e }
            name
        }
    }
}
