package app.absentmotion.editor

import app.absentmotion.model.Contour
import app.absentmotion.model.Knot
import app.absentmotion.model.Layer
import app.absentmotion.model.Pt
import app.absentmotion.model.Scene
import app.absentmotion.model.ShapeKey
import app.absentmotion.model.XmlNode
import app.absentmotion.model.contours
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframes
import app.absentmotion.model.shapeKeys
import app.absentmotion.model.valueAt
import app.absentmotion.model.withShapeKeys
import android.graphics.Matrix
import kotlin.math.abs

/**
 * Rig a vector path with one lightweight null controller per point (and
 * optional tangent handles).  Nulls are ordinary project layers: they animate,
 * undo and export with the project, and their names never leak into the path.
 */
data class PathControllerOptions(
    val showNulls: Boolean = true,
    val tangentHandles: Boolean = false,
    val parented: Boolean = true,
    val scale: Float = 1f,
)

private const val FOR = "pathFor"
private const val CONTOUR = "pathContour"
private const val KNOT = "pathKnot"
private const val HANDLE = "pathHandle"
private const val LOCAL = "pathLocal"

fun EditorState.pathControllers(layerId: Long): List<Layer> =
    scene.layers.filter { it.node[FOR] == "$layerId" }

suspend fun EditorState.createPathControllers(mode: PointMode, options: PathControllerOptions): Int {
    val source = scene.layerAt(mode.layerId, sampleMs) ?: return 0
    val contours = source.contours ?: return 0
    if (contours.all { it.knots.isEmpty() }) return 0
    val old = pathControllers(source.id).map { it.id }
    val m = matrixOf(engine.layerMatrix(source.id, sampleMs))
    val inv = Matrix().also { m.invert(it) }
    var made = 0
    var next = old.fold(scene) { acc, id -> acc.remove(id) }
    fun world(p: Pt): Pt = floatArrayOf(p.x, p.y).also { m.mapPoints(it) }.let { Pt(it[0], it[1]) }
    fun local(p: Pt): Pt = floatArrayOf(p.x, p.y).also { inv.mapPoints(it) }.let { Pt(it[0], it[1]) }
    fun add(contour: Int, knot: Int, handle: String, at: Pt) {
        val id = Layer.newId()
        val worldAt = world(at)
        val node = XmlNode(
            "nullobj",
            linkedMapOf(
                "id" to "$id", "label" to "~ ${source.label} ${contour + 1}.${knot + 1}${if (handle == "point") "" else " $handle"}",
                "startTime" to "${source.startTime}", "endTime" to "${source.endTime}",
                FOR to "${source.id}", CONTOUR to "$contour", KNOT to "$knot", HANDLE to handle,
                LOCAL to formatFloats(floatArrayOf(at.x, at.y)),
            ).also { if (options.parented) it["parent"] = "${source.id}" }.also { if (!options.showNulls) it["hidden"] = "true" },
            listOf(XmlNode("transform", children = listOf(
                XmlNode("location", mapOf("value" to formatFloats(if (options.parented) floatArrayOf(at.x, at.y) else floatArrayOf(worldAt.x, worldAt.y)))),
                XmlNode("scale", mapOf("value" to formatFloats(floatArrayOf(options.scale, options.scale)))),
            ))),
        )
        next = next.add(Layer(node))
        made++
    }
    contours.forEachIndexed { ci, contour ->
        contour.knots.forEachIndexed { ki, knot ->
            add(ci, ki, "point", knot.p)
            if (options.tangentHandles) {
                knot.inHandle?.let { add(ci, ki, "in", it) }
                knot.outHandle?.let { add(ci, ki, "out", it) }
            }
        }
    }
    if (made > 0) {
        edit { next }
        selectedId = source.id
    }
    return made
}

fun EditorState.setPathControllersVisible(layerId: Long, visible: Boolean) {
    val ids = pathControllers(layerId).map { it.id }.toSet()
    if (ids.isEmpty()) return
    edit { s -> ids.fold(s) { acc, id -> acc.update(id) { it.copy(node = it.node.with("hidden", if (visible) null else "true")) } } }
}

suspend fun EditorState.setPathControllersParented(layerId: Long, parented: Boolean, timeMs: Int) {
    val controllers = pathControllers(layerId)
    if (controllers.isEmpty()) return
    val source = scene.layer(layerId) ?: return
    val m = matrixOf(engine.layerMatrix(layerId, timeMs))
    val inv = Matrix().also { m.invert(it) }
    fun toWorld(p: Pt): Pt = floatArrayOf(p.x, p.y).also { m.mapPoints(it) }.let { Pt(it[0], it[1]) }
    fun toLocal(p: Pt): Pt = floatArrayOf(p.x, p.y).also { inv.mapPoints(it) }.let { Pt(it[0], it[1]) }
    edit { s -> controllers.fold(s) { acc, c ->
        val raw = c.node[LOCAL]?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
        val stored = if (raw != null && raw.size >= 2) Pt(raw[0], raw[1]) else c.locationAt(timeMs)
        val shown = if (parented) stored else toWorld(stored)
        acc.update(c.id) { layer ->
            val transform = layer.node.child("transform") ?: XmlNode("transform")
            layer.copy(node = layer.node.with("parent", if (parented) "$layerId" else null)
                .withChild("transform", transform.withChild("location", XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(shown.x, shown.y)))))
                    .withChild("scale", transform.child("scale"))))
        }
    } }
}

fun EditorState.setPathControllerScale(layerId: Long, scale: Float) {
    val controllers = pathControllers(layerId)
    if (controllers.isEmpty()) return
    val s = scale.coerceIn(0.2f, 5f)
    edit { scene -> controllers.fold(scene) { acc, c ->
        acc.update(c.id) { layer ->
            val transform = layer.node.child("transform") ?: XmlNode("transform")
            layer.copy(node = layer.node.withChild("transform", transform.withChild("scale", XmlNode("scale", mapOf("value" to formatFloats(floatArrayOf(s, s)))))))
        }
    } }
}

/**
 * Reads every controller at each animated pose and writes ordinary shape
 * keyframes.  Path animation stays baked, so every renderer and exporter sees
 * exactly the same geometry, and a later edit can be undone in one step.
 */
suspend fun EditorState.bakePathControllers(mode: PointMode): Int {
    val source = scene.layerAt(mode.layerId, sampleMs) ?: return 0
    val controllers = pathControllers(source.id)
    if (controllers.isEmpty()) return 0
    val base = source.contours ?: return 0
    val original = source.shapeKeys
    val times = linkedSetOf<Float>()
    controllers.forEach { c ->
        val location = c.node.child("transform")?.child("location")
        location?.keyframes?.forEach { times += it.t }
    }
    original.forEach { times += it.t }
    times += source.snap.start
    if (times.isEmpty()) return 0
    val byPoint = controllers.filter { it.node[HANDLE] == "point" }.associateBy { "${it.node[CONTOUR]}:${it.node[KNOT]}" }
    val byHandle = controllers.filter { it.node[HANDLE] != "point" }.associateBy { "${it.node[CONTOUR]}:${it.node[KNOT]}:${it.node[HANDLE]}" }
    val keys = times.sorted().map { t ->
        val atMs = source.timeOf(t)
        val m = matrixOf(engine.layerMatrix(source.id, atMs))
        val inv = Matrix().also { m.invert(it) }
        fun localOf(c: Layer): Pt {
            val p = c.locationAt(atMs)
            if (c.node["parent"]?.toLongOrNull() == source.id) return p
            return floatArrayOf(p.x, p.y).also { inv.mapPoints(it) }.let { Pt(it[0], it[1]) }
        }
        val contours = base.mapIndexed { ci, contour ->
            contour.copy(knots = contour.knots.mapIndexed { ki, k ->
                val point = byPoint["$ci:$ki"]?.let(::localOf) ?: k.p
                val inHandle = byHandle["$ci:$ki:in"]?.let(::localOf) ?: k.inHandle
                val outHandle = byHandle["$ci:$ki:out"]?.let(::localOf) ?: k.outHandle
                Knot(point, inHandle, outHandle)
            })
        }
        ShapeKey(t, contours, original.firstOrNull { abs(it.t - t) < 1e-5f }?.e)
    }
    val baked = source.withShapeKeys(keys)
    edit { it.update(source.id, sampleMs) { _ -> baked } }
    return keys.size
}

private fun matrixOf(value: FloatArray?): Matrix {
    val matrix = Matrix()
    if (value != null && value.size >= 9) matrix.setValues(value.copyOfRange(0, 9))
    return matrix
}

private fun Layer.locationAt(timeMs: Int): Pt {
    val location = node.child("transform")?.child("location") ?: return Pt(0f, 0f)
    val text = location.valueAt((timeMs - startTime) / 1000f) ?: location["value"] ?: return Pt(0f, 0f)
    val parts = text.split(',').mapNotNull { it.trim().toFloatOrNull() }
    return if (parts.size >= 2) Pt(parts[0], parts[1]) else Pt(0f, 0f)
}
