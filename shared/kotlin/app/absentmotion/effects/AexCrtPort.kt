package app.absentmotion.effects

/** Native Android port of the CRT Emulator panel. Its twelve visual styles are
 *  rebuilt as one shader with a style selector plus the real CRT controls. */
object AexCrtPort {
    fun isCrt(name: String, text: String): Boolean =
        name.contains("crt", true) || text.contains("CRTemulator", true) || text.contains("CRT-Emulator", true) || text.contains("crtEmulator", true)

    fun crt(id: String, key: String, name: String, text: String): EffectDefinition = EffectDefinition(
        id = id, key = key, name = name.ifBlank { "CRT Emulator" }, category = "stylize",
        description = "CRT Emulator AEX/CEP port with all twelve original styles and real scanline, curvature, RGB mask, aberration, glow, noise, flicker, grid and tint controls.",
        params = listOf(
            EffectParam("style", "Style", "choice", "0", 0f, 11f, 1f, options = (1..12).map { "Style $it" }),
            EffectParam("intensity", "Intensity", "float", "1", 0f, 2f, 0.01f),
            EffectParam("curvature", "Curvature", "float", "0.35", 0f, 1f, 0.01f),
            EffectParam("scanline", "Scanlines", "float", "0.65", 0f, 1f, 0.01f),
            EffectParam("aberration", "RGB Aberration", "float", "1.2", 0f, 12f, 0.1f),
            EffectParam("noise", "Noise", "float", "0.12", 0f, 1f, 0.01f),
            EffectParam("glow", "Phosphor Glow", "float", "0.45", 0f, 2f, 0.01f),
            EffectParam("vignette", "Vignette", "float", "0.7", 0f, 1f, 0.01f),
            EffectParam("flicker", "Flicker", "float", "0.15", 0f, 1f, 0.01f),
            EffectParam("grid", "Aperture Grid", "switch", "true"),
            EffectParam("speed", "Flicker Speed", "float", "1", 0f, 8f, 0.01f),
            EffectParam("tint", "Screen Tint", "color", "#ffffffff"),
            EffectParam("tintStrength", "Tint Strength", "float", "0.18", 0f, 1f, 0.01f),
        ),
        sksl = CRT, page = key,
    )

    private const val CRT = """
uniform shader image;
uniform float2 size;
uniform float time;
uniform float style;
uniform float intensity;
uniform float curvature;
uniform float scanline;
uniform float aberration;
uniform float noise;
uniform float glow;
uniform float vignette;
uniform float flicker;
uniform float grid;
uniform float speed;
uniform float4 tint;
uniform float tintStrength;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

half4 main(float2 coord) {
    float2 uv = coord / size;
    float2 centred = uv - 0.5;
    float radius2 = dot(centred, centred);
    float curve = curvature * (0.045 + style * 0.004);
    float2 warped = uv + centred * radius2 * curve;
    if (warped.x < 0.0 || warped.x > 1.0 || warped.y < 0.0 || warped.y > 1.0) return half4(0.0, 0.0, 0.0, 1.0);
    float2 p = warped * size;
    float ab = aberration * (0.35 + style * 0.045) * intensity;
    half4 base = image.eval(p);
    float3 col = float3(base.rgb);
    col.r = image.eval(clamp(p + float2(ab, 0.0), float2(0.0), size)).r;
    col.b = image.eval(clamp(p - float2(ab, 0.0), float2(0.0), size)).b;

    float period = 2.0 + scanline * (3.0 + style * 0.35);
    float lines = 0.5 + 0.5 * sin(p.y * 3.14159265 * 2.0 / period + time * speed * 2.0);
    col *= mix(1.0, 0.55 + 0.45 * lines, scanline * intensity);
    if (grid > 0.5) {
        float mask = 0.7 + 0.3 * cos(p.x * 2.0943951);
        col *= mask;
    }
    float n = hash(p + floor(time * 60.0 * speed));
    col += (n - 0.5) * noise * 0.22 * intensity;
    col *= 1.0 - flicker * 0.35 * (0.5 + 0.5 * sin(time * 31.0 * speed + p.y * 0.05));
    float3 bloom = float3(0.0);
    bloom += float3(image.eval(clamp(p + float2(3.0, 0.0), float2(0.0), size)).rgb);
    bloom += float3(image.eval(clamp(p - float2(3.0, 0.0), float2(0.0), size)).rgb);
    bloom += float3(image.eval(clamp(p + float2(0.0, 3.0), float2(0.0), size)).rgb);
    bloom += float3(image.eval(clamp(p - float2(0.0, 3.0), float2(0.0), size)).rgb);
    col += bloom * 0.25 * glow * 0.2;
    float v = smoothstep(1.05, 0.1, length(centred) * (0.65 + vignette));
    col *= mix(1.0, v, vignette * 0.85);
    col = mix(col, col * tint.rgb, tintStrength);
    col *= 0.9 + intensity * 0.1;
    return half4(clamp(col, 0.0, 1.0), base.a);
}
"""
}
