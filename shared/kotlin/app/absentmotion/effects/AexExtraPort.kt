package app.absentmotion.effects

/**
 * Android ports for AEX plug-ins whose embedded shader programs and parameter
 * sets are readable. These are effects in their own right: each parameter maps
 * to a real uniform in the generated SKSL rather than to a built-in lookalike.
 */
object AexExtraPort {
    fun isDeepGlow(name: String, text: String): Boolean =
        name.contains("deep glow", true) || name.contains("deepglow", true) || text.contains("Deep Glow", true) ||
            (text.contains("thresholdSmooth") && text.contains("DeepGlow2"))

    fun deepGlow(id: String, key: String, name: String, text: String): EffectDefinition = EffectDefinition(
        id = id, key = key, name = name.ifBlank { "Deep Glow" }, category = "stylize",
        description = "Deep Glow AEX port: thresholded multi-tap glow with real exposure, tint, blend, threshold and aberration controls.",
        params = listOf(
            choice("glowMode", "Glow Mode", 0, listOf("Classic", "Physical")),
            choice("blendMode", "Blend Mode", 0, listOf("Screen", "Add")),
            pixels("radius", "Radius", 80f, 0f, 600f, 1f),
            slider("exposure", "Exposure", 1f, 0f, 8f, 0.01f),
            slider("threshold", "Threshold", 0.55f, 0f, 2f, 0.01f),
            slider("thresholdSmooth", "Threshold Smooth", 0.18f, 0f, 1f, 0.01f),
            choice("thresholdMode", "Threshold Mode", 1, listOf("Chroma", "Luma")),
            color("tint", "Tint", "#ffffffff"),
            slider("tintStrength", "Tint Strength", 0.4f, 0f, 1f, 0.01f),
            choice("tintMode", "Tint Mode", 0, listOf("Multiply", "Overlay", "Soft Light")),
            slider("aberration", "Glow Aberration", 0f, 0f, 20f, 0.1f),
            slider("sourceOpacity", "Source Opacity", 1f, 0f, 1f, 0.01f),
            choice("view", "View", 0, listOf("Final Result", "Glow Input")),
            toggle("simpleExposure", "Simplified Exposure", false),
        ),
        sksl = DEEP_GLOW, page = key,
    )

    fun isAutoSway(name: String, text: String): Boolean =
        name.contains("autosway", true) || text.contains("AutoSway", true) || (text.contains("Sway distance") && text.contains("AutoSway"))

    fun autoSway(id: String, key: String, name: String, text: String): EffectDefinition = EffectDefinition(
        id = id, key = key, name = name.ifBlank { "AutoSway" }, category = "move",
        description = "AutoSway port: a real-time sway warp with type, axle, distance, roundness, speed, decay, lag, offset, rotation and scale controls.",
        params = listOf(
            choice("swayType", "Sway Type", 0, listOf("Simple", "Orbit", "Pendulum", "Random", "Wind")),
            choice("axle", "Axle", 0, listOf("2D", "X", "Y")),
            pixels("distance", "Sway Distance", 18f, 0f, 200f, 0.5f),
            slider("roundness", "Sway Roundness", 0.6f, 0f, 1f, 0.01f),
            slider("speed", "Sway Speed", 0.8f, 0f, 8f, 0.01f),
            slider("decay", "Sway Decay", 0.1f, 0f, 2f, 0.01f),
            slider("offset", "Offset", 0f, -10f, 10f, 0.01f),
            slider("lag", "Lag Factor", 0.15f, 0f, 2f, 0.01f),
            slider("rotationSway", "Rotation Sway", 2f, 0f, 45f, 0.1f),
            slider("scaleSway", "Scale Sway", 0f, 0f, 1f, 0.01f),
        ),
        sksl = AUTO_SWAY, page = key,
    )

    fun isFlex(name: String, text: String): Boolean =
        name.contains("flex", true) || (text.contains("Flex") && text.contains("Corner") && text.contains("Shape"))

    fun flex(id: String, key: String, name: String, text: String): EffectDefinition = EffectDefinition(
        id = id, key = key, name = name.ifBlank { "Flex" }, category = "distort",
        description = "Flex shape port: flex, bend, taper, wave, skew and rounded-edge controls for shape layers.",
        params = listOf(
            pixels("flexWidth", "Width", 100f, 1f, 200f, 0.5f),
            pixels("flexHeight", "Height", 100f, 1f, 200f, 0.5f),
            slider("corner", "Corner Radius", 0f, 0f, 100f, 0.5f),
            slider("softness", "Edge Softness", 0.5f, 0f, 20f, 0.1f),
            slider("bend", "Bend", 0f, -100f, 100f, 0.5f),
            slider("taper", "Taper", 0f, -1f, 1f, 0.01f),
            slider("skew", "Skew", 0f, -1f, 1f, 0.01f),
            slider("wave", "Wave", 0f, 0f, 20f, 0.1f),
            slider("rotationFlex", "Rotation", 0f, -180f, 180f, 1f),
            pixels("offsetX", "Offset X", 0f, -100f, 100f, 0.5f),
            pixels("offsetY", "Offset Y", 0f, -100f, 100f, 0.5f),
        ),
        sksl = FLEX, page = key,
    )

    private fun slider(id: String, label: String, value: Float, min: Float, max: Float, step: Float) =
        EffectParam(id, label, "float", fmt(value), min, max, step)

    private fun pixels(id: String, label: String, value: Float, min: Float, max: Float, step: Float) =
        EffectParam(id, label, "float", fmt(value), min, max, step, pixels = true)

    private fun toggle(id: String, label: String, value: Boolean) =
        EffectParam(id, label, "switch", value.toString())

    private fun color(id: String, label: String, value: String) =
        EffectParam(id, label, "color", value)

    private fun choice(id: String, label: String, value: Int, options: List<String>) =
        EffectParam(id, label, "choice", value.toString(), 0f, (options.size - 1).toFloat(), 1f, options = options)

    private fun fmt(value: Float) = String.format(java.util.Locale.US, "%.4f", value)

    private const val DEEP_GLOW = """
uniform shader image;
uniform float2 size;
uniform float time;
uniform float radius;
uniform float exposure;
uniform float threshold;
uniform float thresholdSmooth;
uniform float thresholdMode;
uniform float blendMode;
uniform float4 tint;
uniform float tintStrength;
uniform float tintMode;
uniform float aberration;
uniform float sourceOpacity;
uniform float view;
uniform float simpleExposure;
uniform float glowMode;

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

half4 main(float2 coord) {
    half4 src = image.eval(coord);
    float3 base = float3(src.rgb);
    float3 sum = float3(0.0);
    float weightSum = 0.0;
    float smooth = max(thresholdSmooth, 0.001);
    for (int i = 0; i < 24; i++) {
        float fi = float(i);
        float angle = fi * 2.39996323;
        float reach = sqrt((fi + 0.5) / 24.0) * max(radius, 0.0);
        float2 p = coord + float2(cos(angle), sin(angle)) * reach;
        p = max(float2(0.0), min(size, p));
        float3 c = float3(image.eval(p).rgb);
        float mask = thresholdMode > 1.5
            ? smoothstep(threshold - smooth, threshold + smooth, luma(c))
            : smoothstep(threshold - smooth, threshold + smooth, max(c.r, max(c.g, c.b)));
        float w = exp(-2.2 * fi / 24.0);
        sum += c * mask * w;
        weightSum += w;
    }
    float3 glow = sum / max(weightSum, 0.0001);
    glow = glow * max(exposure, 0.0);
    if (tintStrength > 0.001) {
        float3 t = tint.rgb;
        float3 tinted = tintMode < 0.5 ? glow * t : (tintMode < 1.5 ? 1.0 - (1.0 - glow) * (1.0 - t) : mix(glow, t, 0.5));
        glow = mix(glow, tinted, tintStrength);
    }
    if (aberration > 0.001) {
        float a = aberration;
        glow.r = max(glow.r, float(image.eval(coord + float2(a, 0.0)).r));
        glow.b = max(glow.b, float(image.eval(coord - float2(a, 0.0)).b));
    }
    if (view > 0.5) return half4(glow, 1.0);
    float3 source = base * sourceOpacity;
    if (simpleExposure > 0.5) source *= exposure;
    float3 outColor = blendMode < 0.5 ? 1.0 - (1.0 - source) * (1.0 - glow) : source + glow;
    return half4(outColor, src.a);
}
"""

    private const val AUTO_SWAY = """
uniform shader image;
uniform float2 size;
uniform float time;
uniform float swayType;
uniform float axle;
uniform float distance;
uniform float roundness;
uniform float speed;
uniform float decay;
uniform float offset;
uniform float lag;
uniform float rotationSway;
uniform float scaleSway;

half4 main(float2 coord) {
    float2 uv = coord / size;
    float t = time * max(speed, 0.0) + offset - lag;
    float2 d = float2(0.0);
    if (swayType < 0.5) d = float2(sin(t), cos(t * 0.83));
    else if (swayType < 1.5) d = float2(cos(t), sin(t * 1.17));
    else if (swayType < 2.5) d = float2(sin(t) * roundness, sin(t * 2.0) * 0.35);
    else if (swayType < 3.5) d = float2(sin(t * 1.31 + sin(t * 0.71)), cos(t * 0.93 + sin(t * 0.37)));
    else d = float2(sin(t * 0.55) + sin(t * 1.7) * 0.35, cos(t * 0.41));
    if (axle > 0.5 && axle < 1.5) d.y = 0.0;
    if (axle > 1.5) d.x = 0.0;
    float damp = 1.0 / (1.0 + max(decay, 0.0) * abs(sin(t * 0.5)));
    float2 shift = d * distance * damp;
    float2 centre = size * 0.5;
    float2 p = coord - centre;
    float angle = sin(t) * rotationSway * 0.0174532925;
    float ca = cos(angle);
    float sa = sin(angle);
    p = float2(p.x * ca - p.y * sa, p.x * sa + p.y * ca);
    float scale = 1.0 + sin(t * 0.7) * scaleSway * 0.08;
    p /= max(scale, 0.1);
    p += centre + shift;
    p = max(float2(0.0), min(size, p));
    half4 c = image.eval(p);
    return half4(c.rgb, c.a);
}
"""

    private const val FLEX = """
uniform shader image;
uniform float2 size;
uniform float time;
uniform float flexWidth;
uniform float flexHeight;
uniform float corner;
uniform float softness;
uniform float bend;
uniform float taper;
uniform float skew;
uniform float wave;
uniform float rotationFlex;
uniform float offsetX;
uniform float offsetY;

half4 main(float2 coord) {
    float2 uv = coord / size;
    float2 centre = float2(0.5, 0.5) + float2(offsetX / 200.0, offsetY / 200.0);
    float2 p = uv - centre;
    float angle = -rotationFlex * 0.0174532925;
    float ca = cos(angle);
    float sa = sin(angle);
    p = float2(p.x * ca - p.y * sa, p.x * sa + p.y * ca);
    float2 halfSize = float2(max(flexWidth, 1.0) / 200.0, max(flexHeight, 1.0) / 200.0);
    float2 q = abs(p) / halfSize;
    float r = clamp(corner / 100.0, 0.0, 1.0);
    float2 d = q - (1.0 - r);
    float sd = length(max(d, float2(0.0))) + min(max(d.x, d.y), 0.0) - r;
    float mask = 1.0 - smoothstep(0.0, max(softness, 0.001) * 0.01, sd);
    p.x += sin((p.y + time * wave * 0.03) * 8.0) * bend * 0.002;
    p.x += p.y * skew * 0.35;
    p.x *= 1.0 + p.y * taper;
    float2 sample = clamp((centre + p) * size, float2(0.0), size);
    half4 c = image.eval(sample);
    return half4(c.rgb, c.a * mask);
}
"""
}
