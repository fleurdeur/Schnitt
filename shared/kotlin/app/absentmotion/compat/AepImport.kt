package app.absentmotion.compat

import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatColor
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Read-only After Effects project reader for RIFX/Egg! .aep files and the XML
 * .aepx variant.
 *
 * .aep: follows the public chunk layout used by After Effects. The root form is
 * RIFX/Egg!, items live in Fold/Item lists, composition settings in cdta and
 * layer records in Layr/ldta. Transform properties are read from the layer's
 * tdgp tree, including their keyframe lists. Effects, masks and text still need
 * a dedicated mapping and are not executed.
 * .aepx: reads compositions, composition settings and layer metadata from the
 * documented XML structure.
 *
 * Imported compositions become ordinary editable Schnitt scenes. Media and
 * unsupported operators are reported by the normal import report.
 */
object AepImport {
    private const val MAX_DEPTH = 128
    private const val MAX_ITEMS = 20_000
    private const val MAX_LAYERS = 40_000
    private const val MAX_KEYS = 100_000
    private const val MAX_PRECOMP_DEPTH = 12

    private class Chunk(val id: String, val start: Int, val end: Int, val type: String?, val children: List<Chunk>)

    private class Item(
        val id: Long,
        val name: String,
        val kind: Int,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Double = 0.0,
        val seconds: Double = 0.0,
        val background: Int = 0xFF000000.toInt(),
        val layers: List<AepLayer> = emptyList(),
        val mediaWidth: Int = 0,
        val mediaHeight: Int = 0,
        val solid: Int? = null,
        val sourceName: String = "",
        val path: String = "",
        val audio: Boolean = false,
    )

    private class AepLayer(
        val id: Long,
        val name: String,
        val source: Long,
        val hidden: Boolean,
        val type: Int,
        val nullObject: Boolean,
        val parent: Long,
        val start: Double,
        val stretch: Double,
        val inPoint: Double,
        val outPoint: Double,
        val transform: Transform,
        val textContent: String? = null,
    )

    private class Value(val dims: Int, val static: List<Double>?, val keys: List<Key>)

    private class Key(val at: Double, val values: List<Double>)

    private class Transform(
        val position: Value,
        val anchor: Value,
        val scale: Value,
        val rotation: Value,
        val opacity: Value,
        val text: Boolean,
    )

    fun readX(text: String): List<XmlNode> {
        val root = runCatching { XmlNode.parse(text.removePrefix("\uFEFF"), XmlNode.IMPORT_NODES) }.getOrNull() ?: return emptyList()
        if (root.name !in setOf("AfterEffectsProject", "Project", "Compositions")) return emptyList()
        val comps = root.descendants().filter { it.name == "Composition" }.toList().ifEmpty {
            root.descendants().filter { it.name == "Comp" }.toList()
        }
        return comps.mapIndexedNotNull { index, comp -> compX(comp, index) }
    }

    fun read(bytes: ByteArray): List<XmlNode> {
        if (bytes.size < 12) return emptyList()
        if (ascii(bytes, 0, 4) != "RIFX" || ascii(bytes, 8, 4) != "Egg!") return emptyList()
        val size = u32(bytes, 4).toLong().coerceAtMost(bytes.size.toLong() - 8).toInt()
        val end = (8 + size).coerceAtMost(bytes.size)
        val root = Chunk("RIFX", 0, end, "Egg!", chunks(bytes, 12, end, 0))
        val fold = root.descendants().firstOrNull { it.type == "Fold" } ?: return emptyList()
        val items = LinkedHashMap<Long, Item>()
        var count = 0

        fun walk(parent: Chunk) {
            val children = ArrayList<Chunk>()
            children += parent.children.filter { it.type == "Item" }
            children += parent.children.filter { it.type == "Sfdr" }.flatMap { c -> c.children.filter { it.type == "Item" } }
            for (child in children) {
                if (++count > MAX_ITEMS) return
                val item = item(bytes, child) ?: continue
                items[item.id] = item
                if (item.kind == 1) walk(child)
            }
        }
        walk(fold)
        val comps = items.values.filter { it.kind == 4 && it.width > 0 && it.height > 0 }
        if (comps.isEmpty()) return emptyList()
        return comps.map { comp -> scene(comp, items, bytes) }
    }

    private fun chunks(bytes: ByteArray, from: Int, to: Int, depth: Int): List<Chunk> {
        if (depth > MAX_DEPTH) return emptyList()
        val out = ArrayList<Chunk>()
        var at = from
        while (at + 8 <= to) {
            val id = ascii(bytes, at, 4) ?: break
            if (id.any { it.code !in 32..126 }) break
            val size = u32(bytes, at + 4)
            if (size < 0 || at + 8L + size > to) break
            val body = at + 8
            val end = body + size
            if (id == "LIST" && size >= 4) {
                val type = ascii(bytes, body, 4)
                out += Chunk(id, body, end, type, chunks(bytes, body + 4, end, depth + 1))
            } else {
                out += Chunk(id, body, end, null, emptyList())
            }
            at = end + (size and 1)
        }
        return out
    }

    private fun item(bytes: ByteArray, chunk: Chunk): Item? {
        val idta = chunk.children.firstOrNull { it.id == "idta" } ?: return null
        if (idta.end - idta.start < 20) return null
        val kind = u16(bytes, idta.start)
        if (kind !in setOf(1, 4, 7)) return null
        val id = u32(bytes, idta.start + 16).toLong() and 0xFFFFFFFFL
        val name = chunk.children.firstOrNull { it.id == "Utf8" }?.let { text(bytes, it) }.orEmpty().ifBlank { "Item $id" }
        if (kind == 1) return Item(id, name, kind)
        if (kind == 7) return footage(bytes, id, name, chunk)
        val cdta = chunk.children.firstOrNull { it.id == "cdta" }
        if (cdta == null || cdta.end - cdta.start < 154) return Item(id, name, kind)
        val width = u16(bytes, cdta.start + 140).coerceIn(1, 16384)
        val height = u16(bytes, cdta.start + 142).coerceIn(1, 16384)
        val divisor = u32(bytes, cdta.start + 4).toDouble()
        val dividend = u32(bytes, cdta.start + 8).toDouble()
        val fps = if (divisor > 0) dividend / divisor else 0.0
        val durationUnits = u32(bytes, cdta.start + 44).toDouble()
        val seconds = if (dividend > 0) durationUnits / dividend else 0.0
        val red = byte(bytes, cdta.start + 52)
        val green = byte(bytes, cdta.start + 53)
        val blue = byte(bytes, cdta.start + 54)
        val layers = ArrayList<AepLayer>()
        for (list in chunk.children) {
            if (list.type != "Layr") continue
            if (layers.size >= MAX_LAYERS) break
            layers += layer(bytes, list, width, height) ?: continue
        }
        return Item(id, name, kind, width, height, fps, seconds, (0xFF shl 24) or (red shl 16) or (green shl 8) or blue, layers)
    }

    private fun footage(bytes: ByteArray, id: Long, itemName: String, chunk: Chunk): Item {
        val pin = chunk.children.firstOrNull { it.type == "Pin" || it.type == "Pin " } ?: chunk
        val sspc = pin.children.firstOrNull { it.id == "sspc" }
        val opti = pin.children.firstOrNull { it.id == "opti" }
        val tag = if (sspc != null && sspc.end - sspc.start >= 26) ascii(bytes, sspc.start + 22, 4).orEmpty() else ""
        val width = if (sspc != null && sspc.end - sspc.start >= 38) u32(bytes, sspc.start + 30).coerceIn(0, 32768) else 0
        val height = if (sspc != null && sspc.end - sspc.start >= 38) u32(bytes, sspc.start + 34).coerceIn(0, 32768) else 0
        val solid = if (tag == "Soli" && opti != null && opti.end - opti.start >= 26) {
            val alpha = f32(bytes, opti.start + 10).coerceIn(0f, 1f)
            val r = f32(bytes, opti.start + 14).coerceIn(0f, 1f)
            val g = f32(bytes, opti.start + 18).coerceIn(0f, 1f)
            val b = f32(bytes, opti.start + 22).coerceIn(0f, 1f)
            ((alpha * 255).roundToInt() shl 24) or
                ((r * 255).roundToInt() shl 16) or
                ((g * 255).roundToInt() shl 8) or
                (b * 255).roundToInt()
        } else null
        val solidName = if (opti != null && opti.end - opti.start > 26) cString(bytes, opti.start + 26, opti.end) else ""
        val alias = pin.children.firstOrNull { it.id == "alas" }?.let { text(bytes, it) }
            ?: pin.children.firstOrNull { it.type == "Als2" }?.children?.firstOrNull { it.id == "alas" }?.let { text(bytes, it) }
        val path = alias?.let { runCatching { JSONObject(it).optString("fullpath") }.getOrNull()?.takeIf(String::isNotBlank) }
        val fileName = path?.substringAfterLast("\\")?.substringAfterLast('/').orEmpty()
        val sourceName = itemName.takeUnless { it.startsWith("Item ") } ?: fileName.ifBlank { solidName }
        val audio = tag == "WAVE" || fileName.substringAfterLast('.', "").lowercase() in setOf("wav", "mp3", "m4a", "aac", "ogg", "flac", "aiff", "aif")
        return Item(id, itemName, 7, mediaWidth = width, mediaHeight = height, solid = solid, sourceName = sourceName, path = path.orEmpty(), audio = audio)
    }

    private fun layer(bytes: ByteArray, chunk: Chunk, compWidth: Int, compHeight: Int): AepLayer? {
        val ldta = chunk.children.firstOrNull { it.id == "ldta" } ?: return null
        if (ldta.end - ldta.start < 140) return null
        val id = u32(bytes, ldta.start).toLong() and 0xFFFFFFFFL
        val stretchNum = i32(bytes, ldta.start + 8)
        val stretchDen = max(1, u32(bytes, ldta.start + 108))
        val stretch = stretchNum.toDouble() / stretchDen
        val start = rational(bytes, ldta.start + 12)
        val inPoint = start + rational(bytes, ldta.start + 20) * stretch
        val outPoint = start + rational(bytes, ldta.start + 28) * stretch
        val sourceRaw = u32(bytes, ldta.start + 40).toLong() and 0xFFFFFFFFL
        val type = u32(bytes, ldta.start + 128)
        val parent = u32(bytes, ldta.start + 132).toLong() and 0xFFFFFFFFL
        val video = byte(bytes, ldta.start + 39) and 0x01 != 0
        val guide = byte(bytes, ldta.start + 37) and 0x02 != 0
        val nullObject = byte(bytes, ldta.start + 38) and 0x80 != 0
        val name = chunk.children.firstOrNull { it.id == "Utf8" }?.let { text(bytes, it) }.orEmpty()
        val transform = transform(bytes, chunk, compWidth, compHeight, type == 3)
        return AepLayer(
            id = id,
            name = name,
            source = if (sourceRaw == 0L || sourceRaw == 0xFFFFFFFFL) 0L else sourceRaw,
            hidden = !video || guide,
            type = type,
            nullObject = nullObject,
            parent = parent,
            start = start,
            stretch = stretch,
            inPoint = inPoint,
            outPoint = outPoint,
            transform = transform,
            textContent = if (type == 3) textDocument(bytes, chunk) else null,
        )
    }

    private fun transform(bytes: ByteArray, chunk: Chunk, compWidth: Int, compHeight: Int, text: Boolean): Transform {
        val root = chunk.children.firstOrNull { it.type == "tdgp" }
        val group = root?.let { members(bytes, it)["ADBE Transform Group"] }
        val centre = listOf(compWidth / 2.0, compHeight / 2.0, 0.0)
        val anchorDefault = if (text) listOf(0.0, 0.0, 0.0) else listOf(0.5, 0.5, 0.0)
        return Transform(
            position = value(bytes, group, "ADBE Position", 3, centre),
            anchor = value(bytes, group, "ADBE Anchor Point", 3, anchorDefault),
            scale = value(bytes, group, "ADBE Scale", 3, listOf(1.0, 1.0, 1.0)),
            rotation = value(bytes, group, "ADBE Rotate Z", 1, listOf(0.0)),
            opacity = value(bytes, group, "ADBE Opacity", 1, listOf(1.0)),
            text = text,
        )
    }

    private fun members(bytes: ByteArray, group: Chunk): Map<String, Chunk> {
        val out = LinkedHashMap<String, Chunk>()
        var pending: String? = null
        for (child in group.children) {
            if (child.id == "tdmn") {
                pending = cString(bytes, child.start, child.end).takeUnless { it == "ADBE Group End" || it.isBlank() }
                continue
            }
            if (child.type != "tdgp" && child.type != "tdbs") continue
            val name = pending ?: continue
            out.putIfAbsent(name, child)
            pending = null
        }
        return out
    }

    private fun value(bytes: ByteArray, group: Chunk?, name: String, dims: Int, fallback: List<Double>): Value {
        val chunk = group?.let { members(bytes, it)[name] } ?: return Value(dims, fallback, emptyList())
        return property(bytes, chunk, dims) ?: Value(dims, fallback, emptyList())
    }

    private fun property(bytes: ByteArray, chunk: Chunk, dimsWanted: Int): Value? {
        val tdb4 = chunk.children.firstOrNull { it.id == "tdb4" } ?: return null
        val dims = byte(bytes, tdb4.start + 3).takeIf { it in 1..4 } ?: dimsWanted
        val timeScale = u32(bytes, tdb4.start + 12).toDouble().takeIf { it > 0 } ?: 0.0
        val static = chunk.children.firstOrNull { it.id == "cdat" }?.let { data ->
            (0 until dims).map { i -> f64(bytes, data.start + i * 8) }
        }?.takeIf { it.size == dims && it.all(Double::isFinite) }
        val list = chunk.children.firstOrNull { it.type == "list" }
        val keys = ArrayList<Key>()
        if (list != null) {
            val lhd3 = list.children.firstOrNull { it.id == "lhd3" }
            val ldat = list.children.firstOrNull { it.id == "ldat" }
            if (lhd3 != null && ldat != null) {
                val count = u32(bytes, lhd3.start + 8)
                val size = u32(bytes, lhd3.start + 16)
                val spatialSize = 56 + 24 * dims
                val plainSize = 8 + 40 * dims
                val spatial = when {
                    size == spatialSize && size != plainSize -> true
                    size == plainSize && size != spatialSize -> false
                    else -> byte(bytes, tdb4.start + 7) != 0
                }
                val step = if (spatial) 56 else 8
                if (count in 0..MAX_KEYS && size >= step + dims * 8 && timeScale > 0) {
                    for (i in 0 until count) {
                        val at = ldat.start + i * size
                        if (at + step + dims * 8 > ldat.end) break
                        val time = i32(bytes, at).toDouble() / timeScale
                        val values = (0 until dims).map { slot -> f64(bytes, at + step + slot * 8) }
                        if (values.all(Double::isFinite)) keys += Key(time, values)
                    }
                }
            }
        }
        return Value(dims, static, keys.sortedBy { it.at })
    }

    private fun compX(comp: XmlNode, index: Int): XmlNode? {
        val settings = comp.descendants().firstOrNull { it.name == "CompositionSettings" } ?: comp
        fun value(vararg keys: String): String? = keys.firstNotNullOfOrNull { settings[it] ?: comp[it] }
        val width = value("width", "compWidth")?.toDoubleOrNull()?.roundToInt()?.coerceIn(1, 16384) ?: return null
        val height = value("height", "compHeight")?.toDoubleOrNull()?.roundToInt()?.coerceIn(1, 16384) ?: return null
        val fps = value("frameRate", "fps")?.toDoubleOrNull()?.takeIf { it in 1.0..240.0 } ?: 30.0
        val seconds = value("duration", "seconds")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 5.0
        val total = (seconds.coerceAtMost(24 * 3600.0) * 1000.0).roundToInt().coerceAtLeast(1)
        val title = comp["name"] ?: comp["comment"] ?: settings["name"] ?: "Composition ${index + 1}"
        val layers = comp.children.filter { it.name == "Layers" }.flatMap { it.children }.filter { it.name == "Layer" }.ifEmpty {
            comp.descendants().filter { it.name == "Layer" || it.name == "LayerSettings" }.toList()
        }
        val children = layers.asReversed().mapIndexed { i, node ->
            val info = node.descendants().firstOrNull { it.name == "LayerSettings" } ?: node
            val label = info["name"] ?: info["sourceName"] ?: node["name"] ?: "Layer ${i + 1}"
            val hidden = (info["enabled"] ?: info["visible"] ?: node["enabled"] ?: node["visible"]) in setOf("false", "0", "no")
            placeholder(width, height, total, label, i, hidden)
        }
        return XmlNode("scene", linkedMapOf(
            "title" to title, "width" to "$width", "height" to "$height", "exportWidth" to "$width",
            "exportHeight" to "$height", "fps" to "${fps.roundToInt().coerceIn(1, 240)}",
            "bgcolor" to "#ff000000", "totalTime" to "$total",
        ), children)
    }

    private fun scene(comp: Item, items: Map<Long, Item>, bytes: ByteArray, depth: Int = 0, stack: Set<Long> = emptySet()): XmlNode {
        val fps = comp.fps.takeIf { it.isFinite() && it in 1.0..240.0 } ?: 30.0
        val seconds = comp.seconds.takeIf { it.isFinite() && it > 0.0 } ?: 5.0
        val total = (seconds * 1000.0).roundToInt().coerceIn(1, 24 * 3_600_000)
        val ids = HashMap<Long, Long>()
        for (layer in comp.layers) ids[layer.id] = Layer.newId()
        val children = comp.layers.asReversed().mapNotNull { layer ->
            node(layer, comp, items, ids, total, depth, stack, bytes)
        }
        return XmlNode("scene", linkedMapOf(
            "title" to comp.name, "width" to "${comp.width}", "height" to "${comp.height}",
            "exportWidth" to "${comp.width}", "exportHeight" to "${comp.height}", "fps" to "${fps.roundToInt().coerceIn(1, 240)}",
            "bgcolor" to formatColor(comp.background), "totalTime" to "$total",
        ), children)
    }

    private fun node(
        layer: AepLayer,
        comp: Item,
        items: Map<Long, Item>,
        ids: Map<Long, Long>,
        total: Int,
        depth: Int,
        stack: Set<Long>,
        bytes: ByteArray,
    ): XmlNode? {
        val source = items[layer.source]
        if (source?.audio == true) return null
        val fallback = when (layer.type) {
            3 -> "Text"
            4 -> "Shape"
            else -> "Layer"
        }
        val label = layer.name.ifBlank { source?.sourceName?.ifBlank { fallback } ?: fallback }
        val rawStart = (layer.inPoint * 1000.0).roundToLong()
        val rawEnd = (layer.outPoint * 1000.0).roundToLong()
        if (layer.hidden || rawEnd <= 0L || rawStart >= total.toLong()) return null
        val startMs = rawStart.coerceIn(0L, (total - 1).toLong()).toInt()
        val endMs = rawEnd.coerceIn((startMs + 1).toLong(), total.toLong()).toInt()
        if (endMs <= startMs) return null
        val duration = (endMs - startMs).coerceAtLeast(1)
        val text = layer.transform.text
        if (text) return textNode(layer, comp, ids, startMs, endMs, duration, label)
        val mediaWidth = source?.mediaWidth?.takeIf { it > 0 } ?: source?.takeIf { it.kind == 4 }?.width ?: comp.width
        val mediaHeight = source?.mediaHeight?.takeIf { it > 0 } ?: source?.takeIf { it.kind == 4 }?.height ?: comp.height
        val centreX = mediaWidth / 2.0
        val centreY = mediaHeight / 2.0
        val anchor = { values: List<Double> ->
            if (text) {
                listOf(values.getOrElse(0) { 0.0 }, values.getOrElse(1) { 0.0 }, values.getOrElse(2) { 0.0 })
            } else {
                listOf(
                    values.getOrElse(0) { 0.5 } * mediaWidth,
                    values.getOrElse(1) { 0.5 } * mediaHeight,
                    values.getOrElse(2) { 0.0 },
                )
            }
        }
        val pivot = { values: List<Double> ->
            anchor(values).let { listOf(it[0] - centreX, it[1] - centreY, it[2]) }
        }
        val transform = XmlNode("transform", children = listOf(
            anim("location", layer.transform.position, duration, startMs, layer.start, layer.stretch) { it },
            anim("pivot", layer.transform.anchor, duration, startMs, layer.start, layer.stretch, pivot),
            anim("scale", layer.transform.scale, duration, startMs, layer.start, layer.stretch) { values ->
                val x = values.getOrElse(0) { 1.0 }
                val y = values.getOrElse(1) { 1.0 }
                val z = values.getOrElse(2) { 1.0 }
                if (abs(z - 1.0) > 1.0e-6) listOf(x, y, z) else listOf(x, y)
            },
            anim("rotation", layer.transform.rotation, duration, startMs, layer.start, layer.stretch) { listOf(it.getOrElse(0) { 0.0 }) },
            anim("opacity", layer.transform.opacity, duration, startMs, layer.start, layer.stretch) { listOf(it.getOrElse(0) { 1.0 }) },
        ))
        if (source?.kind == 4 && depth < MAX_PRECOMP_DEPTH && source.id !in stack) {
            val inner = scene(source, items, bytes, depth + 1, stack + source.id)
            val attrs = linkedMapOf(
                "id" to "${ids[layer.id]}", "label" to label, "startTime" to "$startMs", "endTime" to "$endMs",
            )
            ids[layer.parent]?.let { attrs["parent"] = "$it" }
            return XmlNode("embedScene", attrs, listOf(transform, inner))
        }
        val nullLayer = layer.nullObject || (layer.type != 3 && layer.type != 4 && source == null)
        val media = source?.path?.takeIf { it.isNotBlank() }
        val image = media?.substringAfterLast('.', "")?.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "exr", "tif", "tiff", "psd")
        val attrs = linkedMapOf(
            "id" to "${ids[layer.id]}", "label" to label, "startTime" to "$startMs", "endTime" to "$endMs",
            "fillType" to when {
                nullLayer -> "none"
                source?.solid != null -> "color"
                media != null && image -> "media"
                media != null -> "media"
                else -> "color"
            },
            "mediaFillMode" to "fill", "s" to ".rect",
        )
        if (source?.solid != null) attrs["fillColor"] = formatColor(source.solid)
        else if (media != null) attrs[if (image) "fillImage" else "fillVideo"] = media
        else if (!nullLayer) attrs["fillColor"] = formatColor(0xFF3A3D47.toInt())
        ids[layer.parent]?.let { attrs["parent"] = "$it" }
        val size = XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "${f(mediaWidth.toDouble())},${f(mediaHeight.toDouble())}"))
        return XmlNode("shape", attrs, if (nullLayer) listOf(transform) else listOf(transform, size))
    }

    private fun anim(
        name: String,
        value: Value,
        duration: Int,
        startMs: Int,
        layerStart: Double,
        stretch: Double,
        convert: (List<Double>) -> List<Double> = { it },
    ): XmlNode {
        val keys = value.keys
        if (keys.isEmpty()) {
            val values = convert(value.static ?: return XmlNode(name, mapOf("value" to default(name))))
            return XmlNode(name, mapOf("value" to values.joinToString(",") { f(it) }))
        }
        val nodes = keys.map { key ->
            val t = ((layerStart + key.at * stretch) * 1000.0 - startMs) / duration
            XmlNode("kf", linkedMapOf("t" to f(t), "v" to convert(key.values).joinToString(",") { f(it) }))
        }
        return XmlNode(name, children = nodes)
    }

    private fun textNode(
        layer: AepLayer,
        comp: Item,
        ids: Map<Long, Long>,
        startMs: Int,
        endMs: Int,
        duration: Int,
        label: String,
    ): XmlNode {
        val content = layer.textContent?.takeIf { it.isNotBlank() } ?: label
        val attrs = linkedMapOf(
            "id" to "${ids[layer.id]}", "label" to label, "startTime" to "$startMs", "endTime" to "$endMs",
            "fillType" to "color", "fillColor" to "#ffffffff", "size" to "${max(12, comp.height / 10)}",
        )
        ids[layer.parent]?.let { attrs["parent"] = "$it" }
        val transform = XmlNode("transform", children = listOf(
            anim("location", layer.transform.position, duration, startMs, layer.start, layer.stretch) { it },
            anim("pivot", layer.transform.anchor, duration, startMs, layer.start, layer.stretch) { values ->
                listOf(values.getOrElse(0) { 0.0 }, values.getOrElse(1) { 0.0 }, values.getOrElse(2) { 0.0 })
            },
            anim("scale", layer.transform.scale, duration, startMs, layer.start, layer.stretch) { values ->
                listOf(values.getOrElse(0) { 1.0 }, values.getOrElse(1) { 1.0 })
            },
            anim("rotation", layer.transform.rotation, duration, startMs, layer.start, layer.stretch) { listOf(it.getOrElse(0) { 0.0 }) },
            anim("opacity", layer.transform.opacity, duration, startMs, layer.start, layer.stretch) { listOf(it.getOrElse(0) { 1.0 }) },
        ))
        return XmlNode("text", attrs, listOf(transform, XmlNode("content", text = content)))
    }

    private fun textDocument(bytes: ByteArray, chunk: Chunk): String? {
        val btdk = chunk.descendants().firstOrNull { it.type == "btdk" } ?: return null
        var at = btdk.start + 4
        val end = btdk.end
        while (at + 1 < end) {
            if (byte(bytes, at) == 0xFE && byte(bytes, at + 1) == 0xFF) {
                at += 2
                val out = ByteArrayOutputStream()
                while (at + 1 < end) {
                    val b = byte(bytes, at)
                    if (b == 0x29) break
                    if (b == 0x5C) {
                        at++
                        if (at + 1 < end) {
                            out.write(bytes[at].toInt())
                            out.write(bytes[at + 1].toInt())
                            at += 2
                        }
                        continue
                    }
                    out.write(bytes[at].toInt())
                    out.write(bytes[at + 1].toInt())
                    at += 2
                }
                return String(out.toByteArray(), Charsets.UTF_16BE)
                    .replace('\r', '\n')
                    .replace('\u0003', '\n')
                    .trimEnd('\n')
            }
            at++
        }
        return null
    }

    private fun default(name: String) = when (name) {
        "location", "pivot" -> "0,0,0"
        "scale" -> "1,1"
        "rotation" -> "0"
        "opacity" -> "1"
        else -> "0"
    }

    private fun placeholder(compWidth: Int, compHeight: Int, total: Int, label: String, index: Int, hidden: Boolean): XmlNode {
        val hash = label.hashCode() and 0x7FFFFFFF
        val red = 64 + hash % 128
        val green = 64 + (hash / 131) % 128
        val blue = 64 + (hash / 17_171) % 128
        val color = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        val width = max(24.0, compWidth * 0.62)
        val height = max(18.0, compHeight * 0.11)
        val x = compWidth / 2.0 - width / 2.0
        val y = min(compHeight - height - 12.0, 20.0 + index * max(16.0, compHeight * 0.07))
        val attrs = linkedMapOf(
            "id" to "${Layer.newId()}", "label" to label, "startTime" to "0", "endTime" to "$total",
            "fillType" to "color",
        )
        if (hidden) attrs["hidden"] = "true"
        val path = "M0,0 L${f2(width)},0 L${f2(width)},${f2(height)} L0,${f2(height)} Z"
        return XmlNode("shape", attrs, listOf(
            XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "${f2(x)},${f2(y)},0")))),
            XmlNode("fillColor", mapOf("value" to formatColor(color))),
            XmlNode("path", mapOf("d" to path)),
        ))
    }

    private fun XmlNode.descendants(): Sequence<XmlNode> = sequence {
        yield(this@descendants)
        for (child in children) yieldAll(child.descendants())
    }

    private fun Chunk.descendants(): Sequence<Chunk> = sequence {
        yield(this@descendants)
        for (child in children) yieldAll(child.descendants())
    }

    private fun ascii(bytes: ByteArray, at: Int, size: Int): String? {
        if (at < 0 || size < 0 || at + size > bytes.size) return null
        return String(bytes, at, size, StandardCharsets.US_ASCII)
    }

    private fun cString(bytes: ByteArray, at: Int, end: Int): String {
        var stop = at
        while (stop < end && stop < bytes.size && bytes[stop] != 0.toByte()) stop++
        return String(bytes, at, stop - at, StandardCharsets.UTF_8).trim()
    }

    private fun text(bytes: ByteArray, chunk: Chunk): String =
        String(bytes, chunk.start, chunk.end - chunk.start, StandardCharsets.UTF_8).trim('\u0000', ' ')

    private fun rational(bytes: ByteArray, at: Int): Double {
        val den = u32(bytes, at + 4)
        return if (den > 0) i32(bytes, at).toDouble() / den else 0.0
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        if (at + 2 > bytes.size) 0 else ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, at: Int): Int =
        if (at + 4 > bytes.size) 0 else ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun i32(bytes: ByteArray, at: Int): Int = u32(bytes, at)

    private fun byte(bytes: ByteArray, at: Int): Int = if (at in bytes.indices) bytes[at].toInt() and 0xFF else 0

    private fun f32(bytes: ByteArray, at: Int): Float = Float.fromBits(u32(bytes, at))

    private fun f64(bytes: ByteArray, at: Int): Double {
        if (at < 0 || at + 8 > bytes.size) return Double.NaN
        var bits = 0L
        for (i in 0 until 8) bits = (bits shl 8) or (bytes[at + i].toLong() and 0xFF)
        return Double.fromBits(bits)
    }

    private fun f(value: Double): String = String.format(Locale.US, "%.6f", value)
    private fun f2(value: Double): String = String.format(Locale.US, "%.2f", value)
}
