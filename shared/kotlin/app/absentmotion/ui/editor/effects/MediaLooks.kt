package app.absentmotion.ui.editor.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.absentmotion.effects.MediaLook
import app.absentmotion.effects.mediaLook
import app.absentmotion.effects.screenFromName
import app.absentmotion.library.openableUri
import app.absentmotion.media.openGrabber
import app.absentmotion.model.MediaFill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val looks = ConcurrentHashMap<String, MediaLook>()

internal fun knownLook(media: MediaFill): MediaLook? = looks[lookKey(media)]

internal suspend fun mediaLookOf(context: Context, media: MediaFill): MediaLook {
    looks[lookKey(media)]?.let { return it }
    val bitmap = try {
        if (media.video) videoFrame(context, media) else withContext(Dispatchers.IO) { smallPicture(context, media.uri) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    val seen = bitmap?.let {
        try {
            val pixels = IntArray(it.width * it.height)
            it.getPixels(pixels, 0, it.width, 0, 0, it.width, it.height)
            mediaLook(pixels, it.width, it.height)
        } finally {
            it.recycle()
        }
    } ?: MediaLook()
    val look = if (seen.screen == null) seen.copy(screen = screenFromName(media.uri)) else seen
    if (looks.size > 256) looks.clear()
    looks[lookKey(media)] = look
    return look
}

private fun lookKey(media: MediaFill) = "${media.uri}#${media.inTime}"

private suspend fun videoFrame(context: Context, media: MediaFill): Bitmap? = withContext(Dispatchers.IO) {
    val grabber = runCatching { openGrabber(context, Uri.parse(openableUri(media.uri))) }.getOrNull() ?: return@withContext null
    try {
        grabber.scaledFrame(media.inTime * 1000L + 300_000L, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320)
    } finally {
        grabber.release()
    }
}

private fun smallPicture(context: Context, uri: String): Bitmap? {
    val bytes = context.contentResolver.openInputStream(Uri.parse(openableUri(uri)))?.use { it.readBytes() } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 160 || bounds.outHeight / sample > 160) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
