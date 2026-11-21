package app.absentmotion.compat

import app.absentmotion.model.finiteFloat
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframes
import app.absentmotion.model.valueAt
import app.absentmotion.platform.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.withSign

class EffectMigration(private val rules: Map<String, Rule>, private val catalog: EffectCatalog) {
    private val definitionsByKey = catalog.definitions.associateBy { it.key }
    private val definitionsById = catalog.definitions.associateBy { it.id }

    /** Alight effects that the native motion/text renderer already understands
     *  without a catalog entry. They must not be rewired through the generic
     *  fallback: that loses parameters and can move a layer. */
    private val nativeAlight = setOf(
        "com.alightcreative.effects.fade", "com.alightcreative.effects.blink", "com.alightcreative.effects.blink2",
        "com.alightcreative.effects.flicker", "com.alightcreative.effects.flicker2",
        "com.alightcreative.effects.pulse-opacity", "com.alightcreative.effects.pulse_opacity2",
        "com.alightcreative.effects.oscillate", "com.alightcreative.effects.oscillate2", "com.alightcreative.effects.oscillate3",
        "com.alightcreative.effects.spin", "com.alightcreative.effects.pulsate", "com.alightcreative.effects.pulsate2",
        "com.alightcreative.effects.swing", "com.alightcreative.effects.swing2",
        "com.alightcreative.effects.jitter", "com.alightcreative.effects.randomdisplace",
        "com.alightcreative.effects.shake", "com.alightcreative.effects.shake2",
        "com.alightcreative.effects.scaleassist",
    )
    class Conversion(
        val to: String?,
        val power: Float = 1f,
        val scale: Float = 1f,
        val offset: Float = 0f,
        val wrap: Float = 0f,
        val min: Float = -Float.MAX_VALUE,
        val max: Float = Float.MAX_VALUE,
        val values: Map<String, Float> = emptyMap(),
        val neutral: String? = null,
        val component: Int = -1,
        val plus: String? = null,
        val relative: Boolean = false,
        val per: String? = null,
        val take: Int = 0,
        val gate: String? = null,
        val ignore: Boolean = false,
        val turn: Boolean = false,
        val blank: String? = null,
        val signs: List<Float> = emptyList(),
        val pre: List<Float> = emptyList(),
        val vector: String? = null,
    )

    class Turn(val quat: String, val spin: String, val to: List<String>)

    class Rule(val to: String, val params: Map<String, List<Conversion?>>, val set: Map<String, String>, val onlyWith: String? = null, val turn: Turn? = null)

    /** One effect or setting group that needs the user's decision after import. */
    data class Gap(val id: String, val label: String, val properties: List<String>) : Serializable

    data class Result(val scene: Scene, val converted: Map<String, Int>, val dropped: Set<String>, val gaps: List<Gap> = emptyList()) {
        val changed: Boolean get() = converted.isNotEmpty()
    }

    fun migrate(scene: Scene): Result {
        // Projects already written by Schnitt, with no Alight effect left,
        // cannot be changed by this pass. Returning before walking every
        // property and keyframe is the main reason large projects open faster.
        if (scene.node["am"] == "app.absentmotion" && !hasAlightEffect(scene.node)) {
            return Result(scene, emptyMap(), emptySet())
        }
        val converted = linkedMapOf<String, Int>()
        val dropped = sortedSetOf<String>()
        val gaps = linkedMapOf<String, MutableSet<String>>()
        fun noteGap(node: XmlNode, properties: Collection<String>) {
            val id = node["id"] ?: return
            gaps.getOrPut(id) { linkedSetOf() }.addAll(properties)
        }
        var renamed = false
        fun visit(node: XmlNode, dims: FloatArray): XmlNode {
            // Text spacing is read directly by the native text layout, not by
            // the effect catalog. Keep it untouched instead of letting the
            // generic fallback rewire its properties into another effect.
            if (node.name == "effect" && node["id"] == ForeignEffectIds.TEXT_SPACING) return node
            val rule = if (node.name == "effect") rules[node["id"]]?.takeIf { r -> r.onlyWith == null || node.children.any { c -> c.name == "property" && c["name"] == r.onlyWith } } else null
            if (node.name == "effect" && rule == null && node["id"] in nativeAlight) return node
            val own = rule?.let { definitionsByKey[it.to] }
            val older = own != null && node["id"] == own.id
            // A curated rule is a promise that the built-in effect is the
            // implementation we want. A bundled Alight shader must not win just
            // because it also parsed: several of them cannot be translated
            // faithfully (multi-pass buffers, layer matrices) and render as a
            // placeholder. The rule keeps the imported property names and
            // keyframes while switching to the known-good native effect.
            if (rule != null && own != null && (!older || node.children.any { c -> c.name == "property" && own.params.none { it.id == c["name"] } })) {
                if (older) renamed = true else converted.merge("${node["id"]!!.substringAfterLast('.')} → ${own.name}", 1, Int::plus)
                return convert(node, rule, own, older, dims) {
                    dropped += "${own.name}: $it"
                    noteGap(node, listOf(it))
                }
            }
            val nativeAm = if (node.name == "effect") definitionsById[node["id"]]?.takeIf {
                it.key.startsWith("am-") || it.external
            } else null
            if (nativeAm != null) return align(node, nativeAm)
            val alightId = node["id"]?.startsWith("com.alightcreative.") == true
            if (node.name == "effect" && alightId && catalog.definitions.none { it.id == node["id"] }) {
                val matched = fallback(node)
                if (matched != null) {
                    converted.merge("${node["id"]!!.substringAfterLast('.')} → ${matched.effect.name}", 1, Int::plus)
                    matched.dropped.forEach { dropped += "${matched.effect.name}: $it" }
                    if (matched.dropped.isNotEmpty()) noteGap(node, matched.dropped)
                    return matched.node
                }
                dropped += "Unsupported effect: ${node["id"]}"
                noteGap(node, node.children.filter { it.name == "property" }.mapNotNull { it["name"] })
            }
            val inner = if (node.name in Scene.LAYER_TAGS) layerDims(node, dims) else dims
            return if (node.children.isEmpty()) node else node.copy(children = node.children.map { visit(it, inner) })
        }
        val w = scene.node["width"]?.finiteFloat()?.takeIf { it > 0f } ?: 1080f
        val h = scene.node["height"]?.finiteFloat()?.takeIf { it > 0f } ?: 1080f
        var root = visit(scene.node, floatArrayOf(w, h, 0f, w, h))
        val foreign = root["am"]?.startsWith("com.alightcreative") == true
        if (foreign) {
            root = alightPivots(root)
            root = normalizeGroups(root, root["width"]?.toIntOrNull() ?: 1080, root["height"]?.toIntOrNull() ?: 1080)
            root = root.copy(attrs = root.attrs + ("am" to "app.absentmotion"), children = root.children.map(::squaredBlendOpacity))
        }
        val gapsOut = gaps.map { (id, props) -> Gap(id, id.substringAfterLast('.').replace('-', ' '), props.toList()) }
        return Result(if (converted.isEmpty() && !renamed && !foreign) scene else Scene(root), converted, dropped, gapsOut)
    }




    private fun hasAlightEffect(node: XmlNode): Boolean =
        node.name == "effect" && node["id"]?.startsWith("com.alightcreative.") == true ||
            node.children.any(::hasAlightEffect)

    /**
     * A group with a scene that lost or never had its canvas size used the
     * native 1080 fallback, so the group was the wrong size and sat in the
     * wrong place against the project. Give it the parent composition size.
     */
    private fun normalizeGroups(node: XmlNode, parentWidth: Int, parentHeight: Int): XmlNode {
        val width = node["width"]?.toIntOrNull()?.takeIf { it > 0 } ?: parentWidth
        val height = node["height"]?.toIntOrNull()?.takeIf { it > 0 } ?: parentHeight
        return node.copy(children = node.children.map { child ->
            when (child.name) {
                "scene" -> normalizeGroups(child, width, height)
                "embedScene" -> {
                    val inner = child.child("scene")
                    if (inner == null) child
                    else {
                        val fixed = inner.with("width", "${inner["width"]?.toIntOrNull()?.takeIf { it > 0 } ?: width}")
                            .with("height", "${inner["height"]?.toIntOrNull()?.takeIf { it > 0 } ?: height}")
                        child.withChild("scene", normalizeGroups(fixed, fixed["width"]!!.toInt(), fixed["height"]!!.toInt()))
                    }
                }
                in Scene.LAYER_TAGS -> normalizeGroups(child, width, height)
                else -> child
            }
        })
    }

    /**
     * Alight Motion keeps a layer's location as its centre and its pivot as an
     * independent anchor offset. Our editor stores the pivot-space location
     * (the point that lands on location after the pivot is subtracted). Adding
     * the pivot to location once, at import, preserves every imported position
     * and keyframe without changing how native and hand-authored scenes work.
     */
    private fun alightPivots(node: XmlNode): XmlNode = when (node.name) {
        "scene" -> node.copy(children = node.children.map(::alightPivots))
        in Scene.LAYER_TAGS -> {
            val changed = alightPivot(node)
            changed.copy(children = changed.children.map { if (it.name == "scene") alightPivots(it) else it })
        }
        else -> node
    }

    private fun alightPivot(layer: XmlNode): XmlNode {
        if (layer.name == "camera") return layer
        val transform = layer.children.firstOrNull { it.name == "transform" } ?: return layer
        val pivot = transform.children.firstOrNull { it.name == "pivot" } ?: return layer
        val pivots = values(pivot).flatMap { it.split(',') }.mapNotNull { it.trim().finiteFloat() }
        if (pivots.isEmpty() || pivots.all { abs(it) < 1e-6f }) return layer
        val location = transform.children.firstOrNull { it.name == "location" } ?: XmlNode("location", mapOf("value" to "0,0,0"))
        val times = (location.keyframes.map { it.t } + pivot.keyframes.map { it.t }).distinct().sorted()
        if (times.isEmpty()) {
            val l = numbers(location["value"], 3)
            val p = numbers(pivot["value"], 2)
            val made = floatArrayOf(l[0] + p[0], l[1] + p[1], l[2])
            return layer.copy(children = layer.children.map {
                if (it !== transform) it else transform.copy(children = transform.children.map { c ->
                    if (c !== location) c else location.with("value", formatFloats(made))
                })
            })
        }
        val locationKeys = location.keyframes.associateBy { it.t }
        val pivotKeys = pivot.keyframes.associateBy { it.t }
        val made = times.map { at ->
            val l = numbers(location.valueAt(at), 3)
            val p = numbers(pivot.valueAt(at), 2)
            val e = locationKeys[at]?.e ?: pivotKeys[at]?.e
            XmlNode("kf", linkedMapOf("t" to String.format(Locale.US, "%.6f", at),
                "v" to formatFloats(floatArrayOf(l[0] + p[0], l[1] + p[1], l[2]))).also { attrs ->
                if (!e.isNullOrBlank()) attrs["e"] = e
            })
        }
        val rebuilt = XmlNode("location", location.attrs - "value", made)
        return layer.copy(children = layer.children.map {
            if (it !== transform) it else transform.copy(children = transform.children.map { c -> if (c !== location) c else rebuilt })
        })
    }

    private fun numbers(text: String?, count: Int): FloatArray {
        val parsed = text?.split(',')?.mapNotNull { it.trim().finiteFloat() }.orEmpty()
        return FloatArray(count) { parsed.getOrElse(it) { 0f } }
    }

    /**
     * An imported XML can reference an effect that this build does not ship.
     * Match its properties against available effects, then fall back to the
     * closest semantic family.  The effect keeps all properties it can map.
     */
    private data class FallbackMatch(val node: XmlNode, val effect: EffectDefinition, val dropped: List<String>)

    /**
     * An imported XML can reference an effect that this build does not ship.
     * Match the whole effect, not just its id: property names, property labels,
     * the effect name and its semantic family all vote.  Every parameter that
     * has a compatible counterpart is kept, including its keyframes, instead
     * of throwing the animation away.
     */
    private fun fallback(node: XmlNode): FallbackMatch? {
        val rawId = node["id"] ?: return null
        val id = rawId.substringAfterLast('.')
        val props = node.children.filter { it.name == "property" && it["name"] != null }.distinctBy { it["name"] }
        if (props.isEmpty()) return null
        val names = props.mapNotNull { it["name"] }.toSet()
        val idTokens = tokens(id + " " + (node["name"] ?: ""))
        val families = familyWords.filter { it in idTokens }.toSet()
        val candidates = catalog.definitions.filter { it.available && it.id != rawId }
        val scored = candidates.map { d ->
            val targetIds = d.params.map { it.id }.toSet()
            val exact = props.count { it["name"] in targetIds }
            val normalized = props.count { p -> targetIds.any { sameParam(it, p["name"]!!) } }
            val targetTokens = tokens(d.id.substringAfterLast('.') + " " + d.name)
            val overlap = (idTokens intersect targetTokens).count { it.length > 2 }
            val union = (idTokens + targetTokens).count { it.length > 2 }.coerceAtLeast(1)
            val jaccard = overlap.toFloat() / union
            val family = families.count { it in targetTokens }
            val sizePenalty = abs(d.params.size - props.size) * 0.15f
            d to (exact * 10f + normalized * 5f + overlap * 4f + jaccard * 8f + family * 3f - sizePenalty)
        }.filter { it.second > 0f }
        val best = scored.maxByOrNull { it.second } ?: return null
        // A family word alone is not enough: rewiring a layer effect to an
        // unrelated control set is what makes imported layers jump or turn
        // into a different look. Require at least one matching parameter or a
        // strong name match before using a fallback.
        if (best.second < 7f) return null
        val chosen = best.first
        val dropped = ArrayList<String>()
        val used = HashSet<String>()
        val unmapped = ArrayList<XmlNode>()
        val mapped = props.mapNotNull { prop ->
            val name = prop["name"]!!
            val label = prop["label"]
            val target = chosen.params.firstOrNull { it.id == name }
                ?: chosen.params.firstOrNull { !used.contains(it.id) && sameParam(it.id, name) }
                ?: label?.let { l -> chosen.params.firstOrNull { !used.contains(it.id) && sameParam(it.label, l) } }
                ?: byShape(prop, chosen, used, props)
            if (target == null) {
                // Keep the source property verbatim even when this app has no
                // control for it. Dropping it also dropped every keyframe on
                // that row, which is what made imported animations look static.
                if (prop.children.any { it.name == "kf" }) unmapped += prop
                dropped += name
                null
            } else {
                used += target.id
                property(prop, target.id, target.type) { value -> convertFor(target, prop, value) }
            }
        }
        if (mapped.isEmpty()) return null
        val extras = node.children.filter { it.name != "property" }
        return FallbackMatch(node.copy(attrs = node.attrs + ("id" to chosen.id), children = mapped + unmapped + extras), chosen, dropped)
    }

    private fun byShape(prop: XmlNode, effect: EffectDefinition, used: Set<String>, all: List<XmlNode>): EffectParam? {
        val sourceType = prop["type"] ?: return null
        val same = all.count { (it["type"] ?: "float") == sourceType }
        if (same != 1) return null
        return effect.params.filter { !used.contains(it.id) && it.type == sourceType }.singleOrNull()
    }

    private fun convertFor(target: EffectParam, source: XmlNode, raw: String): String {
        val text = raw.trim()
        val first = text.split(',').firstOrNull()?.trim()?.finiteFloat()
        return when (target.kind) {
            "float", "angle" -> when (text) { "true" -> "1"; "false" -> "0"; else -> first?.let { formatFloats(floatArrayOf(it.coerceIn(target.min, target.max))) } ?: target.default }
            "point" -> {
                val numbers = text.split(',').mapNotNull { it.trim().finiteFloat() }
                if (numbers.size >= 2) formatFloats(floatArrayOf(numbers[0], numbers[1])) else target.default
            }
            "color" -> if (text.startsWith("#")) text else target.default
            "switch" -> when (text) { "true", "1" -> "true"; "false", "0" -> "false"; else -> if ((first ?: 0f) != 0f) "true" else "false" }
            "choice" -> first?.let { n -> formatFloats(floatArrayOf(n.roundToInt().coerceIn(target.min.toInt(), target.max.toInt()).toFloat())) } ?: target.default
            else -> text.ifBlank { target.default }
        }
    }

    private fun sameParam(a: String, b: String): Boolean = normalizeParam(a) == normalizeParam(b)

    private fun normalizeParam(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
        .replace(" ", "")

    private fun tokens(value: String): Set<String> = value.lowercase(Locale.US)
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length > 2 && it !in stopWords }
        .toSet()

    private val stopWords = setOf("com", "alightcreative", "alight", "motion", "effect", "effects", "the", "and", "for", "with", "from", "this", "that", "into", "default", "image", "texture", "input", "output", "buffer", "comp", "layer")
    private val familyWords = listOf("blur", "glow", "light", "shadow", "streak", "ray", "star", "shake", "jitter", "swing", "spin", "pulsate", "oscillate", "wave", "ripple", "displace", "distort", "warp", "glass", "mosaic", "pixel", "grid", "tile", "hex", "repeat", "color", "hue", "tint", "tone", "contrast", "brightness", "exposure", "saturation", "vibrance", "sharpen", "clarity", "glitch", "rgb", "chromatic", "outline", "border", "stroke", "matte", "key", "mask", "noise", "grain", "cloud", "smoke", "fire", "particle", "curl", "vortex", "zoom", "mirror", "flip", "wipe", "vignette", "gradient", "map", "text", "3d", "cube", "sphere", "cylinder", "torus", "prism", "pyramid", "ring")

    private fun layerDims(layer: XmlNode, dims: FloatArray): FloatArray {
        val size = sizeOf(layer)
        val scale = transformOf(layer, "scale")?.split(',')?.mapNotNull { it.trim().finiteFloat() }.orEmpty()
        val sx = abs(scale.getOrElse(0) { 1f }); val sy = abs(scale.getOrElse(1) { scale.getOrElse(0) { 1f } })
        val w = size?.get(0) ?: dims[0]; val h = size?.get(1) ?: dims[1]
        return floatArrayOf(w, h, dims.getOrElse(2) { 0f } + rotationOf(layer), (size?.get(0) ?: dims.getOrElse(3) { w }) * sx, (size?.get(1) ?: dims.getOrElse(4) { h }) * sy)
    }

    private fun transformOf(layer: XmlNode, name: String): String? =
        layer.children.firstOrNull { it.name == "transform" }?.children?.firstOrNull { it.name == name }?.valueAt(0f)

    private fun squaredBlendOpacity(layer: XmlNode): XmlNode {
        if (layer.name !in Scene.LAYER_TAGS || layer["blending"] !in setOf("multiply", "subtract")) return layer
        if (layer.children.any { it.name == "effect" && it["id"] in setOf(LIFT, "absent.backdrop") }) return layer
        val square = { v: String -> v.trim().finiteFloat()?.let { formatFloats(floatArrayOf(it * it)) } ?: v }
        return layer.copy(children = layer.children.map { t ->
            if (t.name != "transform") t else t.copy(children = t.children.map { o ->
                if (o.name != "opacity") o else o.copy(attrs = o.attrs.mapValues { (k, v) -> if (k == "value") square(v) else v },
                    children = o.children.map { kf -> kf["v"]?.let { kf.with("v", square(it)) } ?: kf })
            })
        })
    }

    private fun rotationOf(layer: XmlNode): Float = layer.children.firstOrNull { it.name == "transform" }?.children
        ?.firstOrNull { it.name == "rotation" }?.valueAt(0f)?.split(',')?.firstOrNull()?.trim()?.finiteFloat() ?: 0f

    private fun sizeOf(layer: XmlNode): FloatArray? = layer.children.firstOrNull { it.name == "property" && it["name"] == "size" }?.valueAt(0f)
        ?.split(',')?.mapNotNull { it.trim().finiteFloat() }?.takeIf { it.size >= 2 && it[0] > 0f && it[1] > 0f }?.let { floatArrayOf(2 * it[0], 2 * it[1]) }

    private fun convert(effect: XmlNode, rule: Rule, own: EffectDefinition, keep: Boolean, dims: FloatArray, drop: (String) -> Unit): XmlNode {
        val made = linkedMapOf<String, XmlNode>()
        val others = ArrayList<XmlNode>()
        val gates = rule.params.values.flatten().mapNotNull { it?.gate }.toSet()
        val turn = rule.turn?.takeIf { t -> t.to.all { id -> own.params.any { it.id == id } } }
        for (child in effect.children) {
            val name = child["name"]
            if (child.name != "property" || name == null) { others += child; continue }
            if (name in gates || (turn != null && (name == turn.quat || name == turn.spin))) continue
            val conversions = rule.params[name]
            if (conversions == null) {
                // Keep same-named controls even when the curated rule did not
                // list them, and keep the original property verbatim when the
                // target has no counterpart. Never erase its keyframes.
                val animated = child.children.any { it.name == "kf" }
                val auto = (if (keep) own.params.firstOrNull { it.id == name } else null)
                    ?: if (animated) own.params.firstOrNull { sameParam(it.id, name) || sameParam(it.label, name) } else null
                if (auto != null) {
                    made.putIfAbsent(auto.id, property(child, auto.id, auto.type) { text -> convertFor(auto, child, text) })
                } else {
                    // Keep animated source rows even when this build has no
                    // matching control yet. Their keyframes survive a re-export
                    // and a later importer can still read them.
                    if (child.children.any { it.name == "kf" }) others += child
                    drop(name)
                }
                continue
            }
            val split = conversions.size > 1
            conversions.forEachIndexed { i, c ->
                val param = c?.to?.let { to -> own.params.firstOrNull { it.id == to } }
                if (c?.ignore == true) return@forEachIndexed
                if (param != null) {
                    val shift = c.plus?.let { other -> effect.children.firstOrNull { it.name == "property" && it["name"] == other }?.valueAt(0f)?.finiteFloat() } ?: 0f
                    val off = c.gate?.let { gate -> effect.children.firstOrNull { it.name == "property" && it["name"] == gate }?.valueAt(0f)?.trim() != "true" } ?: false
                    val reference = c.vector?.let { name -> effect.children.firstOrNull { it.name == "property" && it["name"] == name } }
                    made[param.id] = if (off) XmlNode("property", mapOf("name" to param.id, "type" to param.type, "value" to "#00000000"))
                        else property(child, param.id, param.type) { text, at ->
                            convertValue(text, c, if (c.component >= 0) c.component else if (split && c.take == 0) i else -1, shift, dims,
                                reference?.valueAt(at)?.finiteFloat())
                        }
                } else if (c?.neutral == null || values(child).any { !same(if (split) it.split(',').getOrElse(i) { "" } else it, c.neutral) }) {
                    drop(if (split) "$name (${i + 1})" else name)
                }
            }
        }
        if (turn != null) {
            val quat = effect.children.firstOrNull { it.name == "property" && it["name"] == turn.quat }
            val spin = effect.children.firstOrNull { it.name == "property" && it["name"] == turn.spin }
            if (quat != null || spin != null) {
                val base = turn.to.map { id -> rule.set[id]?.toDoubleOrNull() ?: 0.0 }.toDoubleArray()
                Orientation(quat, spin, base).properties(turn.to).forEach { made[it["name"]!!] = it }
            }
        }
        val fixed = rule.set.filterKeys { it !in made }.mapNotNull { (id, value) ->
            own.params.firstOrNull { it.id == id }?.let { XmlNode("property", mapOf("name" to id, "type" to it.type, "value" to value)) }
        }
        val ordered = own.params.mapNotNull { p ->
            made[p.id] ?: fixed.firstOrNull { it["name"] == p.id }
                ?: if (!keep && p.missingDefault != p.default) XmlNode("property", mapOf("name" to p.id, "type" to p.type, "value" to p.default)) else null
        }
        return effect.copy(attrs = effect.attrs + ("id" to own.id), children = ordered + others)
    }

    /** Keeps every imported property and its keyframes while matching the
     *  original Alight parameter names to the controls of the imported effect
     *  definition. Unmatched properties are kept verbatim instead of becoming
     *  defaults. */
    private fun align(effect: XmlNode, definition: EffectDefinition): XmlNode {
        if (effect.children.none { it.name == "property" }) return effect
        val used = HashSet<String>()
        val children = effect.children.map { child ->
            if (child.name != "property") return@map child
            val name = child["name"] ?: return@map child
            val exact = definition.params.firstOrNull { it.id == name }
            val target = exact ?: definition.params.firstOrNull { !used.contains(it.id) && sameParam(it.id, name) }
            if (target == null) child
            else {
                used += target.id
                if (target.id == name) child.copy(attrs = child.attrs + ("type" to target.type))
                else child.copy(attrs = child.attrs + ("name" to target.id) + ("type" to target.type))
            }
        }
        val attrs = if (definition.applyToChildren) effect.attrs + ("applyToChildren" to "true") else effect.attrs
        return effect.copy(attrs = attrs, children = children)
    }

    private fun property(source: XmlNode, name: String, type: String, change: (String, Float) -> String): XmlNode {
        val attrs = linkedMapOf("name" to name, "type" to type)
        source.attrs.forEach { (k, v) -> if (k != "name" && k != "type") attrs[k] = if (k == "value") change(v, 0f) else v }
        return source.copy(attrs = attrs, children = source.children.map { kf ->
            if (kf.name == "kf" && kf["v"] != null) kf.with("v", change(kf["v"]!!, kf["t"]?.finiteFloat() ?: 0f)) else kf
        })
    }

    private fun property(source: XmlNode, name: String, type: String, change: (String) -> String): XmlNode =
        property(source, name, type) { value, _ -> change(value) }

    private fun values(node: XmlNode): List<String> = listOfNotNull(node["value"]) + node.children.mapNotNull { it["v"] }

    private fun same(a: String, b: String): Boolean {
        val x = a.split(',').map { it.trim().finiteFloat() }
        val y = b.split(',').map { it.trim().finiteFloat() }
        return if (x.all { it != null } && y.all { it != null }) x == y else a.trim() == b.trim()
    }

    private fun convertValue(text: String, c: Conversion, component: Int, shift: Float, dims: FloatArray, reference: Float? = null): String {
        val t = text.trim().let { s -> if (c.blank != null && s.split(',').all { it.trim().finiteFloat() == 0f }) c.blank else s }
        if (t == "true" || t == "false") return c.values[t]?.let { formatFloats(floatArrayOf(it)) } ?: t
        if (t.startsWith("#")) return t
        val parts = t.split(',')
        val picked = if (component >= 0) listOf(parts.getOrElse(component) { "0" }) else if (c.take > 0) parts.take(c.take) else parts
        val numbers = picked.map { p ->
            val n = p.trim().finiteFloat() ?: return t
            c.values[n.toInt().toString()]?.takeIf { n % 1f == 0f } ?: n
        }
        if (c.vector != null && numbers.size == 1) {
            // Old Alight turbulent noise stored stretch as one signed number:
            // a positive value widened Y, a negative value widened X. The
            // current effect exposes two axes, so expand it using the scale
            // value from the same effect at this keyframe.
            val stretch = numbers[0]
            val scale = reference?.takeIf { abs(it) > 1e-4f } ?: 1f
            val pair = if (stretch >= 0f) floatArrayOf(1f, 1f + stretch / scale)
            else floatArrayOf(1f - stretch / scale, 1f)
            return formatFloats(FloatArray(2) { pair[it].coerceIn(c.min, c.max) })
        }
        val size = when (c.per) { "width" -> dims[0]; "height" -> dims[1]; "spanWidth" -> dims.getOrElse(3) { dims[0] }; "spanHeight" -> dims.getOrElse(4) { dims[1] }; else -> 1f }
        return formatFloats(FloatArray(numbers.size) { i ->
            val shown = if (c.relative) numbers[i] / dims[(if (component >= 0) component else i) % 2] + 0.5f else numbers[i]
            val base = shown * c.signs.getOrElse(i) { 1f } + c.pre.getOrElse(i) { 0f }
            val raised = if (c.power == 1f) base else abs(base).pow(c.power).withSign(base)
            val moved = raised * c.scale * size + c.offset + shift + if (c.turn) dims.getOrElse(2) { 0f } else 0f
            (if (c.wrap > 0f) moved.mod(c.wrap) else moved).coerceIn(c.min, c.max)
        })
    }

    companion object {
        const val ASSET = "effects/foreign.json"
        private const val LIFT = "com.alightcreative.effects.lift"

        fun read(context: Context, catalog: EffectCatalog = EffectCatalog.read(context)): EffectMigration =
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }, catalog)

        fun parse(json: String, catalog: EffectCatalog): EffectMigration {
            val root = JSONObject(json)
            val rules = root.keys().asSequence().associateWith { id ->
                val rule = root.getJSONObject(id)
                val params = rule.optJSONObject("params") ?: JSONObject()
                val set = rule.optJSONObject("set") ?: JSONObject()
                Rule(
                    rule.getString("to"),
                    params.keys().asSequence().associateWith { name ->
                        when (val p = params.get(name)) {
                            is JSONArray -> List(p.length()) { i -> p.optJSONObject(i)?.let(::conversion) }
                            else -> listOf(conversion(p as JSONObject))
                        }
                    },
                    set.keys().asSequence().associateWith { set.get(it).let { v -> if (v is Number) formatFloats(floatArrayOf(v.toFloat())) else v.toString() } },
                    rule.optString("onlyWith").takeIf { it.isNotEmpty() },
                    rule.optJSONObject("orientation")?.let { o -> Turn(o.getString("quat"), o.getString("euler"), o.getJSONArray("to").let { a -> List(a.length()) { a.getString(it) } }) },
                )
            }
            return EffectMigration(rules, catalog)
        }

        private fun conversion(json: JSONObject) = Conversion(
            json.optString("to").takeIf { it.isNotEmpty() },
            json.optDouble("power", 1.0).toFloat(),
            json.optDouble("scale", 1.0).toFloat(),
            json.optDouble("offset", 0.0).toFloat(),
            json.optDouble("wrap", 0.0).toFloat(),
            json.optDouble("min", -Float.MAX_VALUE.toDouble()).toFloat(),
            json.optDouble("max", Float.MAX_VALUE.toDouble()).toFloat(),
            json.optJSONObject("values")?.let { v -> v.keys().asSequence().associateWith { v.getDouble(it).toFloat() } }.orEmpty(),
            json.opt("neutral")?.toString(),
            json.optInt("component", -1),
            json.optString("plus").takeIf { it.isNotEmpty() },
            json.optBoolean("relative"),
            json.optString("per").takeIf { it.isNotEmpty() },
            json.optInt("take", 0),
            json.optString("gate").takeIf { it.isNotEmpty() },
            json.optBoolean("ignore"),
            json.optBoolean("turn"),
            json.optString("blank").takeIf { it.isNotEmpty() },
            json.optJSONArray("signs")?.let { a -> List(a.length()) { a.getDouble(it).toFloat() } }.orEmpty(),
            json.optJSONArray("pre")?.let { a -> List(a.length()) { a.getDouble(it).toFloat() } }.orEmpty(),
            json.optString("vector").takeIf { it.isNotEmpty() },
        )
    }
}

fun Scene.oldAlightImport(): Boolean {
    if (!fromAlight || node["reimportTip"] != null) return false
    fun lacks(effect: XmlNode, name: String) = effect.children.none { it.name == "property" && it["name"] == name }
    fun stale(n: XmlNode): Boolean = n.name == "effect" && (n["id"] == "absent.backdrop" && lacks(n, "spill") || n["id"] == "absent.exposure" && lacks(n, "soft")) ||
        n.children.any(::stale)
    return stale(node)
}

fun Scene.withReimportTipShown(): Scene = Scene(node.with("reimportTip", "shown"))
