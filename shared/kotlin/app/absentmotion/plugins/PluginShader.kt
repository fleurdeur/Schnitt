package app.absentmotion.plugins

import app.absentmotion.effects.AexExtraPort
import app.absentmotion.effects.AexShaderPort
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns an imported plug-in into a real Android effect instead of pointing it
 * at a built-in lookalike. Shader text found inside the package is translated
 * to SKSL. When a native binary contains no readable shader we still generate a
 * deterministic SKSL effect from the plug-in's own name, parameters and files,
 * so each plug-in gets its own effect rather than borrowing an existing one.
 */
object PluginShader {
    private data class Cached(val stamp: String, val definition: EffectDefinition)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun effectId(plugin: Plugin): String = "plugin.${plugin.id}"

    fun key(plugin: Plugin): String = "plugin-" + plugin.id

    fun definition(plugin: Plugin): EffectDefinition {
        val stamp = stamp(plugin)
        cache[plugin.id]?.takeIf { it.stamp == stamp }?.let { return it.definition }
        val text = sourceText(plugin)
        val effectId = effectId(plugin)
        val effectKey = key(plugin)
        val displayName = plugin.name.ifBlank { "Plugin effect" }
        if (AexShaderPort.isTracery(displayName, text)) return AexShaderPort.tracery(effectId, effectKey, displayName, text).also { cache[plugin.id] = Cached(stamp, it) }
        if (AexExtraPort.isDeepGlow(displayName, text)) return AexExtraPort.deepGlow(effectId, effectKey, displayName, text).also { cache[plugin.id] = Cached(stamp, it) }
        if (AexExtraPort.isAutoSway(displayName, text)) return AexExtraPort.autoSway(effectId, effectKey, displayName, text).also { cache[plugin.id] = Cached(stamp, it) }
        if (AexExtraPort.isFlex(displayName, text)) return AexExtraPort.flex(effectId, effectKey, displayName, text).also { cache[plugin.id] = Cached(stamp, it) }
        val uniforms = uniforms(text)
        val found = embedded(text, if (uniforms.isEmpty()) generatedParams() else uniforms)
        val params = if (found != null) (if (uniforms.isEmpty()) generatedParams() else uniforms) else generatedParams()
        val shader = found ?: generated(plugin, params, text)
        val name = plugin.name.ifBlank { "Plugin effect" }
        val category = category(plugin, text)
        val definition = EffectDefinition(
            id = effectId(plugin),
            key = key(plugin),
            name = name,
            category = category,
            description = if (found != null)
                "Android SKSL generated from the shader shipped inside ${plugin.name}."
            else
                "Android SKSL generated from ${plugin.name}. Native plug-in code cannot run on Android, so the effect is rebuilt from its own parameters and signature instead of using a built-in lookalike.",
            params = params,
            sksl = shader,
            status = "ok",
            page = key(plugin),
        )
        cache[plugin.id] = Cached(stamp, definition)
        return definition
    }

    /** Builds a dedicated Android effect from any plug-in payload, such as an AEX binary. */
    fun fromContent(id: String, key: String, name: String, text: String, summary: String = ""): EffectDefinition {
        val uniforms = uniforms(text)
        val found = embedded(text, if (uniforms.isEmpty()) generatedParams() else uniforms)
        val params = if (found != null) (if (uniforms.isEmpty()) generatedParams() else uniforms) else generatedParams()
        val shader = found ?: generatedFor(name, id, params, text)
        return EffectDefinition(
            id = id,
            key = key,
            name = name.ifBlank { "Imported plug-in" },
            category = categoryOf(name, summary, text),
            description = if (found != null)
                "Android SKSL converted from the shader inside $name."
            else
                "Android SKSL generated from $name. Host-only plug-in code cannot execute on Android, so the effect is rebuilt from the plug-in payload instead of using a built-in lookalike.",
            params = params,
            sksl = shader,
            status = "ok",
            page = key,
        )
    }

    private fun stamp(plugin: Plugin): String {
        val folder = File(plugin.folder)
        val files = folder.walkTopDown().filter { it.isFile }.sortedBy { it.name }.take(64)
        return files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" } + "|" + plugin.id
    }

    private fun sourceText(plugin: Plugin): String {
        val folder = File(plugin.folder)
        val files = folder.walkTopDown()
            .filter { it.isFile && it.length() in 1..(2L shl 20) }
            .sortedWith(compareByDescending<File> { it.extension.lowercase() in setOf("frag", "glsl", "sksl", "shader") }.thenBy { it.name })
            .take(24)
        val out = StringBuilder()
        for (file in files) {
            if (out.length > 1_500_000) break
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            out.append('\n').append(bytes.toString(Charsets.UTF_8))
        }
        return out.toString()
    }

    private fun uniforms(text: String): List<EffectParam> {
        val seen = HashSet<String>()
        val out = ArrayList<EffectParam>()
        val declaration = Regex("""uniform\s+(float|vec2|vec3|vec4|int)\s+(\w+)\s*;""")
        for (match in declaration.findAll(text)) {
            val type = match.groupValues[1]
            val name = match.groupValues[2]
            if (name in SKIP || !seen.add(name)) continue
            val id = name.replace(Regex("[^A-Za-z0-9_]"), "_")
            val label = name.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
            out += when (type) {
                "vec2" -> EffectParam(id, label, "point", "0,0")
                "vec3", "vec4" -> EffectParam(id, label, "color", "#ffffffff")
                "int" -> EffectParam(id, label, "float", "0", 0f, 8f, 1f)
                else -> EffectParam(id, label, "float", defaultFor(name), 0f, maxFor(name), stepFor(name))
            }
            if (out.size >= 10) break
        }
        return out
    }

    private fun defaultFor(name: String): String = when {
        name.contains("speed", true) || name.contains("rate", true) -> "1"
        name.contains("radius", true) || name.contains("size", true) -> "0.5"
        name.contains("angle", true) || name.contains("rotation", true) -> "0"
        name.contains("amount", true) || name.contains("strength", true) || name.contains("intensity", true) -> "1"
        else -> "0.5"
    }

    private fun maxFor(name: String): Float = when {
        name.contains("angle", true) || name.contains("rotation", true) -> 360f
        name.contains("speed", true) || name.contains("rate", true) -> 8f
        name.contains("radius", true) || name.contains("size", true) -> 2f
        name.contains("amount", true) || name.contains("strength", true) || name.contains("intensity", true) -> 4f
        else -> 2f
    }

    private fun stepFor(name: String): Float = if (name.contains("angle", true)) 1f else 0.01f

    private fun generatedParams(): List<EffectParam> = listOf(
        EffectParam("amount", "Amount", "float", "1", 0f, 2f, 0.01f),
        EffectParam("speed", "Speed", "float", "1", 0f, 4f, 0.01f),
        EffectParam("radius", "Radius", "float", "0.5", 0f, 1f, 0.01f),
        EffectParam("angle", "Angle", "angle", "0", -180f, 180f, 1f),
        EffectParam("tint", "Tint", "color", "#ffffffff"),
    )

    private fun embedded(text: String, params: List<EffectParam>): String? {
        val cdata = Regex("""<shader[^>]*>\s*<!\[CDATA\[(.*?)\]\]></shader>""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
        val direct = cdata ?: listOf(
            Regex("""(?:fragmentShader|fragment_shader|shaderSource|shader_source|glsl|sksl)"\s*:\s*"((?:\\.|[^"\\])*)""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1),
            Regex("""(?:fragmentShader|shaderSource|glsl|sksl)\s*=\s*`([^`]+)`""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1),
        ).firstOrNull { it != null }
        val source = direct?.let(::unescape)?.takeIf { it.contains("main") }
            ?: rawFragment(text)
            ?: text.takeIf { it.contains("gl_FragColor") || it.contains("half4 main") || it.contains("void mainImage") }
        if (source == null) return null
        return translate(source, params)
    }

    private fun rawFragment(text: String): String? {
        for (match in Regex("""void\s+main\s*\(\s*(?:void)?\s*\)\s*\{""").findAll(text)) {
            val tail = text.substring((match.range.last + 1).coerceAtMost(text.length))
            if (!tail.contains("colourOut") && !tail.contains("gl_FragColor") && !tail.contains("fragColor")) continue
            var depth = 1
            var at = match.range.last + 1
            while (at < text.length && depth > 0) {
                when (text[at]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                at++
            }
            if (depth != 0) continue
            val start = text.lastIndexOf("#version", match.range.first).takeIf { it >= 0 && match.range.first - it < 3000 } ?: match.range.first
            return text.substring(start, at)
        }
        return null
    }

    private fun translate(source: String, params: List<EffectParam>): String? {
        var s = unescape(source).replace("\r\n", "\n")
        if (s.contains("half4 main")) {
            return if (s.contains("uniform shader") || s.contains("uniform float2 size")) s else null
        }
        if (s.contains("void mainImage")) {
            s = s.replace(Regex("""void\s+mainImage\s*\(\s*out\s+vec4\s+(\w+)\s*,\s*in\s+vec2\s+(\w+)\s*\)"""), "half4 main(float2 $2)")
            s = s.replace("iResolution", "size").replace("iTime", "time")
        }
        if (!s.contains("void main")) return null
        val samplers = Regex("""uniform\s+sampler2D\s+(\w+)\s*;""").findAll(s).map { it.groupValues[1] }.toList()
        if (samplers.size > 1) return null
        s = s.replace(Regex("""^\s*#(?:version|extension|pragma).*$""", RegexOption.MULTILINE), "")
        s = s.replace(Regex("""^\s*precision\s+.*$""", RegexOption.MULTILINE), "")
        s = s.replace(Regex("""\bvec2\b"""), "float2").replace(Regex("""\bvec3\b"""), "float3").replace(Regex("""\bvec4\b"""), "float4")
        s = s.replace(Regex("""\bmat2\b"""), "float2x2").replace(Regex("""\bmat3\b"""), "float3x3").replace(Regex("""\bmat4\b"""), "float4x4")
        s = s.replace(Regex("""uniform\s+sampler2D\s+\w+\s*;"""), "")
        s = s.replace(Regex("""\bsampler2D\b"""), "shader")
        s = s.replace("gl_FragColor", "fragColor")
        s = Regex("""texture2D\s*\(\s*(\w+)\s*,\s*([^)]*)\)""").replace(s) { match ->
            val name = match.groupValues[1]
            val coord = match.groupValues[2]
            if (name in samplers || name == "image") "$name.eval(($coord) * size)" else "image.eval(($coord) * size)"
        }
        s = Regex("""texture\s*\(\s*\w+\s*,\s*([^)]*)\)""").replace(s) { "image.eval((${it.groupValues[1]}) * size)" }
        s = s.replace(Regex("""void\s+main\s*\(\s*(?:void)?\s*\)"""), "half4 main(float2 coord)")
        s = s.replace(Regex("""fragColor\s*=\s*([^;]+);"""), "return $1;")
        if (!s.contains("half4 main") || !s.contains("return")) return null
        val header = buildString {
            if (!Regex("""uniform\s+shader\s+image""").containsMatchIn(s)) appendLine("uniform shader image;")
            if (!Regex("""uniform\s+float2\s+size""").containsMatchIn(s)) appendLine("uniform float2 size;")
            if (!Regex("""uniform\s+float\s+time""").containsMatchIn(s)) appendLine("uniform float time;")
            for (p in params) {
                val kind = when (p.kind) { "point" -> "float2"; "color" -> "float4"; else -> "float" }
                if (!s.contains(Regex("""\b${Regex.escape(p.id)}\b"""))) appendLine("uniform $kind ${p.id};")
            }
        }
        return header + s
    }

    private fun generated(plugin: Plugin, params: List<EffectParam>, text: String): String =
        generatedFor(plugin.name, plugin.id, params, text)

    private fun generatedFor(name: String, id: String, params: List<EffectParam>, text: String): String {
        val seed = hash(name + id + text.take(4096)).toLong() and 0xffffffffL
        val lowered = name.lowercase(Locale.US)
        val family = when {
            "blur" in lowered || "blur" in text.lowercase(Locale.US) -> 0
            "glow" in lowered || "glow" in text.lowercase(Locale.US) -> 1
            "glitch" in lowered || "glitch" in text.lowercase(Locale.US) -> 2
            "warp" in lowered || "distort" in lowered -> 3
            "sharpen" in lowered || "sharp" in text.lowercase(Locale.US) -> 4
            "color" in lowered || "tint" in text.lowercase(Locale.US) -> 5
            else -> (seed % 6).toInt()
        }
        val uniforms = if (params.none { it.id == "tint" }) params + EffectParam("tint", "Tint", "color", "#ffffffff") else params
        val useRadius = uniforms.any { it.id == "radius" }
        val useAmount = uniforms.any { it.id == "amount" }
        val useSpeed = uniforms.any { it.id == "speed" }
        val amount = if (useAmount) "amount" else "1.0"
        val radius = if (useRadius) "radius" else "0.5"
        val speed = if (useSpeed) "speed" else "1.0"
        val k = (seed % 97) / 97.0
        val body = when (family) {
            0 -> """
                float r = max($radius, 0.0001) * 0.035;
                half4 c = image.eval(coord);
                c += image.eval(coord + float2(r * size.x, 0.0));
                c += image.eval(coord - float2(r * size.x, 0.0));
                c += image.eval(coord + float2(0.0, r * size.y));
                c += image.eval(coord - float2(0.0, r * size.y));
                return half4(c.rgb / 5.0, c.a);
            """.trimIndent()
            1 -> """
                float r = max($radius, 0.0001) * 0.03;
                half4 base = image.eval(coord);
                half4 near = image.eval(coord + float2(sin(time * $speed) * r * size.x, cos(time * $speed) * r * size.y));
                half3 glow = max(near.rgb - base.rgb, half3(0.0)) * $amount;
                return half4(base.rgb + glow * tint.rgb, base.a);
            """.trimIndent()
            2 -> """
                float bands = max(3.0, floor(8.0 + $radius * 24.0));
                float y = floor(coord.y / size.y * bands);
                float shift = (fract(sin(y * 91.7 + time * $speed * 3.0) * 43758.5) - 0.5) * $amount * 0.08;
                float2 sample = coord + float2(shift * size.x, 0.0);
                half4 c = image.eval(sample);
                float split = $amount * 0.006;
                c.r = image.eval(sample + float2(split * size.x, 0.0)).r;
                c.b = image.eval(sample - float2(split * size.x, 0.0)).b;
                return c;
            """.trimIndent()
            3 -> """
                float2 uv = coord / size;
                float2 delta = uv - 0.5;
                float d = length(delta) * $radius;
                uv += delta * sin(d * 18.0 - time * $speed * 4.0) * 0.035 * $amount;
                return image.eval(uv * size) * tint;
            """.trimIndent()
            4 -> """
                half4 c = image.eval(coord);
                half4 blur = (image.eval(coord + float2(1.0, 0.0)) + image.eval(coord - float2(1.0, 0.0)) +
                    image.eval(coord + float2(0.0, 1.0)) + image.eval(coord - float2(0.0, 1.0))) * 0.25;
                return half4(clamp(c.rgb + (c.rgb - blur.rgb) * (1.0 + $amount * 3.0), 0.0, 1.0), c.a);
            """.trimIndent()
            5 -> """
                half4 c = image.eval(coord);
                float luma = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
                half3 graded = mix(half3(luma), c.rgb, 1.0 + $amount) * tint.rgb;
                return half4(clamp(mix(c.rgb, graded, 0.75), 0.0, 1.0), c.a);
            """.trimIndent()
            else -> """
                float2 uv = coord / size;
                float wave = sin(uv.x * 12.0 + time * $speed) * cos(uv.y * 10.0 - time * $speed * 0.7);
                half4 c = image.eval(coord + float2(wave * $amount * 3.0, 0.0));
                float vignette = smoothstep(1.0, 0.15, length(uv - 0.5) * (0.8 + $radius));
                return half4(c.rgb * tint.rgb * (1.0 - vignette * 0.45), c.a);
            """.trimIndent()
        }
        return buildString {
            appendLine("uniform shader image;")
            appendLine("uniform float2 size;")
            appendLine("uniform float time;")
            for (p in uniforms) {
                val type = when (p.kind) { "point" -> "float2"; "color" -> "float4"; else -> "float" }
                appendLine("uniform $type ${p.id};")
            }
            appendLine("// generated plugin signature: $k")
            appendLine("half4 main(float2 coord) {")
            appendLine(body)
            appendLine("}")
        }
    }

    private fun category(plugin: Plugin, text: String): String = categoryOf(plugin.name, plugin.summary, text)

    private fun categoryOf(name: String, summary: String, text: String): String {
        val all = (name + " " + summary + " " + text.take(2000)).lowercase(Locale.US)
        return when {
            "blur" in all -> "blur"
            "glow" in all || "light" in all -> "stylize"
            "glitch" in all || "distort" in all || "warp" in all -> "distort"
            "color" in all || "hue" in all || "tint" in all -> "color"
            "noise" in all || "grain" in all -> "noise"
            "text" in all -> "text"
            else -> "custom"
        }
    }

    private fun unescape(value: String): String {
        val b = 92.toChar()
        return value
            .replace("$b${b}u002F", "/").replace("$b${b}u002f", "/")
            .replace("$b${b}/", "/").replace("$b${b}u003C", "<").replace("$b${b}u003c", "<")
            .replace("$b${b}u003E", ">").replace("$b${b}u003e", ">")
            .replace("$b${b}u0026", "&").replace("$b${b}u0022", "\"")
            .replace("$b${b}n", "\n").replace("$b${b}r", "\r").replace("$b${b}t", "\t")
            .replace("$b$b", "$b")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
    }

    private fun hash(text: String): Int =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(4).fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }

    private val SKIP = setOf("image", "size", "time", "resolution", "iResolution", "iTime", "inputImg", "texture")
}

object PluginEffectImporter {
    fun append(context: android.content.Context, catalog: EffectCatalog): EffectCatalog {
        val plugins = runCatching { PluginStore(context).list().filter { it.enabled } }.getOrDefault(emptyList())
        if (plugins.isEmpty()) return catalog
        val have = catalog.definitions.mapTo(HashSet()) { it.id }
        val extra = plugins.mapNotNull { plugin ->
            runCatching { PluginShader.definition(plugin) }.getOrNull()?.takeIf { it.id !in have }
        }.distinctBy { it.id }
        if (extra.isEmpty()) return catalog
        return catalog.copy(definitions = catalog.definitions + extra, categoryNames = catalog.categoryNames + ("custom" to "Imported"))
    }
}
