package app.absentmotion.editor

import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Pt
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.cameraFov
import app.absentmotion.model.finiteFloat
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframes
import app.absentmotion.model.withKeyframes
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.tan

/**
 * A small planar-camera solver used by the 3D camera tracker.
 *
 * A tracked plane gives a homography between the reference frame and every
 * later frame.  With the camera's focal length we can take the rotation and
 * the relative translation out of that homography.  Translation and scale are
 * taken from the more stable similarity part; the homography supplies the
 * perspective (yaw/pitch) so the result is a real 3D camera move rather than a
 * flat corner pin.
 */
internal data class CameraPose(
    val x: Float, val y: Float, val z: Float,
    val rotation: Float, val tiltX: Float, val tiltY: Float,
    val fov: Float,
)

internal data class Homography(val h: DoubleArray)

internal fun homography(from: List<Pt>, to: List<Pt>): Homography? {
    if (from.size < 4 || from.size != to.size) return null
    val ata = Array(8) { DoubleArray(8) }
    val atb = DoubleArray(8)
    fun row(a: DoubleArray, b: Double) {
        for (i in 0 until 8) {
            atb[i] += a[i] * b
            for (j in 0 until 8) ata[i][j] += a[i] * a[j]
        }
    }
    for (k in from.indices) {
        val x = from[k].x.toDouble(); val y = from[k].y.toDouble()
        val u = to[k].x.toDouble(); val v = to[k].y.toDouble()
        row(doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y), u)
        row(doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y), v)
    }
    for (col in 0 until 8) {
        var pivot = col
        for (r in col + 1 until 8) if (abs(ata[r][col]) > abs(ata[pivot][col])) pivot = r
        if (abs(ata[pivot][col]) < 1e-10) return null
        if (pivot != col) {
            val t = ata[pivot]; ata[pivot] = ata[col]; ata[col] = t
            val b = atb[pivot]; atb[pivot] = atb[col]; atb[col] = b
        }
        val d = ata[col][col]
        for (j in col until 8) ata[col][j] /= d
        atb[col] /= d
        for (r in 0 until 8) {
            if (r == col) continue
            val f = ata[r][col]
            if (abs(f) < 1e-15) continue
            for (j in col until 8) ata[r][j] -= f * ata[col][j]
            atb[r] -= f * atb[col]
        }
    }
    return Homography(doubleArrayOf(atb[0], atb[1], atb[2], atb[3], atb[4], atb[5], atb[6], atb[7], 1.0))
}

private data class Rotation(val yaw: Double, val pitch: Double, val roll: Double)

private fun rotationOf(h: Homography, fov: Float): Rotation? {
    val hh = h.h
    val f = 1.0 / (2.0 * tan(Math.toRadians(fov.coerceIn(1f, 179f) / 2.0)))
    var r1 = doubleArrayOf(hh[0], hh[3], hh[6] * f)
    var r2 = doubleArrayOf(hh[1], hh[4], hh[7] * f)
    val n1 = hypot(hypot(r1[0], r1[1]), r1[2])
    val n2 = hypot(hypot(r2[0], r2[1]), r2[2])
    if (n1 < 1e-9 || n2 < 1e-9) return null
    r1 = DoubleArray(3) { r1[it] / n1 }
    r2 = DoubleArray(3) { r2[it] / n2 }
    val dot = r1[0] * r2[0] + r1[1] * r2[1] + r1[2] * r2[2]
    r2 = DoubleArray(3) { r2[it] - r1[it] * dot }
    val n = hypot(hypot(r2[0], r2[1]), r2[2])
    if (n < 1e-9) return null
    r2 = DoubleArray(3) { r2[it] / n }
    val r3 = doubleArrayOf(r1[1] * r2[2] - r1[2] * r2[1], r1[2] * r2[0] - r1[0] * r2[2], r1[0] * r2[1] - r1[1] * r2[0])
    val yaw = atan2(r1[1], r1[0])
    val pitch = atan2(-r1[2], hypot(r1[0], r1[1]))
    val roll = atan2(r2[2], r3[2])
    return Rotation(yaw, pitch, roll)
}

private fun normalize(p: Pt, width: Float, height: Float) = Pt(p.x / max(width, 1f) - 0.5f, p.y / max(height, 1f) - 0.5f)

internal fun Scene.cameraTrack(
    camera: Layer, steps: List<TrackStep>, width: Int, height: Int,
    toScene: (Int, Pt) -> Pt?, base: CameraPose, baseZoom: Float,
): Map<Int, CameraPose> {
    val reference = steps.firstOrNull()?.points ?: return emptyMap()
    val w = max(width, 1).toFloat(); val h = max(height, 1).toFloat()
    val ref = steps.first().points.mapNotNull { toScene(steps.first().frame, it) }.map { normalize(it, w, h) }
    if (ref.size < 4 || ref.size != reference.size) return emptyMap()
    val out = LinkedHashMap<Int, CameraPose>()
    for (step in steps) {
        val now = step.points.mapNotNull { toScene(step.frame, it) }.map { normalize(it, w, h) }
        if (now.size != ref.size) continue
        val sim = Sim.between(ref, now)
        val scale = sim.scale.takeIf { it.isFinite() && it > 0.001 } ?: 1.0
        val rot = homography(ref, now)?.let { rotationOf(it, base.fov) }
        val dx = sim.tx * w
        val dy = sim.ty * h
        val yaw = Math.toDegrees(rot?.yaw ?: 0.0).toFloat()
        val pitch = Math.toDegrees(rot?.pitch ?: 0.0).toFloat()
        val z = if (base.z < -1f) base.z / scale.toFloat() else base.z - (scale.toFloat() - 1f) * baseZoom
        out[step.frame] = CameraPose(
            x = base.x - dx.toFloat(),
            y = base.y - dy.toFloat(),
            z = z.coerceIn(-baseZoom * 20f, baseZoom * 4f),
            rotation = base.rotation - sim.degrees.toFloat(),
            tiltX = (base.tiltX + pitch).coerceIn(-89f, 89f),
            tiltY = (base.tiltY + yaw).coerceIn(-89f, 89f),
            fov = base.fov,
        )
    }
    return out
}

internal fun Scene.withCameraTrack(cameraId: Long, poses: Map<Int, CameraPose>): Scene {
    val camera = layer(cameraId) ?: return this
    if (poses.isEmpty()) return this
    val length = (camera.endTime - camera.startTime).coerceAtLeast(1).toFloat()
    val frames = poses.keys.sorted()
    val t = FloatArray(frames.size) { ((frameStart(frames[it]) - camera.startTime) / length).coerceIn(0f, 1f) }
    fun writeOn(source: Scene, ref: PropRef, values: List<FloatArray>): Scene {
        val keys = values.mapIndexed { i, v -> Keyframe(t[i], formatFloats(v)) }
        return source.update(cameraId) { current ->
            val element = current.element(ref) ?: XmlNode(ref.name)
            val outside = element.keyframes.filter { it.t < t.first() - 0.001f || it.t > t.last() + 0.001f }
            current.withElementCompat(ref, element.withKeyframes(outside + keys))
        }
    }
    var scene = writeOn(this, PropRef.Location, frames.map { poses.getValue(it).let { p -> floatArrayOf(p.x, p.y, p.z) } })
    scene = writeOn(scene, PropRef.Rotation, frames.map { floatArrayOf(poses.getValue(it).rotation) })
    return writeOn(scene, PropRef.Tilt, frames.map { poses.getValue(it).let { p -> floatArrayOf(p.tiltX, p.tiltY) } })
}

private fun Layer.withElementCompat(ref: PropRef, element: XmlNode): Layer = when (ref.kind) {
    PropRef.Kind.Transform -> copy(node = node.withChild("transform", (node.child("transform") ?: XmlNode("transform")).withChild(ref.name, element)))
    PropRef.Kind.Child -> copy(node = node.withChild(ref.name, element))
    else -> this
}

internal fun cameraBase(camera: Layer): CameraPose {
    val at = camera.location
    val tilt = camera.tilt
    return CameraPose(at[0], at[1], at[2], camera.transform("rotation", 0f)[0], tilt[0], tilt[1], camera.cameraFov)
}
