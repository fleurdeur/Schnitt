package app.absentmotion.media

import app.absentmotion.platform.intOr
import android.media.MediaFormat

internal fun videoColorStandard(standard: Int): Int {
    if (standard < 64) return when (standard) {
        3 -> 1
        10 -> 2
        else -> standard
    }
    val matrix = if (standard >= 0x10000) (standard - 0x10000) / 256 else (standard - 64) / 7
    return when (matrix) {
        1 -> 1
        3 -> 2
        5 -> 6
        else -> 0
    }
}

internal fun isHdrTransfer(transfer: Int): Boolean = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG

/**
 * True when this track may reach the renderer as a wide-gamut or 10-bit buffer.
 *
 * Those buffers are not portable: some drivers hand them to Skia in a layout or
 * color space that the preview cannot sample, which shows up as a green or
 * blocky frame. A 540p SDR copy is both smaller and safe on every device.
 */
internal fun MediaFormat.needsSdrCopy(): Boolean {
    val transfer = intOr(MediaFormat.KEY_COLOR_TRANSFER, 0)
    if (isHdrTransfer(transfer)) return true
    if (intOr(MediaFormat.KEY_COLOR_STANDARD, 0) == MediaFormat.COLOR_STANDARD_BT2020) return true
    if (containsKey(MediaFormat.KEY_HDR_STATIC_INFO) || containsKey("hdr10-plus-info")) return true
    val profile = intOr(MediaFormat.KEY_PROFILE, 0)
    return when (getString(MediaFormat.KEY_MIME)) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> profile == 0x10 || profile == 0x20 || profile == 0x40
        MediaFormat.MIMETYPE_VIDEO_HEVC -> profile == 2
        MediaFormat.MIMETYPE_VIDEO_VP9 -> profile == 2 || profile == 3
        MediaFormat.MIMETYPE_VIDEO_AV1 -> profile == 2
        else -> false
    }
}

internal fun MediaFormat.requestSdr(): Boolean = needsSdrCopy().also { sdr ->
    if (sdr) setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
}
