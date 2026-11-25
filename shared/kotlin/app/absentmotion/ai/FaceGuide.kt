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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
            humanFace(rgb) ?: animeEyes(rgb)
        } catch (_: Exception) {
            null
        } finally {
            rgb?.recycle()
            grabber.release()
        }
    }

    /** The platform frontal-face detector.  Solid on photographic faces. */
    private fun humanFace(rgb: Bitmap): FaceGuide? {
        val faces = arrayOfNulls<FaceDetector.Face>(1)
        val count = FaceDetector(rgb.width, rgb.height, 1).findFaces(rgb, faces)
        val face = faces.take(count).filterNotNull().maxByOrNull { it.confidence() } ?: return null
        if (face.confidence() < 0.3f) return null
        val mid = PointF()
        face.getMidPoint(mid)
        return FaceGuide(mid.x / rgb.width, mid.y / rgb.height, face.eyesDistance() / rgb.width)
    }

    /**
     * Fallback for anime and cartoon faces the frontal detector cannot see:
     * finds the two darkest compact blobs that sit symmetrically in the upper
     * part of the frame and reads them as the eyes.  Returns null when no
     * confident pair exists, never a guess.
     */
    private fun animeEyes(rgb: Bitmap): FaceGuide? {
        val w = min(rgb.width, 160)
        val h = min(rgb.height, 160)
        if (w < 40 || h < 40) return null
        val small = Bitmap.createScaledBitmap(rgb, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== rgb) small.recycle()
        val luma = FloatArray(w * h) { i ->
            val c = pixels[i]
            (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF))
        }
        // Search the upper-middle area where an eye line lives in a portrait.
        val x0 = (w * 0.18f).toInt(); val x1 = (w * 0.82f).toInt()
        val y0 = (h * 0.06f).toInt(); val y1 = (h * 0.58f).toInt()
        val region = luma.filterIndexed { i, _ -> i / w in y0 until y1 && i % w in x0 until x1 }
        if (region.size < 200) return null
        val sorted = region.sorted()
        val dark = (sorted[sorted.size / 10] + sorted[sorted.size / 4]) / 2f
        if (dark >= sorted[sorted.size * 3 / 4]) return null
        val mask = BooleanArray(w * h) { i -> luma[i] <= dark && i / w in y0 until y1 && i % w in x0 until x1 }
        val blobs = blobs(luma, mask, w, h)
        val candidates = blobs.filter { it.area in 6..(w * h / 60) && it.mean < dark }
        val best = bestPair(candidates, w, h) ?: return null
        val (a, b) = best
        val spacing = abs(a.cx - b.cx) / w
        val lineY = ((a.cy + b.cy) / 2f) / h
        val midX = ((a.cx + b.cx) / 2f) / w
        return FaceGuide(midX, lineY, spacing)
    }

    private class Blob(val cx: Float, val cy: Float, val area: Int, val mean: Float)

    /** Flood-fills the dark mask into blobs with centroid and mean luma. */
    private fun blobs(luma: FloatArray, mask: BooleanArray, w: Int, h: Int): List<Blob> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Blob>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var sumX = 0; var sumY = 0; var area = 0; var sum = 0f
            while (top > 0) {
                val at = stack[--top]
                val x = at % w; val y = at / w
                sumX += x; sumY += y; area++; sum += luma[at]
                if (x > 0 && mask[at - 1] && !seen[at - 1]) { seen[at - 1] = true; stack[top++] = at - 1 }
                if (x < w - 1 && mask[at + 1] && !seen[at + 1]) { seen[at + 1] = true; stack[top++] = at + 1 }
                if (y > 0 && mask[at - w] && !seen[at - w]) { seen[at - w] = true; stack[top++] = at - w }
                if (y < h - 1 && mask[at + w] && !seen[at + w]) { seen[at + w] = true; stack[top++] = at + w }
            }
            out += Blob(sumX / area.toFloat(), sumY / area.toFloat(), area, sum / area)
        }
        return out
    }

    /** Picks the eye-looking pair: dark, similar height, symmetric about the centre. */
    private fun bestPair(candidates: List<Blob>, w: Int, h: Int): Pair<Blob, Blob>? {
        var best: Pair<Blob, Blob>? = null
        var bestScore = 0f
        for (i in candidates.indices) {
            val a = candidates[i]
            for (j in i + 1 until candidates.size) {
                val b = candidates[j]
                if (min(a.cx, b.cx) >= max(a.cx, b.cx)) continue
                val dx = abs(a.cx - b.cx)
                val dy = abs(a.cy - b.cy)
                if (dx < w * 0.08f || dx > w * 0.5f) continue
                if (dy > h * 0.18f) continue
                val mid = (a.cx + b.cx) / 2f
                if (abs(mid - w / 2f) > w * 0.22f) continue
                val size = max(a.area, b.area).toFloat() / min(a.area, b.area).toFloat()
                if (size > 3f) continue
                val score = (1f - dy / (h * 0.18f)) + (1f - abs(mid - w / 2f) / (w * 0.22f)) + 1f - min(a.mean, b.mean) / 255f
                if (score > bestScore) { bestScore = score; best = a to b }
            }
        }
        return best
    }
}
