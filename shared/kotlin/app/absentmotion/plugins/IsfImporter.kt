package app.absentmotion.plugins

import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Reads the Interactive Shader Format used by ISF4AE and the wider ISF
 * ecosystem.  An ISF file is a JSON header in a block comment followed by a
 * GLSL fragment shader.  Input metadata becomes native effect parameters; the
 * shader is translated into the same SkSL form used by every other Schnitt
 * effect, so it participates in keyframes, preview and export.
 */
object IsfImporter {
    private val header = Regex("""/\*\s*(\{[\s\S]*?\})\s*\*/""")

    fun parse(text: String, id: String, key: String, name: String): EffectDefinition? {
        val json = header.find(text)?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val inputs = json.optJSONArray("INPUTS") ?: JSONArray()
        val params = ArrayList<EffectParam>()
        var hasImage = false
        for (i in 0 until inputs.length()) {
            val input = inputs.optJSONObject(i) ?: continue
            val rawName = input.optString("NAME").takeIf { it.isNotBlank() } ?: continue
            val type = input.optString("TYPE", "float").lowercase(Locale.US)
            if (type == "image") { hasImage = true; continue }
            val nameId = rawName.replace(Regex("[^A-Za-z0-9_]"), "_")
            val label = input.optString("LABEL").takeIf { it.isNotBlank() } ?: rawName.replace('_', ' ')
            val min = input.optDouble("MIN", Double.NEGATIVE_INFINITY).toFloat()
            val max = input.optDouble("MAX", Double.POSITIVE_INFINITY).toFloat()
            val unit = input.optString("UNIT").lowercase(Locale.US)
            when (type) {
                "bool" -> params += EffectParam(nameId, label, "switch", if (input.optBoolean("DEFAULT", false)) "true" else "false")
                "long" -> {
                    val values = input.optJSONArray("VALUES")?.let { a -> List(a.length()) { a.opt(it).toString() } }.orEmpty()
                    if (values.isNotEmpty()) {
                        params += EffectParam(nameId, label, "choice", input.optInt("DEFAULT", 0).coerceIn(0, values.lastIndex).toString(),
                            0f, (values.size - 1).toFloat(), 1f, options = values)
                    } else {
                        params += EffectParam(nameId, label, "float", input.optInt("DEFAULT", 0).toString(), min, max, 1f)
                    }
                }
                "point2d", "point" -> {
                    val d = input.optJSONArray("DEFAULT")
                    val x = d?.optDouble(0, 0.0) ?: 0.0
                    val y = d?.optDouble(1, 0.0) ?: 0.0
                    params += EffectParam(nameId, label, "point", "${f(x)},${f(y)}")
                }
                "color" -> {
                    val d = input.optJSONArray("DEFAULT")
                    val r = d?.optDouble(0, 1.0) ?: 1.0
                    val g = d?.optDouble(1, 1.0) ?: 1.0
                    val b = d?.optDouble(2, 1.0) ?: 1.0
                    val a = d?.optDouble(3, 1.0) ?: 1.0
                    params += EffectParam(nameId, label, "color", color(r, g, b, a))
                }
                else -> {
                    val def = input.optDouble("DEFAULT", 0.0)
                    val percent = unit == "percent"
                    params += EffectParam(nameId, label, if (unit == "angle" || unit == "direction") "angle" else "float",
                        f(def), min, max, if (unit == "angle" || unit == "direction") 1f else 0.01f, percent = percent)
                }
            }
        }
        val shader = text.substring(header.find(text)?.range?.last?.plus(1) ?: 0)
        val sksl = translate(shader, params) ?: return null
        return EffectDefinition(
            id = id, key = key, name = name.ifBlank { "ISF shader" }, category = "custom",
            description = json.optString("DESCRIPTION").takeIf { it.isNotBlank() } ?: "Interactive Shader Format effect imported from the plug-in package.",
            params = params, sksl = sksl, status = "ok", page = key,
        )
    }

    private fun translate(source: String, params: List<EffectParam>): String? {
        var s = source.replace("\r\n", "\n")
        s = s.replace(Regex("""^\s*#(?:version|extension|pragma).*$""", RegexOption.MULTILINE), "")
        s = s.replace(Regex("""^\s*precision\s+.*$""", RegexOption.MULTILINE), "")
        s = s.replace(Regex("""^\s*uniform\s+[^;]+;""", RegexOption.MULTILINE), "")
        s = s.replace(Regex("""\bvec2\b"""), "float2").replace(Regex("""\bvec3\b"""), "float3").replace(Regex("""\bvec4\b"""), "float4")
        s = s.replace(Regex("""\bivec2\b"""), "int2").replace(Regex("""\bivec3\b"""), "int3").replace(Regex("""\bivec4\b"""), "int4")
        s = s.replace(Regex("""\bmat2\b"""), "float2x2").replace(Regex("""\bmat3\b"""), "float3x3").replace(Regex("""\bmat4\b"""), "float4x4")
        s = s.replace(Regex("""\b(?:highp|mediump|lowp)\b"""), "")
        s = s.replace("RENDERSIZE", "size").replace("TIME", "time")
        s = s.replace("TIMEDELTA", "(1.0/30.0)").replace("FRAMEINDEX", "(time*30.0)")
        s = s.replace(Regex("""\bgl_FragCoord\.xy\b"""), "coord")
        s = s.replace("isf_FragNormCoord", "(coord / size)").replace("isf_FragCoord", "coord")
        s = Regex("""IMG_THIS_PIXEL\s*\(\s*\w+\s*\)""").replace(s, "image.eval(coord)")
        s = Regex("""IMG_THIS_NORM_PIXEL\s*\(\s*\w+\s*\)""").replace(s, "image.eval(coord)")
        s = Regex("""IMG_NORM_PIXEL\s*\(\s*\w+\s*,\s*([^)]*)\)""").replace(s) { "image.eval((${it.groupValues[1]}) * size)" }
        s = Regex("""IMG_PIXEL\s*\(\s*\w+\s*,\s*([^)]*)\)""").replace(s) { "image.eval(${it.groupValues[1]})" }
        val imageNames = Regex("""(?:texture2D|texture)\s*\(\s*(\w+)\s*,""").findAll(s).map { it.groupValues[1] }.toSet()
        s = Regex("""(?:texture2D|texture)\s*\(\s*\w+\s*,\s*([^)]*)\)""").replace(s) { "image.eval((${it.groupValues[1]}) * size)" }
        if (s.contains("texture2D") || s.contains("texture(")) return null
        s = s.replace(Regex("""void\s+main\s*\(\s*(?:void)?\s*\)"""), "half4 main(float2 coord)")
        s = s.replace(Regex("""gl_FragColor\s*=\s*([^;]+);"""), "return $1;")
        s = s.replace(Regex("""\breturn\s*;"""), "return half4(0.0);")
        if (!s.contains("half4 main") || !s.contains("return")) return null
        val header = buildString {
            if (imageNames.isNotEmpty() || !s.contains("uniform shader image")) appendLine("uniform shader image;")
            appendLine("uniform float2 size;")
            appendLine("uniform float time;")
            for (p in params) {
                val type = when (p.kind) { "point" -> "float2"; "color" -> "float4"; else -> "float" }
                appendLine("uniform $type ${p.id};")
            }
        }
        return header + s
    }

    private fun color(r: Double, g: Double, b: Double, a: Double): String =
        String.format(Locale.US, "#%02x%02x%02x%02x", (a * 255).toInt().coerceIn(0, 255), (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255), (b * 255).toInt().coerceIn(0, 255))

    private fun f(value: Double): String = String.format(Locale.US, "%.6f", value)
}
