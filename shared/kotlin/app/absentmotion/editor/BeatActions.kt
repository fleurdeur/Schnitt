package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.ai.SPEECH_RATE
import app.absentmotion.ai.speechChunks
import app.absentmotion.audio.BeatAnalysis
import app.absentmotion.audio.BeatBand
import app.absentmotion.audio.Beats
import app.absentmotion.audio.OfflineMix
import app.absentmotion.audio.Resampler
import app.absentmotion.audio.soundUri
import app.absentmotion.i18n.AppText
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframes
import app.absentmotion.model.withKeyframes
import app.absentmotion.platform.Context
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun EditorState.analyseBeats(
    context: Context,
    layerId: Long,
    sensitivity: Float,
    band: BeatBand,
    minBpm: Int,
    maxBpm: Int,
    onProgress: (Float) -> Unit = {},
): BeatAnalysis {
    val layer = scene.layer(layerId)?.takeIf { it.soundUri != null } ?: error(AppText.get(R.string.edit_beats_select_layer))
    val path = groupPath
    val alone = Scene(scene.node.copy(children = scene.node.children.filter { it.name !in Scene.LAYER_TAGS } +
        layer.node.with("hidden", null).let { it.copy(children = it.children.filterNot { c -> c.name == "gain" }) }))
    val mix = withContext(Dispatchers.IO) { OfflineMix.of(context, alone, Resampler.HIGH) } ?: error(AppText.get(R.string.edit_beats_no_sound))
    val stream = Beats.Stream(SPEECH_RATE, sensitivity, band, minBpm.toDouble(), maxBpm.toDouble())
    try {
        withContext(Dispatchers.IO) {
            speechChunks(mix, layer.startTime, layer.endTime + 1, onProgress) { chunk, count -> stream.add(chunk, count) }
        }
    } finally {
        (mix as? Closeable)?.close()
    }
    if (groupPath != path) return BeatAnalysis(0f, emptyList())
    return withContext(Dispatchers.Default) { stream.analysis() }
}

/**
 * Adds a keyframe on every detected beat for the layer's transform values.
 * The values are the layer's current values, so baking never changes the look;
 * it only creates exact beat markers for effects and further animation.
 */
fun EditorState.bakeBeatKeyframes(beats: List<Double>, targetId: Long): Int {
    val target = scene.layer(targetId) ?: return 0
    if (beats.isEmpty()) return 0
    val start = target.startTime
    val length = (target.endTime - target.startTime).coerceAtLeast(1)
    val refs = listOf(
        PropRef.Opacity to formatFloats(floatArrayOf(target.transform("opacity", 1f)[0])),
        PropRef.Scale to formatFloats(target.scale),
        PropRef.Location to formatFloats(target.location),
    )
    val before = groupPath
    var made = 0
    edit { scene ->
        var next = scene
        for ((ref, base) in refs) {
            val element = target.element(ref) ?: XmlNode(ref.name, mapOf("value" to base))
            val times = beats.map { (((it - start) / length).toFloat()).coerceIn(0f, 1f) }
                .filter { it in 0f..1f }.distinctBy { (it * 1_000_000).toInt() }
            if (times.isEmpty()) continue
            val fresh = times.map { Keyframe(it, base, "linear") }
            val existing = element.keyframes.filter { old -> fresh.none { abs(it.t - old.t) < 1e-5f } }
            next = next.update(target.id) { layer -> layer.withElement(ref, element.withKeyframes((existing + fresh).sortedBy { it.t })) }
            made += fresh.size
        }
        next
    }
    return if (groupPath == before) made else 0
}

/** Frames of the detected beats, clipped to the source layer, for the mini ruler. */
fun EditorState.beatFrames(analysis: BeatAnalysis, layerId: Long): List<Int> {
    val layer = scene.layer(layerId) ?: return emptyList()
    val fps = scene.fps
    val first = scene.frameAt(layer.startTime)
    val last = scene.frameAt(layer.endTime)
    return analysis.beats.map { (((layer.startTime + it) * fps / 1000.0).roundToInt()) }.filter { it in first..last }.distinct()
}

internal fun EditorState.beatSourceLayers(): List<Layer> = scene.layers.filter { it.kind == "audio" && it.soundUri != null }
