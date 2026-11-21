package app.absentmotion.media

import app.absentmotion.platform.numberOf
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import app.absentmotion.model.fpsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun mediaDeclaration(context: Context, uri: Uri): MediaDeclaration? =
    if (context.contentResolver.getType(uri)?.startsWith("video/") == true) videoDeclaration(context, uri) else imageDeclaration(context, uri)

suspend fun videoDeclaration(context: Context, uri: Uri): MediaDeclaration? = withContext(Dispatchers.IO) {
    val ffamp = if (FfampProbe.enabled(context)) FfampProbe.probe(context, uri) else null
    val extractor = MediaExtractor()
    try {
        var name = uri.lastPathSegment ?: ""
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
        }
        val width: Int; val height: Int; val rotation: Int; val duration: Long; val fps: Float?
        if (ffamp != null) {
            width = ffamp.width; height = ffamp.height; rotation = ffamp.rotation; duration = ffamp.durationUs
            fps = ffamp.fps
        } else {
            extractor.setDataSource(context, uri, null)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val retriever = MediaMetadataRetriever()
            var turned = 0
            var length = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            try {
                retriever.setDataSource(context, uri)
                turned = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (length <= 0) length = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            } finally { retriever.release() }
            width = visibleSize(format).first; height = visibleSize(format).second; rotation = turned; duration = length
            fps = (if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.numberOf(MediaFormat.KEY_FRAME_RATE)?.toFloat() else null)?.takeIf { it > 0 }
                ?: runCatching { estimatedFps(VideoSources.timestamps(context, uri.toString())) }.getOrNull()
        }
        MediaDeclaration(XmlNode("media", listOfNotNull("uri" to uri.toString(), "filename" to name, "title" to name,
            "type" to (context.contentResolver.getType(uri) ?: "video/mp4"), "size" to "$size", "orientation" to "$rotation",
            "duration" to "$duration",
            fps?.let { "fps" to fpsText(it) }, "infoUpdated" to "${System.currentTimeMillis()}",
            "width" to "$width", "height" to "$height").toMap(LinkedHashMap())))
    } catch (_: Exception) { null } finally { extractor.release() }
}
