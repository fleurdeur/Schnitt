package app.absentmotion.effects

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.math.roundToInt
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads an Alight Motion <effect> definition XML and compiles its fragment
 * shader into SKSL. Multi-pass effect packs are supported: each <pass> keeps
 * its target buffer, and acPass branches become separate shader stages.
 * Effects that are pure JavaScript with no fragment stage still need a native
 * implementation; they are never silently replaced with an unrelated shader.
 */
object AmEffectImporter {
    private const val FOLDER = "am-effects"
    private val AutoEffects = setOf("oscillate", "oscillate2", "oscillate3", "spin", "pulsate", "pulsate2",
        "swing", "swing2", "jitter", "randomdisplace", "shake", "shake2", "scaleassist")
    private val OpacityEffects = setOf("fade", "blink", "blink2", "flicker", "flicker2", "pulse-opacity", "pulse_opacity2")
    /** Script-only effects now implemented by the native scene engine. */
    private val NativeScriptEffects = setOf(
        "growparts", "morphpath", "shakeparts", "poseshake", "skexnxxposeshake", "vshake", "vshakenew",
        "transform2", "skexnxx15", "facemotion", "perlindisplace",
        "strokecolor", "textspacing", "freepalette",
    )

    suspend fun import(context: Context, uri: Uri): EffectDefinition? = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return@withContext importPack(context, bytes)
        }
        val text = bytes.toString(Charsets.UTF_8)
        val def = parse(text, globals(context)) ?: return@withContext null
        val folder = File(context.filesDir, FOLDER).apply { mkdirs() }
        File(folder, digest(def.id).take(16) + ".xml").writeText(text)
        def.withImages(context)
    }

    /** Imports a whole community pack: every XML plus the textures it references. */
    private fun importPack(context: Context, bytes: ByteArray): EffectDefinition? {
        if (bytes.size > 64 shl 20) return null
        val folder = File(context.filesDir, FOLDER).apply { mkdirs() }
        val temp = File.createTempFile("effect-pack", ".zip", context.cacheDir)
        var first: EffectDefinition? = null
        try {
            temp.writeBytes(bytes)
            ZipFile(temp).use { zip ->
                var entries = 0
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    if (++entries > 2_000) break
                    if (entry.size > 32 shl 20) continue
                    val original = entry.name.replace('\\', '/')
                    if (original.startsWith("/") || original.split('/').any { it == ".." }) continue
                    val relative = when {
                        "assets/effects/" in original -> original.substringAfter("assets/effects/")
                        original.startsWith("effects/") -> original.removePrefix("effects/")
                        else -> original
                    }
                    if (relative.endsWith(".xml", true)) {
                        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                        val parsed = runCatching { parse(text, globals(context)) }.getOrNull() ?: continue
                        File(folder, relative.substringAfterLast('/')).writeText(text)
                        if (first == null) first = parsed
                    } else if (relative.startsWith("resource/") || relative.startsWith("textures/") ||
                        relative.endsWith(".png", true) || relative.endsWith(".webp", true) ||
                        relative.endsWith(".jpg", true) || relative.endsWith(".jpeg", true)) {
                        File(folder, relative).apply { parentFile?.mkdirs() }.writeBytes(
                            zip.getInputStream(entry).use { it.readBytes() }
                        )
                    }
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            temp.delete()
        }
        return first?.withImages(context)
    }

    @Volatile private var bundled: List<EffectDefinition>? = null
    @Volatile private var sharedStrings: Map<String, String>? = null
    @Volatile private var customCache: Pair<Long, List<EffectDefinition>>? = null

    fun append(context: Context, catalog: EffectCatalog): EffectCatalog {
        val folder = File(context.filesDir, FOLDER)
        val words = globals(context)
        val files = if (folder.isDirectory) folder.listFiles { f -> f.extension == "xml" }.orEmpty() else emptyArray()
        val stamp = files.sumOf { it.lastModified() xor it.length() }
        val custom = if (files.isEmpty()) emptyList() else customCache?.takeIf { it.first == stamp }?.second ?: files.mapNotNull { f ->
            runCatching { parse(f.readText(), words) }.getOrNull()?.withImages(context)
        }.also { customCache = stamp to it }
        val builtIn = bundled ?: runCatching {
            context.assets.list("am-effects").orEmpty().filter { it.endsWith(".xml") }.mapNotNull { name ->
                runCatching { context.assets.open("am-effects/$name").bufferedReader().use { parse(it.readText(), words) } }.getOrNull()
            }.map { it.withImages(context) }
        }.getOrDefault(emptyList()).also { bundled = it }
        // The bundled Alight XMLs include copies of effects that now have a
        // native implementation (Directional Blur, Motion Blur, Rays, Cube,
        // Grid, Page Curl...). Keep the built-in one: the imported copy can be
        // an old shader or a partial translation, and showing both is the
        // duplicate that looks broken in the library.
        val known = catalog.definitions.flatMap { listOf(it.key, it.id) }.toMutableSet()
        val extra = (builtIn + custom).filter { def ->
            !duplicateOfNative(def, catalog) && known.add(def.key) && known.add(def.id)
        }.distinctBy { it.key }.map { it.withImages(context) }
        return if (extra.isEmpty()) catalog else catalog.copy(definitions = catalog.definitions + extra)
    }

    internal fun duplicateOfNative(def: EffectDefinition, catalog: EffectCatalog): Boolean =
        catalog.definitions.any {
            !it.key.startsWith("am-") && !it.external && effectName(it.name) == effectName(def.name)
        }

    internal fun effectName(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun EffectDefinition.withImages(context: Context): EffectDefinition {
        if (resourcePaths.isEmpty() || images.isNotEmpty()) return this
        val loaded = resourcePaths.mapNotNull { (name, path) ->
            // Community packs refer to textures as resource/foo, textures/foo
            // and assets/effects/textures/foo. Resolve all three against the
            // shared am-effects asset folder.
            val bare = path.substringAfterLast('/')
            val candidates = listOf(
                path,
                path.removePrefix("assets/effects/"),
                "textures/$bare",
                "resource/$bare",
            ).distinct()
            val bytes = candidates.firstNotNullOfOrNull { candidate ->
                runCatching { context.assets.open("am-effects/$candidate").use { it.readBytes() } }.getOrNull()
                    ?: runCatching { File(context.filesDir, "$FOLDER/$candidate").readBytes() }.getOrNull()
            }
            bytes?.let { name to it }
        }.toMap()
        return if (loaded.isEmpty()) this else copy(images = loaded)
    }

    private fun globals(context: Context): Map<String, String> {
        sharedStrings?.let { return it }
        val read = runCatching {
            val json = JSONObject(context.assets.open("am-effects/strings.json").bufferedReader().use { it.readText() })
            buildMap { json.keys().forEach { key -> put(key, json.optString(key)) } }
        }.getOrDefault(emptyMap())
        sharedStrings = read
        return read
    }

    internal fun parse(xml: String, shared: Map<String, String> = emptyMap()): EffectDefinition? {
        val effectTag = Regex("""<effect\s([^>]*)>""").find(xml)?.groupValues?.get(1) ?: return null
        fun tagAttr(name: String): String? = Regex("""(?:^|\s)$name="([^"]*)"""").find(effectTag)?.groupValues?.get(1)
        val rawId = tagAttr("id") ?: return null
        val id = decodeEffectId(rawId)
        val strings = shared + strings(xml)
        val params = parseParams(xml, strings)
        val shader = Regex("""<shader([^>]*)>\s*<!\[CDATA\[(.*?)\]\]>\s*</shader>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] to it.groupValues[2] }.toList().let { found ->
                // Prefer the fragment stage; several packs put a vertex shader first.
                (found.firstOrNull { it.first.contains("fragment", true) }
                    ?: found.firstOrNull { !it.first.contains("vertex", true) }
                    ?: found.firstOrNull())?.second
            }
        val passes = parsePasses(xml)
        // A pack readme can carry an <effect> tag with no shader, pass, script or
        // controls. It is metadata, not something that can be applied to a layer.
        if (shader == null && passes.isEmpty() && params.isEmpty() && !xml.contains("<script")) return null
        val name = label("name", tagAttr("name"), strings).ifBlank {
            id.substringAfterLast('.').replace('-', ' ').replaceFirstChar { it.uppercase() }
        }
        val description = label("desc", tagAttr("desc"), strings).takeIf { it.isNotBlank() } ?: "Alight Motion effect"
        val category = Regex("""<effect\s+[^>]*category="([^"]+)""" + "\"").find(xml)?.groupValues?.get(1) ?: "other"
        val textures = textureIds(xml)
        val chained = shader?.let { if (passes.size > 1) translatePasses(it, params, passes, textures) else null }
        val native = shader?.let { if (passes.size > 1) nativePasses(id, it, params, passes, textures) else null }
        val stem = id.substringAfterLast('.')
        val auto = shader == null && (stem in AutoEffects || stem in NativeScriptEffects)
        val alpha = shader == null && stem in OpacityEffects
        val part = stem == "growparts" || stem == "shakeparts"
        val sksl = if (!native.isNullOrEmpty()) null else chained ?: shader?.let { translate(it, params, textures) } ?: when {
            auto -> null
            alpha -> null
            else -> fallback(name, category, params)
        }
        val key = "am-" + id.substringAfterLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "") + "-" + digest(id).take(4)
        val resourcePaths = Regex("""<texture\s+([^>]*?)/?>""").findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val textureId = Regex("""\bid="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val src = Regex("""\bsrc="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            textureId to src
        }.toMap()
        val note = when {
            chained != null -> "$description · ${passes.size}-stage Alight shader"
            auto -> "$description · Alight transform controls"
            alpha -> "$description · Alight opacity controls"
            else -> description
        }
        return EffectDefinition(id, key, name, mapCategory(category), note, params, sksl, native = auto || alpha || !native.isNullOrEmpty(), status = "ok",
            alsoIn = listOf("external"), page = key, passes = native.orEmpty(), fallback = identityShader(), resourcePaths = resourcePaths,
            applyToChildren = part)
    }

    /** Some community packs store the effect id as ASCII hex instead of text. */
    private fun decodeEffectId(raw: String): String {
        if (raw.length < 16 || raw.length % 2 != 0) return raw
        if (!raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return raw
        val text = runCatching {
            ByteArray(raw.length / 2) { raw.substring(it * 2, it * 2 + 2).toInt(16).toByte() }.toString(Charsets.UTF_8)
        }.getOrNull() ?: return raw
        return text.takeIf { it.startsWith("com.") || it.startsWith("absent.") } ?: raw
    }

    private fun strings(xml: String): Map<String, String> = Regex("""<string\s+name="([^"]+)">([^<]*)</string>""")
        .findAll(xml).associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun label(id: String, raw: String?, strings: Map<String, String>): String {
        val value = raw?.trim().orEmpty()
        val key = when {
            value.startsWith("@string/") -> value.substringAfterLast('/')
            value.startsWith("@am:string/") -> value.substringAfter("@am:string/")
            value.startsWith("@") -> value.substringAfterLast('/')
            else -> return value.ifBlank { id.replace('_', ' ') }
        }
        return strings[key] ?: key.replace('_', ' ')
    }

    private fun parseParams(xml: String, strings: Map<String, String>): List<EffectParam> {
        val out = ArrayList<EffectParam>()
        val tag = Regex("""<(spinner|slider|switch|point|xyz|color|selector|int|texture|orient|hue-disc|float)\s+([^>]*?)/?>""", RegexOption.DOT_MATCHES_ALL)
        for (m in tag.findAll(xml)) {
            val kind = m.groupValues[1]
            val attrs = m.groupValues[2]
            fun attr(n: String): String? = Regex("""(?:^|\s)$n="([^"]*)"""").find(attrs)?.groupValues?.get(1)
            val id = attr("id") ?: continue
            if (id in setOf("inputImg", "comp", "image", "input", "output", "src", "hblur", "vblur", "xDistBuf", "yDistBuf",
                    "blurBuf", "downImg", "ds1", "ds2", "surfBuf", "hdist", "hstreak", "vstreak", "blurImg", "content", "edge", "rgbanoise")) continue
            val rawDefault = attr("default") ?: attr("value") ?: ""
            val shown = label(id, attr("label"), strings)
            val type = attr("type").orEmpty()
            val numeric = rawDefault.trim().toFloatOrNull() ?: 0f
            val low = attr("min")?.toFloatOrNull() ?: if (numeric < 0f) -1f else 0f
            val high = attr("max")?.toFloatOrNull()?.takeIf { it > low } ?: maxOf(numeric + 1f, low + 1f)
            val step = attr("step")?.toFloatOrNull() ?: if (type == "integer") 1f else 0.01f
            when (kind) {
                "spinner" ->
                    out += EffectParam(id, shown, if (type == "angle") "angle" else "float",
                        format(numeric.coerceIn(low, high)), low, high, step, percent = type == "percent",
                        unit = when (type) { "percent" -> "%"; "hz" -> "Hz"; else -> "" })
                "slider", "int", "orient", "float" ->
                    out += EffectParam(id, shown, if (type == "angle") "angle" else "float",
                        format(numeric.coerceIn(low, high)), low, high, step, percent = type == "percent",
                        unit = if (type == "percent") "%" else "")
                "hue-disc" -> {
                    val parts = rawDefault.split(',').mapNotNull { it.trim().toFloatOrNull() }
                    val values = List(3) { format(parts.getOrElse(it) { if (it == 1) 1f else 0f }) }
                    out += EffectParam(id, shown, "hue", values.joinToString(","), -360f, 360f, 0.1f)
                }
                "switch" -> out += EffectParam(id, shown, "switch", if (rawDefault == "true" || rawDefault == "1") "true" else "false")
                "point", "xyz" -> {
                    val p = rawDefault.split(',').mapNotNull { it.trim().toFloatOrNull() }
                    val count = if (kind == "xyz") 3 else 2
                    val values = List(count) { format(p.getOrElse(it) { 0f }) }
                    out += EffectParam(id, shown, if (count == 3) "point3" else "point", values.joinToString(","),
                        attr("min")?.toFloatOrNull() ?: -100000f, attr("max")?.toFloatOrNull() ?: 100000f, 1f)
                }
                "color" -> {
                    val colour = rawDefault.trim().lowercase().takeIf { Regex("#[0-9a-f]{6}([0-9a-f]{2})?").matches(it) } ?: "#ffffffff"
                    out += EffectParam(id, shown, "color", if (colour.length == 7) "#ff" + colour.drop(1) else colour)
                }
                "selector" -> {
                    val block = xml.substring(m.range.last + 1).substringBefore("</selector>")
                    val options = choices(block, strings)
                    if (options.isNotEmpty()) {
                        val values = choiceValues(block).takeIf { it.size == options.size } ?: options.indices.map { it.toFloat() }
                        val actual = values.firstOrNull { kotlin.math.abs(it - numeric) < 0.0001f } ?: values.first()
                        out += EffectParam(id, shown, "choice", format(actual), values.min(), values.max(), 1f,
                            options = options, choiceValues = values)
                    }
                }
            }
        }
        return out.distinctBy { it.id }
    }

    private fun choices(block: String, strings: Map<String, String>): List<String> =
        Regex("""<choice[^>]*label="([^"]*)""" + "\"").findAll(block)
            .map { label(it.groupValues[1].substringAfterLast('/'), it.groupValues[1], strings) }
            .filter { it.isNotBlank() }.toList()

    private fun choiceValues(block: String): List<Float> =
        Regex("""<choice[^>]*value="([^"]*)""" + "\"").findAll(block)
            .mapNotNull { it.groupValues[1].trim().toFloatOrNull() }.toList()

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
                sum += float3(image.eval(coord + float2(ca, sa) * r * amSize).rgb);
                sum += float3(image.eval(coord - float2(ca, sa) * r * amSize).rgb);
                sum += float3(image.eval(coord + float2(-sa, ca) * r * amSize).rgb);
                sum += float3(image.eval(coord - float2(-sa, ca) * r * amSize).rgb);
                return half4(sum / 5.0, image.eval(coord).a);
            """.trimIndent()
            "glow" in lower -> """
                float r = max(abs($strength), 0.02) * 0.06;
                float3 sum = float3(0.0);
                half4 src = image.eval(coord);
                sum += float3(image.eval(coord + float2(r, 0.0) * amSize).rgb);
                sum += float3(image.eval(coord - float2(r, 0.0) * amSize).rgb);
                sum += float3(image.eval(coord + float2(0.0, r) * amSize).rgb);
                sum += float3(image.eval(coord - float2(0.0, r) * amSize).rgb);
                float3 glow = sum * 0.25 * $amount;
                return half4(1.0 - (1.0 - float3(src.rgb)) * (1.0 - glow * $tint.rgb), src.a);
            """.trimIndent()
            "distort" in lower || "warp" in lower || "ripple" in lower || "wave" in lower -> """
                float2 uv = coord / amSize;
                float2 d = uv - 0.5;
                float k = max(abs($strength), 0.02) * 0.12;
                uv += d * sin(length(d) * 24.0 + amTime * 2.0) * k;
                return image.eval(clamp(uv, float2(0.0), float2(1.0)) * amSize);
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
            appendLine("uniform float2 amSize;")
            appendLine("uniform float amTime;")
        appendLine("uniform float amDuration;")
            val reserved = ReservedUniforms
            for (p in params) {
                if (p.id in reserved || !p.id.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) continue
                val type = when (p.kind) { "point" -> "float2"; "point3", "hue" -> "float3"; "color" -> "float4"; "text" -> null; else -> "float" }
                if (type != null) appendLine("uniform $type ${p.id};")
            }
        }
        return header + "half4 main(float2 coord) {\n" + body + "\n}\n"
    }

    private data class Pass(val target: String?, val effect: String?)

    private fun parsePasses(xml: String): List<Pass> = Regex("""<pass\b([^>]*)>""").findAll(xml).map { match ->
        val attrs = match.groupValues[1]
        // A pass is either self-closing (<pass target="x"/>) or an explicit
        // open/close pair (<pass target="x"></pass>).  Alight's own XML uses
        // both forms; recognising only the first one collapsed every second
        // and later pass into a single full-screen shader.
        val target = Regex("""\btarget="([^"]*)""").find(attrs)?.groupValues?.get(1)
        val effect = Regex("""\beffect="([^"]*)""").find(attrs)?.groupValues?.get(1)
        Pass(target, effect)
    }.toList()

    /**
     * Rebuilds an acPass shader as ordered shader functions.  A buffer texture
     * read becomes a call to the function that produced that target, so the
     * original horizontal/vertical, distance-field and similar chains keep
     * their real intermediate data instead of being collapsed into a random
     * generic filter.
     */
    private fun nativePasses(id: String, source: String, params: List<EffectParam>, passes: List<Pass>, textures: Set<String>): List<EffectPass>? {
        val clean = common(expandMacros(source))
        if (clean.contains("acLayerToScreen")) { /* handled by header uniforms */ }
        val allTargets = passes.mapNotNull { it.target?.takeIf { t -> t.isNotBlank() && t != "image" && t != "comp" } }.distinct()
        if (!clean.contains("acPass")) {
            val images = textures - allTargets.toSet()
            val targetHeader = buildString { for (t in allTargets) if (t.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) appendLine("uniform shader $t;") }
            val out = ArrayList<EffectPass>(passes.size)
            for (pass in passes) {
                val helper = pass.effect?.let(::decodeEffectId)?.takeIf { it != id }
                if (helper != null) {
                    val shader = when {
                        helper.contains("blur", true) -> blurShader()
                        helper.contains("findedges", true) -> edgeShader()
                        else -> null
                    }
                    if (shader != null) {
                        out += EffectPass(shader, pass.target?.takeIf { it.isNotBlank() })
                        continue
                    }
                }
                var stage = clean
                stage = specializeSampler(stage)
                stage = convertBody(stage, "coord", emptyMap(), images)
                stage = sampleTargets(stage, allTargets)
                stage = stage.replace(Regex("""\bgl_FragColor\s*=\s*([^;]+);"""), "return $1;")
                stage = stage.replace(Regex("""\breturn\s*;"""), "return half4(0.0);")
                val shader = header(params, images) + targetHeader +
                    "half4 main(float2 coord) {\n" + stage + "\n}\n"
                out += EffectPass(shader, pass.target?.takeIf { it.isNotBlank() })
            }
            return out
        }
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(clean) ?: return null
        val open = main.range.last
        val close = matchingBrace(clean, open) ?: return null
        val body = clean.substring(open + 1, close)
        val branches = branches(body)
        val first = Regex("""if\s*\(\s*acPass\s*==\s*\d+\s*\)\s*\{""").find(body)?.range?.first ?: 0
        val preamble = body.substring(0, first)
        val suffix = body.substring(branchEnd(body) + 1)
        val targets = passes.mapNotNull { it.target?.takeIf { t -> t.isNotBlank() && t != "image" && t != "comp" } }.distinct()
        val images = textures - targets.toSet()
        val targetHeader = buildString { for (t in targets) if (t.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) appendLine("uniform shader $t;") }
        val out = ArrayList<EffectPass>(passes.size)
        for (i in passes.indices) {
            val helper = passes[i].effect?.let(::decodeEffectId)?.takeIf { it != id }
            if (helper != null && helper.contains("blur", true)) {
                out += EffectPass(blurShader(), passes[i].target?.takeIf { it.isNotBlank() })
                continue
            }
            val branch = branches[i] ?: if (i == passes.lastIndex) branches[-1] ?: suffix.takeIf { it.isNotBlank() } else null
            var stage = common(preamble + branch + suffix)
            stage = Regex("""\bacPass\b""").replace(stage, i.toString())
            stage = specializeSampler(stage)
            stage = convertBody(stage, "coord", emptyMap(), images)
            stage = sampleTargets(stage, targets)
            stage = stage.replace(Regex("""\bgl_FragColor\s*=\s*([^;]+);"""), "return $1;")
            stage = stage.replace(Regex("""\breturn\s*;"""), "return half4(0.0);")
            val shader = header(params, images) + targetHeader +
                "half4 main(float2 coord) {\n" + stage + "\n}\n"
            out += EffectPass(shader, passes[i].target?.takeIf { it.isNotBlank() })
        }
        return out
    }

    private fun blurShader(): String = buildString {
        appendLine("uniform shader image;")
        appendLine("uniform float2 amSize;")
        appendLine("uniform float4 screen;")
        appendLine("uniform float2 layer;")
        appendLine("uniform float3x3 toLayer;")
        appendLine("uniform float3x3 fromLayer;")
        appendLine("uniform float2 acScreenSize;")
        appendLine("uniform float2 acLayerSize;")
        appendLine("uniform float2 acLayerCenter;")
        appendLine("uniform float2 acLayerCenterNorm;")
        appendLine("uniform float2 acLayerPivot;")
        appendLine("uniform float2 acVelocity;")
        appendLine("uniform float acScaleVelocity;")
        appendLine("uniform float acAngularVelocity;")
        appendLine("uniform float2 acProjectSize;")
        appendLine("uniform float acShowGuides;")
        appendLine("uniform float2 acPreviewSize;")
        appendLine("uniform float3x3 acLayerToScreen;")
        appendLine("uniform float3x3 acScreenToLayer;")
        appendLine("uniform float acLayerSizeNorm;")
        appendLine("uniform float amTime;")
        appendLine("uniform float amDuration;")
        appendLine("uniform float featureSize;")
        appendLine("half4 main(float2 coord) {")
        appendLine("float2 uv = (coord - screen.xy) / max(screen.zw, float2(1.0));")
        appendLine("float2 px = vec2(0.0015) * max(featureSize, 1.0) / 50.0;")
        appendLine("float4 sum = float4(0.0);")
        appendLine("for (int y = -2; y <= 2; y++) for (int x = -2; x <= 2; x++) sum += image.eval((uv + float2(x * px.x, y * px.y)) * screen.zw + screen.xy);")
        appendLine("return sum / 25.0;")
        appendLine("}")
    }

    private fun edgeShader(): String = buildString {
        appendLine("uniform shader image;")
        appendLine("uniform float2 amSize;")
        appendLine("uniform float4 screen;")
        appendLine("half4 main(float2 coord) {")
        appendLine("float2 px = max(screen.zw, float2(1.0)) / max(amSize, float2(1.0));")
        appendLine("float l = dot(float3(image.eval(coord).rgb), float3(0.2126, 0.7152, 0.0722));")
        appendLine("float lx = dot(float3(image.eval(coord + float2(px.x, 0.0)).rgb), float3(0.2126, 0.7152, 0.0722));")
        appendLine("float ly = dot(float3(image.eval(coord + float2(0.0, px.y)).rgb), float3(0.2126, 0.7152, 0.0722));")
        appendLine("float edge = clamp(length(float2(lx - l, ly - l)) * 2.0, 0.0, 1.0);")
        appendLine("return half4(half3(edge), 1.0);")
        appendLine("}")
    }

    private fun translatePasses(source: String, params: List<EffectParam>, passes: List<Pass>, textures: Set<String>): String? {
        val clean = common(expandMacros(source))
        if (clean.contains("acPass")) return null
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(clean) ?: return null
        val open = main.range.last
        val close = matchingBrace(clean, open) ?: return null
        val body = clean.substring(open + 1, close)
        val branches = branches(body)
        val first = Regex("""if\s*\(\s*acPass\s*==\s*\d+\s*\)\s*\{""").find(body)?.range?.first ?: 0
        val preamble = body.substring(0, first)
        val suffix = body.substring(branchEnd(body) + 1)
        val targets = HashMap<String, Int>()
        for (i in passes.indices) passes[i].target?.takeIf { it.isNotBlank() }?.let { targets[it] = i }
        val images = textures - targets.keys
        val stages = ArrayList<String>(passes.size)
        for (i in passes.indices) {
            val branch = branches[i] ?: if (i == passes.lastIndex) branches[-1] ?: suffix.takeIf { it.isNotBlank() } else null
            var stage = common(preamble + branch + suffix)
            stage = Regex("""\bacPass\b""").replace(stage, i.toString())
            stage = convertBody(stage, "uv", targets, images, normalized = true)
            stage = stage.replace(Regex("""\bgl_FragColor\s*=\s*([^;]+);"""), "return $1;")
            stage = stage.replace(Regex("""\breturn\s*;"""), "return half4(0.0);")
            stages += stage
        }
        val out = StringBuilder(header(params, images))
        for (i in stages.indices) out.append("half4 pass").append(i).append("(float2 uv);\n")
        for (i in stages.indices) {
            out.append("half4 pass").append(i).append("(float2 uv) {\n")
            out.append(stages[i]).append('\n').append("}\n")
        }
        out.append("half4 main(float2 coord) {\nreturn pass").append(passes.lastIndex).append("(AcScreenNorm(coord));\n}\n")
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
        val pattern = Regex("""if\s*\(\s*acPass\s*==\s*(\d+)(?:\s*\|\|\s*acPass\s*==\s*(\d+))?\s*\)\s*\{""")
        val found = pattern.findAll(body).toList()
        for (match in found) {
            val open = match.range.last
            val close = matchingBrace(body, open) ?: continue
            val chunk = body.substring(open + 1, close)
            out[match.groupValues[1].toInt()] = chunk
            match.groupValues[2].takeIf { it.isNotBlank() }?.toInt()?.let { out[it] = chunk }
        }
        // A final plain else is the fallback for any pass index not named above.
        val last = found.lastOrNull()
        val close = last?.let { matchingBrace(body, it.range.last) }
        if (close != null) {
            val tail = body.substring(close + 1)
            val otherwise = Regex("""^\s*else\s*\{""").find(tail)
            if (otherwise != null) {
                val brace = close + 1 + otherwise.range.last
                val end = matchingBrace(body, brace)
                if (end != null) out[-1] = body.substring(brace + 1, end)
            }
        }
        return out
    }

    private fun branchEnd(body: String): Int {
        val pattern = Regex("""if\s*\(\s*acPass\s*==\s*\d+(?:\s*\|\|\s*acPass\s*==\s*\d+)?\s*\)\s*\{""")
        var end = 0
        val found = pattern.findAll(body).toList()
        for (match in found) {
            val close = matchingBrace(body, match.range.last) ?: continue
            if (close > end) end = close
        }
        val last = found.lastOrNull() ?: return end
        val close = matchingBrace(body, last.range.last) ?: return end
        val tail = body.substring(close + 1)
        val otherwise = Regex("""^\s*else\s*\{""").find(tail)
        if (otherwise != null) {
            val brace = close + 1 + otherwise.range.last
            val finish = matchingBrace(body, brace)
            if (finish != null && finish > end) end = finish
        }
        return end
    }

    private fun translate(source: String, params: List<EffectParam>, textures: Set<String>): String? {
        var s = expandMacros(source)
        s = common(s)
        s = convertBody(s, "coord", emptyMap(), textures)
        s = s.replace(Regex("""void\s+main\s*\(\s*\)"""), "half4 main(float2 coord)")
        if (!s.contains("half4 main")) return null
        s = s.replace(Regex("""\bgl_FragColor\s*=\s*([^;]+);"""), "return $1;")
        s = s.replace(Regex("""\breturn\s*;"""), "return half4(0.0);")
        return header(params, textures) + s
    }

    private fun identityShader(): String = "uniform shader image;\nhalf4 main(float2 coord) { return image.eval(coord); }\n"

    private fun textureIds(source: String): Set<String> =
        Regex("""<texture\s+([^>]*)/?>""").findAll(source).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val id = Regex("""\bid="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val src = Regex("""\bsrcType="([^"]+)"""").find(attrs)?.groupValues?.get(1).orEmpty()
            id.takeIf { id != "inputImg" && src != "buffer" }
        }.toSet()

    private fun header(params: List<EffectParam>, textures: Set<String>): String = buildString {
        appendLine("uniform shader image;")
        appendLine("uniform float2 amSize;")
        appendLine("uniform float4 screen;")
        appendLine("uniform float2 layer;")
        appendLine("uniform float3x3 toLayer;")
        appendLine("uniform float3x3 fromLayer;")
        appendLine("uniform float2 acScreenSize;")
        appendLine("uniform float2 acLayerSize;")
        appendLine("uniform float2 acLayerCenter;")
        appendLine("uniform float2 acLayerCenterNorm;")
        appendLine("uniform float2 acLayerPivot;")
        appendLine("uniform float2 acVelocity;")
        appendLine("uniform float acScaleVelocity;")
        appendLine("uniform float acAngularVelocity;")
        appendLine("uniform float2 acProjectSize;")
        appendLine("uniform float acShowGuides;")
        appendLine("uniform float2 acPreviewSize;")
        appendLine("uniform float3x3 acLayerToScreen;")
        appendLine("uniform float3x3 acScreenToLayer;")
        appendLine("uniform float acLayerSizeNorm;")
        appendLine("uniform float amTime;")
        appendLine("uniform float amDuration;")
        for (id in textures) if (id.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            appendLine("uniform shader $id;")
            appendLine("uniform float2 ${id}Size;")
        }
        appendLine("float2 AcScreenNorm(float2 p) { return (p - screen.xy) / max(screen.zw, float2(1.0)); }")
        appendLine("float2 AcLayerNorm(float2 p) {")
        appendLine("    float3 h = toLayer * float3(p, 1.0);")
        appendLine("    return h.xy / max(layer, float2(1.0));")
        appendLine("}")
        appendLine("float2 AcLayerNormFromScreen(float2 s) { return AcLayerNorm(s * screen.zw + screen.xy); }")
        val reserved = ReservedUniforms
        for (p in params) {
            if (p.id in reserved || !p.id.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) continue
            val type = when (p.kind) { "point" -> "float2"; "point3", "hue" -> "float3"; "color" -> "float4"; "text" -> null; else -> "float" }
            if (type != null) appendLine("uniform $type ${p.id};")
        }
    }

    private fun convertBody(body: String, uv: String, targets: Map<String, Int>, textures: Set<String>, normalized: Boolean = false): String {
        var s = body
        s = s.replace("acScreenNorm", if (normalized) uv else "AcScreenNorm($uv)")
        s = s.replace("acLayerNorm", if (normalized) "AcLayerNormFromScreen($uv)" else "AcLayerNorm($uv)")
        s = s.replace("acTime", "amTime").replace("acDuration", "amDuration").replace("acResolution", "acPreviewSize")
        s = s.replace("gl_FragCoord", "float4($uv, 0.0, 1.0)")
        s = sampleTextures(s, uv, targets, textures)
        // inputImg is the layer's own content, so its pixel size is the layer
        // size, not the whole composition. Using screen.zw here made every
        // Alight shader that converts pixels to UV (blurs, outlines, edge
        // detection, noise, ...) operate at the wrong radius whenever the
        // layer was smaller than the project.
        s = s.replace("getTexSize(inputImg.size)", "acLayerSize")
        s = s.replace("inputImg.size", "acLayerSize")
        s = s.replace("getTexSize(comp.size)", "screen.zw")
        s = s.replace("comp.size", "screen.zw")
        for (id in textures) {
            s = s.replace("getTexSize($id.size)", "${id}Size").replace("$id.size", "${id}Size")
        }
        return s
    }

    /** Turns a helper that takes sampler2D into a helper that reads the one
     *  buffer used by this pass. SkSL has no sampler function parameters, but
     *  Alight uses that pattern for gaussian-style blur helpers. */
    private fun specializeSampler(source: String): String {
        if (!source.contains("sampler2D")) return source
        val call = Regex("""(\w+)\s*\(\s*(\w+)\.texture\s*,""").find(source) ?: return source
        val helper = call.groupValues[1]
        val sampler = call.groupValues[2]
        var s = source
        s = Regex("""sampler2D\s+${Regex.escape(sampler)}\s*,""").replace(s, "")
        s = Regex("""${Regex.escape(helper)}\s*\(\s*${Regex.escape(sampler)}\.texture\s*,""").replace(s, "$helper(")
        s = Regex("""\b${Regex.escape(sampler)}\b""").replace(s, sampler.let { call.groupValues[2] })
        // The sampler variable name and the buffer name are sometimes the same
        // (texture), so move the actual child name in from the call.
        s = Regex("""texture2DCv\s*\(\s*${Regex.escape(call.groupValues[2])}\s*,""").replace(s, "texture2DCv(${call.groupValues[2]}.texture,")
        s = Regex("""texture2D\s*\(\s*${Regex.escape(call.groupValues[2])}\s*,""").replace(s, "texture2D(${call.groupValues[2]}.texture,")
        return s
    }

    private fun sampleTextures(source: String, uv: String, targets: Map<String, Int>, textures: Set<String>): String =
        rewriteTextures(source) { texture, coord ->
            when {
                texture == "inputImg" -> "image.eval(($coord) * screen.zw + screen.xy)"
                texture == "comp" -> "comp.eval(($coord) * screen.zw + screen.xy)"
                texture in targets -> "pass${targets[texture]}(($coord))"
                texture in textures -> "$texture.eval(($coord) * ${texture}Size)"
                else -> null
            }
        }

    private fun sampleTargets(source: String, targets: List<String>): String =
        rewriteTextures(source) { texture, coord ->
            if (texture in targets) "$texture.eval(($coord) * screen.zw + screen.xy)" else null
        }

    /** Rewrites texture2D/texture2DCv calls with balanced parentheses, so nested
     *  expressions such as (vec4(uv,0,1) * acLayerToScreen).xy survive intact. */
    private fun rewriteTextures(source: String, map: (String, String) -> String?): String {
        val out = StringBuilder(source.length + 64)
        var at = 0
        while (true) {
            val match = Regex("""\b(texture2DCv|texture2DProj|texture2D)\s*\(""").find(source, at) ?: break
            val open = source.indexOf('(', match.range.first)
            var p = open + 1
            var depth = 1
            var comma = -1
            val commas = ArrayList<Int>(4)
            while (p < source.length && depth > 0) {
                when (source[p]) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 1) {
                        if (comma < 0) comma = p
                        commas += p
                    }
                }
                if (depth == 0) break
                p++
            }
            if (depth != 0 || comma < 0) { out.append(source, at, p + 1); at = p + 1; continue }
            val texture = source.substring(open + 1, comma).trim().removeSuffix(".texture").trim()
            val secondEnd = commas.getOrNull(1) ?: p
            var coord = source.substring(comma + 1, secondEnd).trim()
            // GLSL sampler calls may carry an optional bias or gradient pair.
            // A runtime shader has no derivatives/bias, so the first coordinate
            // argument is the complete sample position for this use.
            if (match.groupValues[1] == "texture2DProj") coord = "($coord).xy / max(($coord).w, 1e-6)"
            val replacement = map(texture, coord)
            out.append(source, at, match.range.first)
            out.append(replacement ?: source.substring(match.range.first, p + 1))
            at = p + 1
        }
        out.append(source, at, source.length)
        return out.toString()
    }

    private fun expandMacros(source: String): String {
        val functions = ArrayList<Triple<String, List<String>, String>>()
        val objects = LinkedHashMap<String, String>()
        val lines = ArrayList<String>()
        for (line in source.lineSequence()) {
            val define = Regex("""^\s*#\s*define\s+([A-Za-z_][A-Za-z0-9_]*)(\(([^)]*)\))?\s*(.*)$""").find(line)
            if (define != null) {
                val name = define.groupValues[1]
                val args = define.groupValues[3]
                val body = define.groupValues[4].trim()
                if (args.isNotBlank()) functions += Triple(name, args.split(',').map { it.trim() }, body)
                else objects[name] = body
                continue
            }
            if (line.trimStart().startsWith("#")) continue
            lines += line
        }
        var text = lines.joinToString("\n")
        fun expandOne(name: String, params: List<String>, body: String, input: String): String {
            val out = StringBuilder(input.length + 32)
            var at = 0
            while (true) {
                val match = Regex("""\b${Regex.escape(name)}\s*\(""").find(input, at) ?: break
                var p = match.range.last + 1
                var depth = 1
                val args = ArrayList<String>()
                val argStart = p
                var piece = argStart
                while (p < input.length && depth > 0) {
                    when (input[p]) {
                        '(' -> depth++
                        ')' -> depth--
                        ',' -> if (depth == 1) {
                            args += input.substring(piece, p).trim()
                            piece = p + 1
                        }
                    }
                    if (depth == 0) break
                    p++
                }
                if (depth != 0) break
                args += input.substring(piece, p).trim()
                out.append(input, at, match.range.first)
                var replaced = body
                params.forEachIndexed { i, param -> replaced = Regex("""\b${Regex.escape(param)}\b""").replace(replaced, args.getOrElse(i) { "" }) }
                out.append(replaced)
                at = p + 1
            }
            out.append(input, at, input.length)
            return out.toString()
        }
        repeat(3) {
            for ((name, params, body) in functions) text = expandOne(name, params, body, text)
            for ((name, body) in objects) {
                text = Regex("""\b${Regex.escape(name)}\b""").replace(text, Regex.escapeReplacement(body))
            }
        }
        return text
    }

    private fun common(source: String): String = source
        .replace(Regex("""\bvec2\b"""), "float2").replace(Regex("""\bvec3\b"""), "float3").replace(Regex("""\bvec4\b"""), "float4")
        .replace(Regex("""\bmat2\b"""), "float2x2").replace(Regex("""\bmat3\b"""), "float3x3").replace(Regex("""\bmat4\b"""), "float4x4")
        .replace(Regex("""\b(?:highp|mediump|lowp)\b"""), "")
        .replace(Regex("""\bin\s+"""), "")
        .replace("acTime", "amTime").replace("acResolution", "acPreviewSize")
        .let { boundedIntLoops(boundedLoops(it)) }

    /** Alight shaders often loop with a float index and a uniform bound. SkSL
     *  only unrolls constant loops, so turn each one into a bounded integer
     *  loop and break on the original condition. */
    private fun boundedLoops(source: String): String {
        val pattern = Regex("""for\s*\(\s*float\s+(\w+)\s*=\s*([^;]+);\s*\1\s*(<=|>=|<|>)\s*([^;]+);\s*\1\s*([+\-])=\s*([^)]+)\)\s*\{""")
        var out = source
        var serial = 0
        while (true) {
            val match = pattern.find(out) ?: break
            val name = match.groupValues[1]
            val start = match.groupValues[2].trim()
            val op = match.groupValues[3]
            val end = match.groupValues[4].trim()
            val sign = match.groupValues[5]
            val step = match.groupValues[6].trim()
            val open = match.range.last
            var depth = 0
            var close = -1
            for (i in open until out.length) {
                if (out[i] == '{') depth++
                else if (out[i] == '}') {
                    depth--
                    if (depth == 0) { close = i; break }
                }
            }
            if (close < 0) break
            val id = "__i${serial++}"
            val delta = if (sign == "-") "-($step)" else step
            val header = "for (int $id = 0; $id < 512; $id++) { float $name = ($start) + ($delta) * float($id); if (!($name $op $end)) break; "
            out = out.substring(0, match.range.first) + header + out.substring(open + 1, close + 1) + out.substring(close + 1)
        }
        return out
    }

    /** SkSL only unrolls loops with a constant bound. Alight also uses
     *  uniform-bounded integer loops for sample counts and octave counts, so
     *  give each one a safe constant bound and break at the real condition. */
    private fun boundedIntLoops(source: String): String {
        val pattern = Regex("""for\s*\(\s*int\s+(\w+)\s*=\s*([^;]+);\s*\1\s*(<=|>=|<|>)\s*([^;]+);\s*\1\s*(\+\+|--|\+=|-=)\s*([^)]*)\)\s*\{""")
        var out = source
        var searchAt = 0
        var serial = 0
        while (true) {
            val match = pattern.find(out, searchAt) ?: break
            val end = match.groupValues[4].trim()
            if (end.matches(Regex("""[0-9.]+"""))) {
                searchAt = match.range.last + 1
                continue
            }
            val name = match.groupValues[1]
            val start = match.groupValues[2].trim()
            val op = match.groupValues[3]
            val update = match.groupValues[5]
            val step = match.groupValues[6].trim()
            val open = match.range.last
            var depth = 0
            var close = -1
            for (i in open until out.length) {
                if (out[i] == '{') depth++
                else if (out[i] == '}') { depth--; if (depth == 0) { close = i; break } }
            }
            if (close < 0) break
            val id = "__n${serial++}"
            val delta = when (update) {
                "++" -> "1"
                "--" -> "-1"
                "+=" -> "($step)"
                else -> "-($step)"
            }
            val header = "for (int $id = 0; $id < 256; $id++) { int $name = ($start) + ($delta) * $id; if (!($name $op $end)) break; "
            out = out.substring(0, match.range.first) + header + out.substring(open + 1, close + 1) + out.substring(close + 1)
            searchAt = match.range.first + header.length
        }
        return out
    }

    private val ReservedUniforms = setOf(
        "image", "amSize", "amTime", "amDuration", "resolution", "coord", "layer", "fromLayer", "toLayer", "orient",
        "paletteSize", "paletteSpread", "swatches", "glyphCount", "glyphColumns", "glyphCell", "visible", "screen",
        "beat", "beatTime", "beatIndex", "extent", "backdrop", "below", "bright", "blurred", "thresholds", "palette", "glyphs",
        "acScreenSize", "acLayerSize", "acLayerCenter", "acLayerCenterNorm", "acLayerPivot", "acLayerToScreen", "acScreenToLayer",
        "acPreviewSize", "acLayerSizeNorm", "acVelocity", "acScaleVelocity", "acAngularVelocity", "acProjectSize", "acShowGuides",
    )

    private fun mapCategory(category: String) = when (category) {
        "blur" -> "blur"; "color" -> "color"; "distort" -> "distort"; "procedural" -> "stylize"; "3d" -> "3d"
        "text" -> "text"; "repeat" -> "repeat"; "matte" -> "key"; "transform" -> "move"; "opacity" -> "color"
        else -> "other"
    }

    private fun format(v: Float) = String.format(java.util.Locale.US, "%.6f", v)
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
