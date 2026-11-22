package app.absentmotion.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent cache for already-sampled project pictures.  Decoding a 12 MP
 * JPEG on every launch is one of the slowest parts of opening a project; the
 * cache stores the exact pixels the engine receives, losslessly, keyed by the
 * file version so edits and replaced media invalidate themselves.
 */
internal object PictureDiskCache {
    private const val BUDGET = 192L shl 20
    private const val MIN_WRITE = 8L shl 10
    private val writes = AtomicLong()

    private fun dir(context: Context): File = File(context.cacheDir, "picture-cache").apply { mkdirs() }

    fun decode(context: Context, resolver: ContentResolver, uri: Uri, limit: Int, alight: Boolean): Bitmap? {
        val version = fileVersion(resolver, uri) ?: return decodePicture(resolver, uri, limit, alightColours = alight)
        val key = key(uri.toString(), version, limit, alight)
        val file = File(dir(context), "$key.webp")
        if (file.isFile && file.length() > 0L) {
            cached(file, alight)?.let { file.setLastModified(System.currentTimeMillis()); return it }
            runCatching { file.delete() }
        }
        val bitmap = decodePicture(resolver, uri, limit, alightColours = alight) ?: return null
        runCatching {
            if (bitmap.byteCount.toLong() >= MIN_WRITE) {
                val made = File(dir(context), "$key.tmp")
                made.outputStream().buffered().use { out ->
                    if (bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) {
                        if (made.length() > 0L) {
                            if (file.exists()) file.delete()
                            if (!made.renameTo(file)) made.copyTo(file, overwrite = true)
                        }
                    }
                }
                made.delete()
                if (writes.incrementAndGet() % 16L == 0L) prune(context)
            }
        }
        return bitmap
    }

    private fun cached(file: File, alight: Boolean): Bitmap? = runCatching {
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (!alight) inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inScaled = false
        })
    }.getOrNull()

    private fun prune(context: Context) {
        val files = dir(context).listFiles { f -> f.isFile && f.extension == "webp" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= BUDGET) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= BUDGET) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun key(uri: String, version: String, limit: Int, alight: Boolean): String {
        val raw = "$uri\n$version\n$limit\n$alight"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
