package app.absentmotion.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import app.absentmotion.ffamp.FfampNative
import kotlin.math.roundToInt

internal data class FfampInfo(
    val width: Int, val height: Int, val rotation: Int, val durationUs: Long,
    val fps: Float?, val mime: String?, val hasAudio: Boolean, val trackCount: Int,
)

/**
 * Opt-in probe for the FFamp native media reader. It reads containers and
 * track headers that some platform extractors reject. Playback and decoding
 * still use the platform path when FFamp cannot open a file.
 */
internal object FfampProbe {
    val available: Boolean get() = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    fun enabled(context: Context): Boolean =
        available && context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("ffamp", false)

    fun probe(context: Context, uri: Uri): FfampInfo? {
        if (!available) return null
        var descriptor: ParcelFileDescriptor? = null
        var handle = 0L
        return runCatching {
            val path = if (uri.scheme == "file") {
                uri.path ?: return null
            } else {
                val opened = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                descriptor = opened
                "/proc/self/fd/${opened.fd}"
            }
            handle = FfampNative.open(path)
            if (handle == 0L) return null
            val count = FfampNative.tracks(handle)
            var video: LongArray? = null
            var audio = false
            for (i in 0 until count) {
                val track = FfampNative.track(handle, i) ?: continue
                if (track.size > 0 && track[0] == 1L && video == null) video = track
                if (track.size > 0 && track[0] == 2L) audio = true
            }
            val v = video ?: return null
            val duration = v.getOrNull(6)?.takeIf { it > 0 } ?: 0L
            val fps = if (duration > 0 && (v.getOrNull(7) ?: 0) > 1) (v[7] * 1_000_000f / duration).let {
                if (it.isFinite() && it > 0) it.roundToInt().toFloat() else null
            } else null
            FfampInfo(
                width = (v.getOrNull(1) ?: 0).toInt(), height = (v.getOrNull(2) ?: 0).toInt(),
                rotation = (((v.getOrNull(3) ?: 0).toInt() % 360) + 360) % 360,
                durationUs = duration, fps = fps,
                mime = runCatching { FfampNative.mime(handle, 0) }.getOrNull(),
                hasAudio = audio, trackCount = count,
            )
        }.getOrNull().also {
            if (handle != 0L) runCatching { FfampNative.close(handle) }
            runCatching { descriptor?.close() }
        }
    }
}
