package app.absentmotion.plugins

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.effects.EffectPreset
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * After Effects .ffx presets are RIFX files.  Android cannot run the Adobe
 * host, but the file still contains the effect names, parameter names and the
 * saved scalar values.  We read those chunks without executing anything and
 * attach the result to the matching built-in Schnitt effect as a normal preset,
 * so every imported value stays visible and editable in the effect panel.
 */
object FfxPreset {
    private const val MAX_BYTES = 16 shl 20

    data class Scalar(val name: String, val value: Double)
    data class Parsed(val effects: List<String>, val scalars: List<Scalar>) {
        val interesting: Boolean get() = effects.isNotEmpty() && scalars.any { it.value != 0.0 }
    }

    fun isFfx(file: File): Boolean = file.extension.equals("ffx", true)

    fun file(plugin: Plugin): File? {
        val direct = File(plugin.folder, plugin.entry.orEmpty())
        if (direct.isFile && isFfx(direct)) return direct
        return File(plugin.folder).walkTopDown().firstOrNull { it.isFile && isFfx(it) }
    }

    fun parse(file: File): Parsed? = runCatching {
        if (file.length() !in 12..MAX_BYTES.toLong()) return@runCatching null
        parse(file.readBytes())
    }.getOrNull()

    fun parse(bytes: ByteArray): Parsed? {
        if (bytes.size < 12 || ascii(bytes, 0, 4) != "RIFX") return null
        val effects = LinkedHashSet<String>()
        val names = LinkedHashSet<String>()
        val scalars = ArrayList<Scalar>()
        fun walk(start: Int, end: Int, depth: Int) {
            if (depth > 24) return
            var at = start
            while (at + 8 <= end) {
                val id = ascii(bytes, at, 4)
                val size = ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                val body = at + 8
                val stop = body + size
                if (size < 0 || stop > end || stop < body) return
                when (id) {
                    "LIST", "RIFX" -> if (size >= 4) walk(body + 4, stop, depth + 1)
                    "tdmn" -> {
                        val name = cString(bytes, body, stop)
                        if (name.startsWith("ADBE ") && EFFECT_SKIP.none { name.contains(it) }) {
                            effects += name.removePrefix("ADBE ").trim()
                        }
                    }
                    "tdsn" -> {
                        val text = utf8Chunk(bytes, body, stop)
                        if (text.isNotBlank()) names += text.trim()
                    }
                    "pard" -> {
                        val name = cString(bytes, body + 16, minOf(stop, body + 48))
                        val value = if (body + 64 <= stop) ByteBuffer.wrap(bytes, body + 56, 8).order(ByteOrder.BIG_ENDIAN).double else Double.NaN
                        val plausible = value == 0.0 || (value.isFinite() && kotlin.math.abs(value) in 1e-20..1e9)
                        if (name.isNotBlank() && !name.startsWith("(") && plausible) {
                            scalars += Scalar(name, value)
                        }
                    }
                }
                at = stop
            }
        }
        walk(12, bytes.size, 0)
        val allEffects = (effects + names.filter { it.length in 3..64 && it.any(Char::isLetter) }).distinct()
        return Parsed(allEffects, scalars.distinctBy { it.name.lowercase(Locale.US) }).takeIf { it.interesting }
    }

    fun append(context: android.content.Context, catalog: EffectCatalog): EffectCatalog {
        val plugins = runCatching { PluginStore(context).list().filter { it.enabled } }.getOrDefault(emptyList())
        if (plugins.isEmpty()) return catalog
        var out = catalog
        for (plugin in plugins) {
            val file = file(plugin) ?: continue
            val parsed = parse(file) ?: continue
            val target = target(out, parsed.effects) ?: continue
            val values = mapValues(target, parsed.scalars)
            if (values.isEmpty()) continue
            val preset = EffectPreset(name = plugin.name.ifBlank { file.nameWithoutExtension }, values = values, keys = emptyMap())
            if (out.definitions.any { it.id == target.id && it.presets.any { p -> p.name == preset.name } }) continue
            out = out.copy(definitions = out.definitions.map { definition ->
                if (definition.id == target.id) definition.copy(presets = definition.presets + preset) else definition
            })
        }
        return out
    }

    fun mappedFor(plugin: Plugin, definition: EffectDefinition): Map<String, String> {
        val parsed = file(plugin)?.let(::parse) ?: return emptyMap()
        return mapValues(definition, parsed.scalars)
    }

    private fun target(catalog: EffectCatalog, names: List<String>): EffectDefinition? {
        val wanted = names.map(::normalize).filter { it.length >= 3 }
        for (definition in catalog.definitions) {
            val keys = listOf(definition.name, definition.id, definition.key).map(::normalize)
            if (keys.any { key -> wanted.any { it == key } }) return definition
        }
        for (definition in catalog.definitions) {
            val key = normalize(definition.name)
            if (key.length >= 6 && wanted.any { it.length <= key.length + 12 && it.contains(key) }) return definition
        }
        val hinted = PluginStore.mapEffects(names.joinToString(" ")).firstOrNull()
        return hinted?.let { id -> catalog.definitions.firstOrNull { it.id == id || it.key == id } }
    }

    private fun mapValues(definition: EffectDefinition, scalars: List<Scalar>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (param in definition.params) {
            if (param.kind !in setOf("float", "angle")) continue
            val id = normalize(param.id)
            val label = normalize(param.label)
            val scalar = scalars.firstOrNull { s ->
                val n = normalize(s.name)
                n == id || n == label || (n.length >= 4 && (n.contains(label) || label.contains(n)) && label.length >= 4)
            } ?: continue
            if (!scalar.value.isFinite()) continue
            var value = scalar.value
            // AE often stores percentage-like amounts as 0..100 while the
            // matching Schnitt control is normalised to 0..1.
            if (param.max <= 2f && value > 2.0 && value <= 100.0 && kotlin.math.abs(value - kotlin.math.round(value)) < 0.0001) value /= 100.0
            if (value < param.min.toDouble() && param.min.isFinite()) value = param.min.toDouble()
            if (value > param.max.toDouble() && param.max.isFinite()) value = param.max.toDouble()
            out[param.id] = param.format(value)
        }
        return out
    }

    private fun normalize(text: String): String = text.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "").trim()

    private val EFFECT_SKIP = listOf("Effect Parade", "Effect Built In Params", "Effect Mask Parade", "Group End", "Force CPU GPU", "End of path")

    private fun ascii(bytes: ByteArray, at: Int, length: Int): String =
        if (at < 0 || at + length > bytes.size) "" else String(bytes, at, length, Charsets.ISO_8859_1)

    private fun cString(bytes: ByteArray, at: Int, end: Int): String {
        var stop = at
        while (stop < end && bytes[stop] != 0.toByte()) stop++
        return String(bytes, at, stop - at, Charsets.ISO_8859_1).trim()
    }

    private fun utf8Chunk(bytes: ByteArray, at: Int, end: Int): String {
        if (at + 8 > end || ascii(bytes, at, 4) != "Utf8") return ""
        val n = ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.BIG_ENDIAN).int.coerceAtLeast(0)
        val stop = minOf(end, at + 8 + n.coerceAtMost(1024))
        return String(bytes, at + 8, stop - at - 8, Charsets.UTF_8)
    }
}
