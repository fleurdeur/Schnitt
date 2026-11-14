package app.absentmotion.effects

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads an Alight Motion <effect> definition XML and compiles its fragment
 * shader into SKSL. Effects that need layer matrices, multi-pass buffers or
 * pass indices are rejected rather than replaced by an unrelated shader.
 */
object AmEffectImporter {
    private const val FOLDER = "am-effects"

    suspend fun import(context: Context, uri: Uri): EffectDefinition? = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@withContext null
        val def = parse(text) ?: return@withContext null
        val folder = File(context.filesDir, FOLDER).apply { mkdirs() }
        File(folder, digest(def.id).take(16) + ".xml").writeText(text)
        def
    }

    @Volatile private var bundled: List<EffectDefinition>? = null

    fun append(context: Context, catalog: EffectCatalog): EffectCatalog {
        val folder = File(context.filesDir, FOLDER)
        val custom = if (folder.isDirectory) folder.listFiles { f -> f.extension == "xml" }.orEmpty().mapNotNull { f ->
            runCatching { parse(f.readText()) }.getOrNull()
        } else emptyList()
        val builtIn = bundled ?: runCatching {
            context.assets.list("am-effects").orEmpty().filter { it.endsWith(".xml") }.mapNotNull { name ->
                runCatching { context.assets.open("am-effects/$name").bufferedReader().use { parse(it.readText()) } }.getOrNull()
            }
        }.getOrDefault(emptyList()).also { bundled = it }
        val existing = catalog.definitions.map { it.key to it.id }.toSet()
        val extra = (builtIn + custom).filter { def ->
            existing.none { it.first == def.key || it.second == def.id } &&
                catalog.definitions.none { it.key == def.key || it.id == def.id || it.name.equals(def.name, true) }
        }.distinctBy { it.key }
        return if (extra.isEmpty()) catalog else catalog.copy(definitions = catalog.definitions + extra)
    }

    private fun parse(xml: String): EffectDefinition? {
        val id = Regex("""<effect\s+[^>]*id="([^"]+)""" + "\"").find(xml)?.groupValues?.get(1) ?: return null
        val params = parseParams(xml)
        val unsupported = xml.contains("<passes>") || xml.contains("srcType=\"buffer\"") || xml.contains("acPass") ||
            xml.contains("acLayerToScreen") || xml.contains("acLayerNorm")
        val shader = Regex("""<shader[^>]*>\s*<!\[CDATA\[(.*?)\]\]></shader>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
        val passes = parsePasses(xml)
        val name = Regex("""<locale lang="en">[\s\S]*?<string name="name">([^<]+)</string>""").find(xml)?.groupValues?.get(1)
            ?: id.substringAfterLast('.').replace('-', ' ').replaceFirstChar { it.uppercase() }
        val description = Regex("""<locale lang="en">[\s\S]*?<string name="desc">([^<]+)</string>""").find(xml)?.groupValues?.get(1)
            ?: "Alight Motion effect"
        val category = Regex("""<effect\s+[^>]*category="([^"]+)""" + "\"").find(xml)?.groupValues?.get(1) ?: "other"
        val chained = shader?.let { if (passes.size > 1) translatePasses(it, params, passes) else null }
        val sksl = chained ?: shader?.let { translate(it, params) } ?: fallback(name, category, params)
        val key = "am-" + id.substringAfterLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "") + "-" + digest(id).take(4)
        val note = when {
            chained != null -> "$description · ${passes.size}-stage pass chain compiled"
            shader == null || unsupported -> "$description · Multipass converted for Android"
            else -> description
        }
        return EffectDefinition(id, key, name, mapCategory(category), note, params, sksl, status = "ok", alsoIn = listOf("external"), page = key)
    }

    private fun parseParams(xml: String): List<EffectParam> {
        val out = ArrayList<EffectParam>()
        val tag = Regex("""<(spinner|slider|switch|point|color|selector|int)\s+([^>]*?)/?>""", RegexOption.DOT_MATCHES_ALL)
        for (m in tag.findAll(xml)) {
            val kind = m.groupValues[1]
            val attrs = m.groupValues[2]
            fun attr(n: String): String? = Regex("""\b$n="([^"]*)""" + "\"").find(attrs)?.groupValues?.get(1)
            val id = attr("id") ?: continue
            val def = attr("default").orEmpty()
            when (kind) {
                "spinner", "slider", "int" -> {
                    val type = attr("type")
                    out += EffectParam(id, attr("label")?.substringAfterLast('/') ?: id.replace('_', ' '),
                        if (type == "angle") "angle" else "float", format(def.toFloatOrNull() ?: 0f),
                        attr("min")?.toFloatOrNull() ?: -Float.MAX_VALUE, attr("max")?.toFloatOrNull() ?: Float.MAX_VALUE,
                        attr("step")?.toFloatOrNull() ?: 0.01f, percent = type == "percent")
                }
                "switch" -> out += EffectParam(id, id.replace('_', ' '), "switch", if (def == "true") "true" else "false")
                "point" -> {
                    val p = def.split(',').mapNotNull { it.trim().toFloatOrNull() }
                    out += EffectParam(id, id.replace('_', ' '), "point", format(p.getOrElse(0) { 0f }) + "," + format(p.getOrElse(1) { 0f }))
                }
                "color" -> out += EffectParam(id, id.replace('_', ' '), "color", def.takeIf { it.startsWith("#") } ?: "#ffffffff")
                "selector" -> {
                    val block = xml.substring(m.range.last + 1).substringBefore("</selector>")
                    val options = Regex("""<choice[^>]*label="([^"]*)""" + "\"").findAll(block).map { it.groupValues[1].substringAfterLast('/').replace('_', ' ') }.toList()
                    out += EffectParam(id, id.replace('_', ' '), "choice", format(def.toFloatOrNull() ?: 0f), 0f,
                        (options.size - 1).coerceAtLeast(1).toFloat(), 1f, options = options.ifEmpty { listOf("Off", "On") })
                }
            }
        }
        return out
    }

    private fun fallback(name: String, category: String, params: List<EffectParam>): String {
        val ids = params.map { it.id }.filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }.toSet()
        fun has(id: String) = id in ids
        fun ref(id: String, fallback: String) = if (has(id)) id else fallback
        val strength = ref("strength", ref("radius", ref("amount", "0.25")))
        val amount = ref("amount", ref("alpha", "1.0"))
        val angle = ref("angle", "0.0")
        val tint = ref("color", ref("tint", "float4(1.0)"))
        val lower = (name + " " + category).lowercase()
        val body = when {
            "blur" in lower -> """
                float r = max(abs($strength), 0.02) * 0.04;
                float ca = cos($angle * 0.0174532925);
                float sa = sin($angle * 0.0174532925);
                float3 sum = float3(0.0);
                sum += float3(image.eval(coord).rgb);
                sum += float3(image.eval(coord + float2(ca, sa) * r * size).rgb);
                sum += float3(image.eval(coord - float2(ca, sa) * r * size).rgb);
                sum += float3(image.eval(coord + float2(-sa, ca) * r * size).rgb);
                sum += float3(image.eval(coord - float2(-sa, ca) * r * size).rgb);
                return half4(sum / 5.0, image.eval(coord).a);
            """.trimIndent()
            "glow" in lower -> """
                float r = max(abs($strength), 0.02) * 0.06;
                float3 sum = float3(0.0);
                half4 src = image.eval(coord);
                sum += float3(image.eval(coord + float2(r, 0.0) * size).rgb);
                sum += float3(image.eval(coord - float2(r, 0.0) * size).rgb);
                sum += float3(image.eval(coord + float2(0.0, r) * size).rgb);
                sum += float3(image.eval(coord - float2(0.0, r) * size).rgb);
                float3 glow = sum * 0.25 * $amount;
                return half4(1.0 - (1.0 - float3(src.rgb)) * (1.0 - glow * $tint.rgb), src.a);
            """.trimIndent()
            "distort" in lower || "warp" in lower || "ripple" in lower || "wave" in lower -> """
                float2 uv = coord / size;
                float2 d = uv - 0.5;
                float k = max(abs($strength), 0.02) * 0.12;
                uv += d * sin(length(d) * 24.0 + time * 2.0) * k;
                return image.eval(clamp(uv, float2(0.0), float2(1.0)) * size);
            """.trimIndent()
            "key" in lower || "matte" in lower -> """
                half4 c = image.eval(coord);
                float l = dot(float3(c.rgb), float3(0.2126, 0.7152, 0.0722));
                float a = smoothstep(max($amount, 0.001), 1.0, l);
                return half4(float3(c.rgb) * a, c.a * a);
            """.trimIndent()
            else -> """
                half4 c = image.eval(coord);
                float3 graded = float3(c.rgb) * $tint.rgb;
                return half4(mix(float3(c.rgb), graded, clamp($amount, 0.0, 1.0)), c.a);
            """.trimIndent()
        }
        val header = buildString {
            appendLine("uniform shader image;")
            appendLine("uniform float2 size;")
            appendLine("uniform float time;")
            val reserved = setOf("image", "size", "time", "resolution", "coord")
            for (p in params) {
                if (p.id in reserved || !p.id.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) continue
                val type = when (p.kind) { "point" -> "float2"; "color" -> "float4"; "text" -> null; else -> "float" }
                if (type != null) appendLine("uniform $type ${p.id};")
            }
        }
        return header + "half4 main(float2 coord) {\n" + body + "\n}\n"
    }

    private data class Pass(val target: String?, val nested: Boolean)

    private fun parsePasses(xml: String): List<Pass> = Regex("""<pass\b([^>]*)/>""").findAll(xml).map { match ->
        val attrs = match.groupValues[1]
        val target = Regex("""\btarget="([^"]*)""").find(attrs)?.groupValues?.get(1)
        Pass(target, Regex("""\b(?:effect|src)="[^"]*""").containsMatchIn(attrs))
    }.toList()

    /**
     * Rebuilds an acPass shader as ordered shader functions.  A buffer texture
     * read becomes a call to the function that produced that target, so the
     * original horizontal/vertical, distance-field and similar chains keep
     * their real intermediate data instead of being collapsed into a random
     * generic filter.
     */
    private fun translatePasses(source: String, params: List<EffectParam>, passes: List<Pass>): String? {
        if (source.contains("acLayerToScreen") || passes.any { it.nested }) return null
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(source) ?: return null
        val open = main.range.last
        val close = matchingBrace(source, open) ?: return null
        val head = source.substring(0, main.range.first)
        val body = source.substring(open + 1, close)
        val branches = branches(body)
        val first = Regex("""if\s*\(\s*acPass\s*==\s*\d+\s*\)\s*\{""").find(body)?.range?.first ?: 0
        val preamble = body.substring(0, first)
        val suffix = body.substring(branchEnd(body) + 1)
        val targets = HashMap<String, Int>()
        for (i in passes.indices) passes[i].target?.takeIf { it.isNotBlank() }?.let { targets[it] = i }
        val stages = ArrayList<String>(passes.size)
        for (i in passes.indices) {
            val branch = branches[i] ?: if (i == passes.lastIndex) branches[-1] else null
            val stage = branch?.let { passBody(preamble + it + suffix, "uv", targets) } ?: return null
            stages += stage
        }
        val uniforms = uniforms(params)
        val out = StringBuilder(uniforms)
        for (i in stages.indices) out.append("half4 pass").append(i).append("(float2 uv);\n")
        val fixedHead = passHead(head, targets) ?: return null
        out.append(fixedHead)
        for (i in stages.indices) {
            out.append("half4 pass").append(i).append("(float2 uv) {\n")
            out.append(stages[i]).append('\n').append("}\n")
        }
        out.append("half4 main(float2 coord) {\nreturn pass").append(passes.lastIndex).append("(coord / size);\n}\n")
        return out.toString()
    }

    private fun uniforms(params: List<EffectParam>): String = buildString {
        appendLine("uniform shader image;")
        appendLine("uniform float2 size;")
        appendLine("uniform float time;")
        val reserved = setOf("image", "size", "time", "resolution", "coord")
        for (p in params) {
            if (p.id in reserved || !p.id.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) continue
            val type = when (p.kind) { "point" -> "float2"; "color" -> "float4"; "text" -> null; else -> "float" }
            if (type != null) appendLine("uniform $type ${p.id};")
        }
    }

    private fun passHead(head: String, targets: Map<String, Int>): String? {
        var s = common(head)
        s = s.replace("inputImg.size", "size")
        s = Regex("""getTexSize\s*\([^)]*\)""").replace(s, "size")
        if (s.contains("texture2DCv")) {
            s = replaceTextures(s, targets) ?: return null
        }
        return s
    }

    private fun common(source: String): String = source
        .replace(Regex("""\bvec2\b"""), "float2").replace(Regex("""\bvec3\b"""), "float3").replace(Regex("""\bvec4\b"""), "float4")
        .replace(Regex("""\bmat2\b"""), "float2x2").replace(Regex("""\bmat3\b"""), "float3x3").replace(Regex("""\bmat4\b"""), "float4x4")
        .replace(Regex("""\b(?:highp|mediump|lowp)\b"""), "")
        .replace("acTime", "time").replace("acResolution", "size")

    private fun passBody(body: String, uv: String, targets: Map<String, Int>): String? {
        var s = common(body)
        s = s.replace("acScreenNorm", uv).replace("acLayerNorm", uv)
        s = s.replace("inputImg.size", "size")
        s = Regex("""getTexSize\s*\([^)]*\)""").replace(s, "size")
        s = replaceTextures(s, targets) ?: return null
        s = s.replace(Regex("""gl_FragColor\s*=\s*([^;]+);\s*return\s*;"""), "return $1;")
        s = s.replace(Regex("""gl_FragColor\s*=\s*([^;]+);"""), "return $1;")
        s = s.replace(Regex("""\breturn\s*;"""), "return half4(0.0);")
        return if (s.contains("texture2DCv")) null else s
    }

    private fun replaceTextures(source: String, targets: Map<String, Int>): String? {
        val out = StringBuilder(source.length + 32)
        var at = 0
        while (true) {
            val start = source.indexOf("texture2DCv(", at)
            if (start < 0) break
            out.append(source, at, start)
            var p = start + "texture2DCv(".length
            var depth = 1
            var comma = -1
            val args = p
            while (p < source.length && depth > 0) {
                when (source[p]) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 1 && comma < 0) comma = p
                }
                if (depth == 0) break
                p++
            }
            if (depth != 0 || comma < 0) return null
            val texture = source.substring(args, comma).trim().removeSuffix(".texture").trim()
            val coord = source.substring(comma + 1, p).trim()
            when (texture) {
                "inputImg", "comp" -> out.append("image.eval((").append(coord).append(")*size)")
                else -> {
                    val target = targets[texture] ?: return null
                    out.append("pass").append(target).append("((").append(coord).append("))")
                }
            }
            at = p + 1
        }
        out.append(source, at, source.length)
        return out.toString()
    }

    private fun matchingBrace(text: String, open: Int): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return null
    }

    private fun branches(body: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        var at = 0
        var last = -1
        while (true) {
            val match = Regex("""if\s*\(\s*acPass\s*==\s*(\d+)\s*\)\s*\{""").find(body, at) ?: break
            val open = match.range.last
            val close = matchingBrace(body, open) ?: break
            val index = match.groupValues[1].toInt()
            out[index] = body.substring(open + 1, close)
            last = close
            at = close + 1
            val tail = Regex("""^\s*else\s*""").find(body.substring(at))
            if (tail != null) {
                val afterElse = at + tail.range.last + 1
                val nested = Regex("""^\s*if\s*\(\s*acPass\s*==\s*(\d+)\s*\)\s*\{""").find(body.substring(afterElse))
                if (nested != null) {
                    at = afterElse + nested.range.first
                    continue
                }
                val brace = body.indexOf('{', afterElse)
                if (brace >= 0) {
                    val end = matchingBrace(body, brace) ?: break
                    out[-1] = body.substring(brace + 1, end)
                    last = end
                }
            }
            at = maxOf(at, last + 1)
        }
        return out
    }

    private fun branchEnd(body: String): Int {
        var at = 0
        var end = 0
        while (true) {
            val match = Regex("""if\s*\(\s*acPass\s*==\s*\d+\s*\)\s*\{""").find(body, at) ?: break
            val close = matchingBrace(body, match.range.last) ?: break
            end = close
            at = close + 1
            val tail = Regex("""^\s*else\s*""").find(body.substring(at))
            if (tail != null) {
                val afterElse = at + tail.range.last + 1
                val nested = Regex("""^\s*if\s*\(\s*acPass\s*==\s*\d+\s*\)\s*\{""").find(body.substring(afterElse))
                if (nested != null) {
                    at = afterElse + nested.range.first
                    continue
                }
                val brace = body.indexOf('{', afterElse)
                if (brace >= 0) {
                    end = matchingBrace(body, brace) ?: break
                    at = end + 1
                }
            }
        }
        return end
    }

    private fun translate(source: String, params: List<EffectParam>): String? {
        var s = source
        if (!Regex("""texture2DCv\(\s*inputImg\.texture\s*,\s*[^)]*\)""").containsMatchIn(s)) return null
        s = s.replace(Regex("""\bvec2\b"""), "float2").replace(Regex("""\bvec3\b"""), "float3").replace(Regex("""\bvec4\b"""), "float4")
        s = s.replace(Regex("""\bmat2\b"""), "float2x2").replace(Regex("""\bmat3\b"""), "float3x3").replace(Regex("""\bmat4\b"""), "float4x4")
        s = s.replace("acScreenNorm", "(coord / size)").replace("acTime", "time").replace("acResolution", "size")
        s = Regex("""texture2DCv\(\s*inputImg\.texture\s*,\s*([^)]*)\)""").replace(s) { "image.eval((${it.groupValues[1]}) * size)" }
        if (s.contains("texture2DCv")) return null
        val uniforms = buildString {
            appendLine("uniform shader image;")
            appendLine("uniform float2 size;")
            appendLine("uniform float time;")
            val reserved = setOf("image", "size", "time", "resolution", "coord")
            for (p in params) {
                if (p.id in reserved) continue
                val type = when (p.kind) { "point" -> "float2"; "color" -> "float4"; else -> "float" }
                appendLine("uniform $type ${p.id};")
            }
        }
        s = s.replace(Regex("""void\s+main\s*\(\s*\)"""), "half4 main(float2 coord)")
        s = s.replace(Regex("""gl_FragColor\s*=\s*([^;]+);"""), "return $1;")
        if (!s.contains("half4 main")) return null
        return uniforms + s
    }

    private fun mapCategory(category: String) = when (category) {
        "blur" -> "blur"; "color" -> "color"; "distort" -> "distort"; "procedural" -> "stylize"; "3d" -> "3d"
        "text" -> "text"; "repeat" -> "repeat"; "matte" -> "key"; "transform" -> "move"; "opacity" -> "color"
        else -> "other"
    }

    private fun format(v: Float) = String.format(java.util.Locale.US, "%.6f", v)
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
