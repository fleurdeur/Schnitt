package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import android.content.Context
import app.absentmotion.media.FrameReader
import app.absentmotion.media.MotionTracker
import app.absentmotion.media.luma
import app.absentmotion.media.trackFactor
import app.absentmotion.model.Layer
import app.absentmotion.model.Pt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun trackStartPoints(width: Int, height: Int, count: Int): List<Pt> = when {
    count <= 1 -> listOf(Pt(width / 2f, height / 2f))
    count == 2 -> listOf(Pt(width * 0.35f, height / 2f), Pt(width * 0.65f, height / 2f))
    else -> {
        // A 4/6/8 point grid gives the homography solver enough spread.  Keep
        // the points away from the border so the patch tracker has context.
        val cols = if (count >= 6) 3 else 2
        val rows = (count + cols - 1) / cols
        (0 until count).map { i ->
            val c = i % cols; val r = i / cols
            Pt(width * (0.2f + 0.6f * (c + 0.5f) / cols), height * (0.2f + 0.6f * (r + 0.5f) / rows))
        }
    }
}

val Layer.canTrack: Boolean get() = mediaFill?.video == true

fun EditorState.startTracking() {
    val layer = selected?.takeIf { it.canTrack } ?: return
    val media = scene.media(layer.mediaFill!!.uri)?.takeIf { it.width > 0 && it.height > 0 } ?: return
    if (!layer.activeAt(timeMs)) frame = scene.firstShownFrame(layer.startTime)
    selectedId = layer.id
    playing = false
    panel = EditorPanel.Tools
    drawing = TrackMode(layer.id).apply { points += trackStartPoints(media.width, media.height, 1) }
}

fun EditorState.setTrackPointCount(mode: TrackMode, count: Int) {
    if (mode.running || mode.points.size == count) return
    if (mode.camera && count < 4) return
    val media = scene.layer(mode.layerId)?.mediaFill?.uri?.let(scene::media) ?: return
    mode.points.clear()
    mode.points += trackStartPoints(media.width, media.height, count)
    mode.pending = emptyList()
    mode.lostFrame = null
}

/** Starts a 4-point planar camera solve.  The button lives in the 3D panel. */
fun EditorState.startCameraTracking() {
    val video = selected?.takeIf { it.canTrack } ?: return
    val media = scene.media(video.mediaFill!!.uri)?.takeIf { it.width > 0 && it.height > 0 } ?: return
    if (!video.activeAt(timeMs)) frame = scene.firstShownFrame(video.startTime)
    if (scene.layers.none { it.kind == "camera" }) addCamera()
    val cam = scene.layers.firstOrNull { it.kind == "camera" } ?: return
    selectedId = video.id
    playing = false
    panel = EditorPanel.Tools
    drawing = TrackMode(video.id).apply {
        points += trackStartPoints(media.width, media.height, 4)
        camera = true
        cameraId = cam.id
        stabilize = false
    }
}

/** Two-point inverse track that locks the selected clip against camera shake. */
fun EditorState.startStabilize() {
    val video = selected?.takeIf { it.canTrack } ?: return
    val media = scene.media(video.mediaFill!!.uri)?.takeIf { it.width > 0 && it.height > 0 } ?: return
    if (!video.activeAt(timeMs)) frame = scene.firstShownFrame(video.startTime)
    selectedId = video.id
    playing = false
    panel = EditorPanel.Tools
    drawing = TrackMode(video.id).apply {
        points += trackStartPoints(media.width, media.height, 2)
        camera = false
        stabilize = true
        target = video.id
    }
}

fun EditorState.runTracking(context: Context, mode: TrackMode, scope: CoroutineScope, resume: Boolean) {
    if (mode.running) return
    val document = scene
    val video = document.layer(mode.layerId) ?: return
    val uri = video.mediaFill?.uri ?: return
    val media = document.media(uri) ?: return
    val width = media.width
    val height = media.height
    val continuing = resume && mode.pending.isNotEmpty() && mode.apply != null && mode.motion.size > 1
    val runs = if (continuing) {
        listOf(TrackRun(mode.pending.first().frames, mode.points.toList(), mode.pending.first().base)) + mode.pending.drop(1)
    } else {
        document.trackRuns(video, frame, mode.range).map { TrackRun(it, mode.points.toList(), Sim()) }
    }
    if (runs.isEmpty() || width <= 0 || height <= 0) {
        mode.message = AppText.get(R.string.track_motion_playhead_outside)
        return
    }
    if (!continuing) {
        val f0 = runs.first().frames.first()
        val anchor = document.videoToScene(video, f0, width, height)?.map(mode.points.first())
        if (anchor == null) {
            mode.message = AppText.get(R.string.track_motion_no_area)
            return
        }
        val chosen = mode.target?.let(document::layer) ?: mode.cameraId?.let(document::layer)
        mode.motion.clear()
        mode.motion[f0] = Sim()
        mode.apply = TrackApply(
            chosen?.id ?: Layer.newId(),
            chosen?.let { document.place(it, document.frameStart(f0)) } ?: Place(anchor.x, anchor.y, 0f, 0f, 1f, 1f, 0f),
            mode.points.size > 1,
        )
    }
    val apply = mode.apply ?: return
    mode.pending = emptyList()
    mode.lostFrame = null
    mode.running = true
    mode.progress = 0f
    mode.message = ""
    mode.job = scope.launch {
        var lost = false
        var last: TrackStep? = null
        var partial: (() -> Map<Int, Sim>)? = null
        try {
            withContext(Dispatchers.IO) {
                FrameReader(context, uri).use { reader ->
                    for ((index, run) in runs.withIndex()) {
                        var shown = 0L
                        val pts = document.framePts(video, run.frames, reader.timestamps)
                        val found = HashMap<Long, List<Pt>>()
                        partial = { document.motionOf(video, trackedSteps(run.frames, pts, found).steps, width, height, run.base) }
                        val outcome = trackFrames(
                            run.frames, pts, reader.timestamps, run.points, width, height,
                            read = { batch, deliver -> reader.read(batch) { p, yuv -> deliver(p, yuv.luma(trackFactor(yuv.width, yuv.height))) } },
                            tracker = { MotionTracker(it) }, found = found,
                        ) { step, fraction ->
                            val now = System.nanoTime()
                            if (now - shown > LIVE_NS) {
                                shown = now
                                withContext(Dispatchers.Main) {
                                    mode.live = step.points
                                    frame = step.frame
                                    mode.progress = (index + fraction) / runs.size
                                }
                            }
                        }
                        val motion = document.motionOf(video, outcome.steps, width, height, run.base)
                        last = outcome.steps.lastOrNull() ?: last
                        val poses = if (mode.camera) {
                            val camera = mode.cameraId?.let(document::layer)
                            if (camera == null) emptyMap() else document.cameraTrack(
                                camera, outcome.steps, width, height,
                                toScene = { f, p -> document.videoToScene(video, f, width, height)?.map(p) },
                                base = cameraBase(camera), baseZoom = document.cameraZoom,
                            )
                        } else emptyMap()
                        withContext(Dispatchers.Main) {
                            mode.motion.putAll(motion)
                            if (poses.isNotEmpty()) {
                                mode.cameraPoses.putAll(poses)
                                edit("camera-track") { it.withCameraTrack(mode.cameraId!!, poses) }
                            } else if (motion.size > 1) writeTrack(mode, video, apply)
                        }
                        partial = null
                        val at = outcome.lostAt ?: continue
                        val base = outcome.steps.lastOrNull()?.let { mode.motion[it.frame] } ?: run.base
                        mode.pending = listOf(TrackRun(run.frames.dropWhile { it != at }, emptyList(), base)) + runs.drop(index + 1)
                        withContext(Dispatchers.Main) {
                            mode.points.clear()
                            mode.points += outcome.lostPoints
                            frame = at
                            mode.lostFrame = at
                            mode.message = if (outcome.steps.isEmpty()) AppText.get(R.string.track_motion_too_little_detail)
                                else AppText.get(R.string.track_motion_lost_at_frame, (at + 1).toString())
                        }
                        lost = true
                        break
                    }
                }
            }
            if (!lost) {
                last?.let { step ->
                    mode.points.clear()
                    mode.points += step.points
                    frame = step.frame
                }
                val count = if (mode.camera) mode.cameraPoses.size else mode.motion.size
                val label = if (mode.camera) AppText.get(R.string.model_layer_camera) else scene.layer(apply.targetId)?.label?.ifEmpty { null } ?: AppText.get(R.string.track_motion_target_layer)
                mode.message = AppText.get(R.string.track_motion_done, count.toString(), label)
            }
        } catch (_: CancellationException) {
            partial?.takeIf { scope.isActive }?.invoke()?.takeIf { it.size > 1 }?.let { mode.motion.putAll(it); writeTrack(mode, video, apply) }
            mode.message = AppText.get(R.string.track_motion_cancelled)
        } catch (_: LinkageError) {
            mode.message = AppText.get(R.string.track_motion_unavailable)
        } catch (e: Exception) {
            mode.message = app.absentmotion.i18n.failureText(AppText.get(R.string.track_motion_failed), e)
        } finally {
            mode.running = false
            mode.live = null
        }
    }
}

private fun EditorState.writeTrack(mode: TrackMode, video: Layer, apply: TrackApply) {
    edit { s ->
        if (s.layer(video.id) == null) return@edit s
        var next = s
        if (next.layer(apply.targetId) == null) {
            if (mode.target != null) return@edit s
            val name = AppText.get(R.string.model_layer_track)
            val count = next.layers.count { it.kind == "nullobj" && it.label.startsWith("$name ") } + 1
            next = next.add(trackNull(apply.targetId, video, Pt(apply.origin.x, apply.origin.y), "$name $count"))
        }
        if (mode.stabilize) next.withTrack(apply, mode.motion.mapValues { it.value.inverse() })
        else next.withTrack(apply, mode.motion)
    }
}

private fun Sim.inverse(): Sim {
    val d = a * a + b * b
    if (d < 1e-9) return Sim()
    return Sim(a / d, -b / d, (-a * tx - b * ty) / d, (b * tx - a * ty) / d)
}

private const val LIVE_NS = 80_000_000L
