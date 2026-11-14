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
        val sksl = shader?.let { translate(it, params) }
        val name = Regex("""<locale lang="en">[\s\S]*?<string name="name">([^<]+)</string>""").find(xml)?.groupValues?.get(1)
            ?: id.substringAfterLast('.').replace('-', ' ').replaceFirstChar { it.uppercase() }
        val description = Regex("""<locale lang="en">[\s\S]*?<string name="desc">([^<]+)</string>""").find(xml)?.groupValues?.get(1)
            ?: "Alight Motion effect"
        val category = Regex("""<effect\s+[^>]*category="([^"]+)""" + "\"").find(xml)?.groupValues?.get(1) ?: "other"
        val key = "am-" + id.substringAfterLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "") + "-" + digest(id).take(4)
        val status = if (unsupported || sksl == null) "unsupported" else "ok"
        val note = if (status == "ok") description else "$description · Requires layer/multi-pass shader support"
        return EffectDefinition(id, key, name, mapCategory(category), note, params, sksl, status = status, alsoIn = listOf("external"), page = key)
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
            for (p in params) {
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
