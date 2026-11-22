package app.absentmotion.ai

import android.content.Context
import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import kotlin.math.abs

internal data class DfrResult(val media: AiMedia, val removed: Int, val total: Int)

/**
 * Re-encodes a clip while skipping frames that repeat the previous frame.
 * Timestamps are kept from the source, so motion remains in sync and a frozen
 * span stays frozen instead of being sped up. Skipping the duplicate samples
 * removes the dead payload and the visible stutter caused by re-encoding it.
 */
internal object DeadFrameRemover {
    suspend fun run(context: Context, layer: Layer, media: MediaDeclaration, progress: AiProgress): DfrResult {
        var previous: IntArray? = null
        var removed = 0
        var total = 0
        val output = rewriteVideo(context, VideoJob(layer, media), progress) { pts, argb, width, height, emit ->
            total++
            val last = previous
            if (last != null && sameFrame(last, argb, width, height)) {
                removed++
            } else {
                previous = argb.copyOf()
                emit(pts, argb)
            }
            progress(0.5f, AppText.get(R.string.ai_dfr) + "  " + AppText.get(R.string.ai_dfr_frames, removed.toString(), total.toString()))
        }
        return DfrResult(output, removed, total)
    }

    internal fun sameFrame(a: IntArray, b: IntArray, width: Int, height: Int, tolerance: Int = 5): Boolean {
        if (a.size != b.size) return false
        val step = ((width.toLong() * height) / 900L).toInt().coerceAtLeast(1)
        var checked = 0
        var at = 0
        while (at < a.size) {
            val x = a[at]
            val y = b[at]
            if (abs((x shr 16 and 255) - (y shr 16 and 255)) > tolerance) return false
            if (abs((x shr 8 and 255) - (y shr 8 and 255)) > tolerance) return false
            if (abs((x and 255) - (y and 255)) > tolerance) return false
            checked++
            at += step
        }
        return checked > 0
    }
}
