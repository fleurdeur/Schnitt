package app.absentmotion.media

import app.absentmotion.platform.alias
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlin.math.roundToInt

/**
 * Finds an H.264 encoder/size combination that the current device actually
 * advertises.  Reverse and GIF export must never assume 1080p is available:
 * some phones only expose 720p, aligned dimensions, or portrait-specific
 * profiles.  Candidates preserve the source aspect ratio and are all even.
 */
internal data class EncoderTarget(val width: Int, val height: Int, val name: String)

private data class H264Encoder(val name: String, val caps: MediaCodecInfo.VideoCapabilities)

internal fun h264Target(width: Int, height: Int, fps: Int): EncoderTarget? {
    if (width < 2 || height < 2) return null
    val encoders = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.mapNotNull { info ->
            if (!info.isEncoder || info.alias || MediaFormat.MIMETYPE_VIDEO_AVC !in info.supportedTypes) return@mapNotNull null
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull()
                ?: return@mapNotNull null
            H264Encoder(info.name, caps)
        }
    }.getOrDefault(emptyList())
    if (encoders.isEmpty()) return null
    val rate = fps.coerceIn(1, 240)
    val scales = LinkedHashSet<Double>().apply {
        add(1.0)
        var scale = 0.9
        while (scale >= 0.25) { add(scale); scale -= 0.05 }
        add(0.2); add(0.15); add(0.1)
        add(2.0); add(4.0); add(8.0)
    }
    // Reverse must not crop or silently resize a clip. Keep the source size even
    // when the encoder's advertised rate range is narrower than the source fps.
    val exactW = even(width.toDouble())
    val exactH = even(height.toDouble())
    for (encoder in encoders) {
        val caps = encoder.caps
        if (runCatching { caps.isSizeSupported(exactW, exactH) }.getOrDefault(false)) {
            return EncoderTarget(exactW, exactH, encoder.name)
        }
    }
    val commons = listOf(1920 to 1080, 1280 to 720, 1080 to 1920, 720 to 1280, 1080 to 1080, 720 to 720, 640 to 360, 360 to 640)
    val candidates = ArrayList<Pair<Int, Int>>()
    for (scale in scales) candidates += even(width * scale) to even(height * scale)
    candidates += commons
    for ((w, h) in candidates) {
        if (w < 2 || h < 2) continue
        for (encoder in encoders) {
            val caps = encoder.caps
            val fits = runCatching {
                caps.isSizeSupported(w, h) && runCatching { caps.areSizeAndRateSupported(w, h, rate.toDouble()) }.getOrDefault(true)
            }.getOrDefault(false)
            if (fits) return EncoderTarget(w, h, encoder.name)
        }
    }
    // Last chance: the lower port width/height range with the source aspect.
    for (encoder in encoders) {
        val caps = encoder.caps
        val w = even(caps.supportedWidths.lower.toDouble()).coerceAtLeast(2)
        val h = even(caps.supportedHeights.lower.toDouble()).coerceAtLeast(2)
        val fits = runCatching { caps.isSizeSupported(w, h) }.getOrDefault(false)
        if (fits) return EncoderTarget(w, h, encoder.name)
    }
    return null
}

internal fun h264EncoderNames(width: Int, height: Int, fps: Int): List<String> {
    return runCatching {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && !it.alias && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
            .mapNotNull { info ->
                val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull()
                    ?: return@mapNotNull null
                if (!runCatching { caps.isSizeSupported(width, height) }.getOrDefault(false)) return@mapNotNull null
                val rated = runCatching { caps.areSizeAndRateSupported(width, height, fps.toDouble()) }.getOrDefault(false)
                Triple(info, caps, rated)
            }
        // Prefer an encoder that accepts the source rate, but never lose the exact
        // source dimensions just because a high frame rate is outside the hint.
        (encoders.filter { it.third } + encoders.filterNot { it.third }).distinctBy { it.first.name }
            .sortedByDescending { it.first.isHardwareAccelerated }
            .map { it.first.name }
    }.getOrDefault(emptyList())
}

private fun even(value: Double): Int = maxOf(2, (value / 2).roundToInt() * 2)

internal fun h264Size(width: Int, height: Int, fps: Int): Pair<Int, Int>? =
    h264Target(width, height, fps)?.let { it.width to it.height }

internal fun h264SizeForSummary(width: Int, height: Int): Pair<Int, Int>? {
    val target = h264Target(width, height, 30) ?: return null
    return target.width to target.height
}
