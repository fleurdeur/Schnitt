package app.absentmotion.editor

import android.content.Context
import android.graphics.Bitmap
import app.absentmotion.R
import app.absentmotion.ai.DepthAnything
import app.absentmotion.ai.MatteEncoder
import app.absentmotion.ai.aiFile
import app.absentmotion.ai.aiMedia
import app.absentmotion.ai.matteBytes
import app.absentmotion.ai.matteSize
import app.absentmotion.ai.matteStamps
import app.absentmotion.i18n.AppText
import app.absentmotion.media.FrameReader
import app.absentmotion.model.*
import app.absentmotion.ai.matteSeconds
import app.absentmotion.ai.processFromMs
import app.absentmotion.ai.argb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Runs real Depth Anything V2 inference over every selected video frame and
 * encodes the result as a grayscale depth video (near subject dark, far
 * background white).  The depth video is added as a normal editable layer.
 */
suspend fun EditorState.addDepthMap(context: Context, layer: Layer, progress: (Float) -> Unit = {}): Int {
    check(DepthAnything.installed(context)) { AppText.get(R.string.depth_need_model) }
    val fill = layer.mediaFill?.takeIf { it.video } ?: return 0
    val media = scene.media(fill.uri) ?: return 0
    val seconds = layer.matteSeconds(media)
    check(seconds <= 300.0) { AppText.get(R.string.ai_clip_too_long, "5") }
    val (width, height) = matteSize(media.width, media.height, 720)
    val file = aiFile(context, "mp4")
    val needed = matteBytes(seconds, width, height) * 2 + 50_000_000L
    check(file.parentFile!!.usableSpace >= needed) { AppText.get(R.string.ai_not_enough_storage, (needed / 1_000_000).toString()) }
    val fps = (media.fps ?: 30f).roundToInt().coerceIn(1, 240)
    var success = false
    try {
        val encoded = withContext(Dispatchers.IO) {
            var actual = width to height
            FrameReader(context, fill.uri).use { reader ->
                val stamps = matteStamps(reader.timestamps, layer.processFromMs, fill.outTime)
                check(stamps.isNotEmpty()) { AppText.get(R.string.ai_no_video_frames) }
                MatteEncoder.open(file, width, height, fps).use { encoder ->
                    actual = encoder.width to encoder.height
                    var done = 0
                    for (batch in stamps.chunked(8)) {
                        reader.read(batch) { pts, yuv ->
                            val argb = yuv.argb(encoder.width, encoder.height, reader.colorStandard, reader.fullRange)
                            val bitmap = Bitmap.createBitmap(argb, encoder.width, encoder.height, Bitmap.Config.ARGB_8888)
                            val depth = try { DepthAnything.depthGray(context, bitmap, encoder.width, encoder.height) } finally { bitmap.recycle() }
                            encoder.write(pts, depth)
                            done++
                            progress(0.02f + 0.97f * done / stamps.size)
                        }
                    }
                    progress(0.99f)
                    encoder.finish()
                }
            }
            actual
        }
        val output = aiMedia(context, file, "video/mp4", mapOf(
            "width" to "${encoded.first}", "height" to "${encoded.second}", "orientation" to "0",
            "duration" to "${media.duration}", "fps" to "$fps",
        ))
        val placed = scene.videoLayer(output.declaration, frame) ?: return 0
        val depth = placed.copy(node = placed.node.with("label", "Depth · " + layer.label)
            .with("startTime", "${layer.startTime}").with("endTime", "${layer.endTime}"))
        edit { it.add(depth, listOf(output.declaration)) }
        selectedId = depth.id
        panel = EditorPanel.Tools
        success = true
        return 1
    } finally {
        if (!success) file.delete()
    }
}
