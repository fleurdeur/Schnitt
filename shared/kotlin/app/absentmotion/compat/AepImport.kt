package app.absentmotion.compat

import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatColor
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Read-only After Effects project reader for RIFX/Egg! .aep files and the XML
 * .aepx variant.
 *
 * .aep: follows the public chunk layout used by After Effects. The root form
 * is RIFX/Egg!, items live in Fold/Item lists, composition settings in cdta and
 * layer records in Layr/ldta. No AE plug-in or expression is executed.
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
    )

    private class AepLayer(val name: String, val source: Long, val hidden: Boolean)

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
            children += parent.children.filter { it.type == "Sfdr" }.flatMap { it.children.filter { c -> c.type == "Item" } }
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
        val names = items.values.associate { it.id to it.name }
        return comps.map { comp -> scene(comp, names) }
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
        if (kind != 4) return Item(id, name, kind)
        val cdta = chunk.children.firstOrNull { it.id == "cdta" }
        if (cdta == null || cdta.end - cdta.start < 154) return Item(id, name, kind)
        val width = u16(bytes, cdta.start + 136).coerceIn(1, 16384)
        val height = u16(bytes, cdta.start + 138).coerceIn(1, 16384)
        val divisor = u32(bytes, cdta.start + 4).toDouble()
        val dividend = u32(bytes, cdta.start + 8).toDouble()
        val fps = if (divisor > 0) dividend / divisor else 0.0
        val secDivisor = u32(bytes, cdta.start + 44).toDouble()
        val secDividend = u32(bytes, cdta.start + 40).toDouble()
        val seconds = if (secDivisor > 0) secDividend / secDivisor else 0.0
        val red = byte(bytes, cdta.start + 48)
        val green = byte(bytes, cdta.start + 49)
        val blue = byte(bytes, cdta.start + 50)
        val layers = ArrayList<AepLayer>()
        for (list in chunk.children) {
            if (list.type !in setOf("Layr", "SLay", "CLay")) continue
            if (layers.size >= MAX_LAYERS) break
            layers += layer(bytes, list) ?: continue
        }
        return Item(id, name, kind, width, height, fps, seconds, (0xFF shl 24) or (red shl 16) or (green shl 8) or blue, layers)
    }

    private fun layer(bytes: ByteArray, chunk: Chunk): AepLayer? {
        val ldta = chunk.children.firstOrNull { it.id == "ldta" } ?: return null
        if (ldta.end - ldta.start < 44) return null
        val source = u32(bytes, ldta.start + 40).toLong() and 0xFFFFFFFFL
        val video = if (ldta.end - ldta.start > 39) byte(bytes, ldta.start + 39) and 0x01 != 0 else true
        val name = chunk.children.firstOrNull { it.id == "Utf8" }?.let { text(bytes, it) }.orEmpty().ifBlank { "Layer" }
        return AepLayer(name, source, !video)
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

    private fun scene(comp: Item, names: Map<Long, String>): XmlNode {
        val fps = comp.fps.takeIf { it.isFinite() && it in 1.0..240.0 } ?: 30.0
        val seconds = comp.seconds.takeIf { it.isFinite() && it > 0.0 } ?: 5.0
        val total = (seconds * 1000.0).roundToInt().coerceIn(1, 24 * 3_600_000)
        val children = comp.layers.asReversed().mapIndexed { index, layer ->
            val label = if (layer.name == "Layer") names[layer.source] ?: "Layer ${index + 1}" else layer.name
            placeholder(comp.width, comp.height, total, label, index, layer.hidden)
        }
        return XmlNode("scene", linkedMapOf(
            "title" to comp.name, "width" to "${comp.width}", "height" to "${comp.height}",
            "exportWidth" to "${comp.width}", "exportHeight" to "${comp.height}", "fps" to "${fps.roundToInt().coerceIn(1, 240)}",
            "bgcolor" to formatColor(comp.background), "totalTime" to "$total",
        ), children)
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

    private fun text(bytes: ByteArray, chunk: Chunk): String =
        String(bytes, chunk.start, chunk.end - chunk.start, StandardCharsets.UTF_8).trim('\u0000', ' ')

    private fun u16(bytes: ByteArray, at: Int): Int =
        if (at + 2 > bytes.size) 0 else ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, at: Int): Int =
        if (at + 4 > bytes.size) 0 else ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun byte(bytes: ByteArray, at: Int): Int = if (at in bytes.indices) bytes[at].toInt() and 0xFF else 0

    private fun f2(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
}
