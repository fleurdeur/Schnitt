package app.absentmotion.ai

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.absentmotion.library.openableUri
import app.absentmotion.media.openGrabber
import app.absentmotion.platform.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A face found in one frame, with the eye distance as a stable scale reference. */
internal data class FaceGuide(val centreX: Float, val centreY: Float, val eyeSpacing: Float)

internal object FaceGuides {
    suspend fun detect(context: Context, uri: String, timeMs: Int): FaceGuide? = withContext(Dispatchers.IO) {
        val grabber = runCatching { openGrabber(context, Uri.parse(openableUri(uri))) }.getOrNull() ?: return@withContext null
        var rgb: Bitmap? = null
        try {
            val frame = grabber.scaledFrame(timeMs.toLong() * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, 640, 640) ?: return@withContext null
            val evenWidth = frame.width and 1.inv()
            val evenHeight = frame.height and 1.inv()
            val square = if (evenWidth != frame.width || evenHeight != frame.height) {
                Bitmap.createBitmap(frame, 0, 0, evenWidth, evenHeight).also { frame.recycle() }
            } else frame
            rgb = square.copy(Bitmap.Config.RGB_565, false)
            square.recycle()
            val faces = arrayOfNulls<FaceDetector.Face>(1)
            val count = FaceDetector(rgb.width, rgb.height, 1).findFaces(rgb, faces)
            val face = faces.take(count).filterNotNull().maxByOrNull { it.confidence() } ?: return@withContext null
            if (face.confidence() < 0.3f) return@withContext null
            val mid = PointF()
            face.getMidPoint(mid)
            FaceGuide(mid.x / rgb.width, mid.y / rgb.height, face.eyesDistance() / rgb.width)
        } catch (_: Exception) {
            null
        } finally {
            rgb?.recycle()
            grabber.release()
        }
    }
}
