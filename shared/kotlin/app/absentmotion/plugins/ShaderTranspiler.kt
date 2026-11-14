package app.absentmotion.plugins

/**
 * Import-time translators for shader source that is not GLSL.  These cover the
 * common single-image effect shape used by plug-in packages; they never run in
 * the render loop.  Unsupported constructs return null and fall back to the
 * semantic effect families instead of producing a broken SkSL program.
 */
internal object ShaderTranspiler {
    fun translate(source: String, params: List<app.absentmotion.effects.EffectParam>): String? {
        val s = source.replace("\r\n", "\n")
        return when {
            s.contains("@fragment") || s.contains("textureSample(") -> wgsl(s, params)
            s.contains("SV_Target") || s.contains("Texture2D<") || s.contains("Texture2D ") || s.contains("SamplerState") ||
                s.contains("float4 main(") -> hlsl(s, params)
            else -> null
        }
    }

    private fun hlsl(source: String, params: List<app.absentmotion.effects.EffectParam>): String? {
        var s = source
        s = s.replace(Regex("""^\s*(?:Texture2D|Texture2DArray|TextureCube|SamplerState|SamplerComparisonState)\b[^;]*;""", RegexOption.MULTILINE), "")
        s = s.replace(Regex("""^\s*cbuffer\b[\s\S]*?\{[\s\S]*?\}\s*;""", RegexOption.MULTILINE), "")
        s = s.replace(Regex("""^\s*(?:static\s+)?(?:const\s+)?(?:float|float2|float3|float4|int|uint|bool)\s+\w+\s*:\s*register\([^)]*\)\s*;""", RegexOption.MULTILINE), "")
        s = s.replace(Regex("""\bTexture2D(?:<[^>]*>)?\b"""), "shader")
        s = s.replace(Regex("""\bSamplerState\b"""), "shader")
        s = s.replace(Regex("""float4\s+main\s*\(([^)]*)\)\s*:\s*SV_Target"""), "half4 main($1)")
        s = s.replace(Regex("""\bfloat4\s+main\s*\(([^)]*)\)"""), "half4 main($1)")
        s = s.replace(Regex("""\bfloat2\s+main\s*\(([^)]*)\)"""), "half4 main($1)")
        s = s.replace(Regex("""\bmain\s*\(\s*(?:float2|float3|float4)\s+(\w+)\s*:\s*TEXCOORD0\s*\)"""), "main(float2 $1)")
        s = s.replace(Regex("""\b(\w+)\.Sample\s*\(\s*\w+\s*,\s*([^)]*)\)""")) { match ->
            "image.eval((${match.groupValues[2]}) * size)"
        }
        s = s.replace(Regex("""\btex2D\s*\(\s*\w+\s*,\s*([^)]*)\)""")) { "image.eval((${it.groupValues[1]}) * size)" }
        s = s.replace(Regex("""\breturn\s+([^;]+);"""), "return $1;")
        if (!s.contains("half4 main")) return null
        if (s.contains("SV_Target") || s.contains("Texture2D") || s.contains("SamplerState") || s.contains("Sample(")) return null
        return header(params) + s
    }

    private fun wgsl(source: String, params: List<app.absentmotion.effects.EffectParam>): String? {
        var s = source
        s = s.replace(Regex("""@group\(\d+\)\s*@binding\(\d+\)\s*var\s+\w+\s*:\s*texture_2d<[^>]+>\s*;"""), "")
        s = s.replace(Regex("""@group\(\d+\)\s*@binding\(\d+\)\s*var\s+\w+\s*:\s*sampler\s*;"""), "")
        s = s.replace(Regex("""@fragment\s+fn\s+main\s*\(\s*@location\(\d+\)\s*(\w+)\s*:\s*vec2<f32>\s*\)\s*->\s*@location\(\d+\)\s*vec4<f32>"""),
            "half4 main(float2 $1)")
        s = s.replace(Regex("""@fragment\s+fn\s+main\s*\(\s*(\w+)\s*:\s*vec2<f32>\s*\)\s*->\s*vec4<f32>"""), "half4 main(float2 $1)")
        s = s.replace(Regex("""\bvec2<f32>"""), "float2").replace(Regex("""\bvec3<f32>"""), "float3")
            .replace(Regex("""\bvec4<f32>"""), "float4").replace(Regex("""\bmat2x2<f32>"""), "float2x2")
            .replace(Regex("""\bmat3x3<f32>"""), "float3x3").replace(Regex("""\bmat4x4<f32>"""), "float4x4")
        s = s.replace(Regex("""\bf32\b"""), "float").replace(Regex("""\bi32\b"""), "int").replace(Regex("""\bu32\b"""), "int")
        s = s.replace(Regex("""\blet\s+(\w+)\s*:\s*float\s*=\s*"""), "float $1 = ")
        s = s.replace(Regex("""\bvar\s+(\w+)\s*:\s*float\s*=\s*"""), "float $1 = ")
        s = s.replace(Regex("""textureSample\s*\(\s*\w+\s*,\s*\w+\s*,\s*([^)]*)\)""")) { "image.eval((${it.groupValues[1]}) * size)" }
        s = s.replace(Regex("""\breturn\s+vec4<f32>"""), "return float4")
        s = s.replace(Regex("""\breturn\s+([^;]+);"""), "return $1;")
        if (!s.contains("half4 main") || s.contains("@fragment") || s.contains("textureSample")) return null
        return header(params) + s
    }

    private fun header(params: List<app.absentmotion.effects.EffectParam>): String = buildString {
        appendLine("uniform shader image;")
        appendLine("uniform float2 size;")
        appendLine("uniform float time;")
        for (p in params) {
            val type = when (p.kind) { "point" -> "float2"; "color" -> "float4"; else -> "float" }
            appendLine("uniform $type ${p.id};")
        }
    }
}
