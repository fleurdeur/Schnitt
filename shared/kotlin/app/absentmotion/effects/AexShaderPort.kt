package app.absentmotion.effects


/**
 * Real ports for AEX plug-ins whose algorithms can be rebuilt from the shader
 * and parameter data embedded in the binary. Tracery is a marker/keying
 * overlay: it finds keyed regions, draws boxes, markers, connections and
 * arrows. The original uses a CPU-side bounding-box table; Android gets the
 * same visible behaviour from a self-contained SKSL detector.
 */
object AexShaderPort {
    fun isTracery(name: String, text: String): Boolean =
        name.contains("tracery", true) || (text.contains("BOX_BORDER_COLOR") && text.contains("CONNECTION_LINE_COLOR") && text.contains("bboxes"))

    fun tracery(id: String, key: String, name: String, text: String): EffectDefinition {
        val params = listOf(
            color("keyColor", "Key Color", "#ff00ff00"),
            slider("threshold", "Threshold", 20f, 0f, 100f, 0.1f),
            toggle("showMask", "Show Mask", false),
            slider("blurStrength", "Blur Strength", 1f, 0f, 20f, 0.1f),
            toggle("alphaLayerMode", "Alpha Layer", false),
            choice("detectionQuality", "Detection Quality", 2, listOf("Extreme", "High", "Standard", "Medium", "Low", "Draft")),
            toggle("BOX_ENABLED", "Enable Bounding Boxes", true),
            color("BOX_BORDER_COLOR", "Border Color", "#ffffffff"),
            pixels("BOX_BORDER_THICKNESS", "Border Thickness", 2f, 0f, 30f, 0.5f),
            color("BOX_FILL_COLOR", "Fill Color", "#33000000"),
            slider("BOX_FILL_OPACITY", "Fill Opacity", 0.12f, 0f, 1f, 0.01f),
            toggle("BOX_ENABLE_GAP", "Enable Gaps", false),
            slider("BOX_GAP_SIZE", "Gap Size", 0.15f, 0f, 0.9f, 0.01f),
            toggle("TEXT_ENABLED", "Enable Text", true),
            color("TEXT_COLOR", "Text Color", "#ffffffff"),
            slider("TEXT_SCALE", "Font Scale", 1f, 0.2f, 5f, 0.05f),
            pixels("TEXT_VERTICAL_OFFSET", "Text Vertical Offset", 0f, -120f, 120f, 1f),
            toggle("MARKER_ENABLED", "Enable Markers", true),
            choice("MARKER_TYPE", "Marker Type", 1, listOf("Dot", "Plus", "Cross")),
            color("MARKER_COLOR", "Marker Color", "#ffffffff"),
            pixels("MARKER_SIZE", "Marker Size", 10f, 1f, 120f, 0.5f),
            pixels("MARKER_LINE_THICKNESS", "Marker Line Thickness", 2f, 0.5f, 20f, 0.5f),
            toggle("CONNECTION_LINE_ENABLED", "Enable Connection Lines", true),
            choice("CONNECTION_TYPE", "Connection Type", 0, listOf("Sequential", "Star", "Full", "MST")),
            color("CONNECTION_LINE_COLOR", "Line Color", "#ccffffff"),
            slider("CONNECTION_LINE_OPACITY", "Line Opacity", 0.8f, 0f, 1f, 0.01f),
            pixels("CONNECTION_LINE_THICKNESS", "Line Thickness", 2f, 0.5f, 20f, 0.5f),
            slider("CONNECTION_BEZIER_CURVATURE", "Curvature", 0.25f, 0f, 1f, 0.01f),
            toggle("CONNECTION_ENABLE_DASH", "Enable Dashed Lines", false),
            pixels("CONNECTION_LINE_DASH_LENGTH", "Dash Length", 10f, 1f, 100f, 0.5f),
            pixels("CONNECTION_LINE_GAP_LENGTH", "Gap Length", 8f, 1f, 100f, 0.5f),
            toggle("ARROW_ENABLED", "Enable Arrows", true),
            pixels("ARROW_SIZE", "Arrow Size", 12f, 2f, 100f, 0.5f),
            slider("ARROW_ANGLE", "Arrow Angle", 30f, 5f, 89f, 1f),
            slider("ARROW_POSITION", "Arrow Position", 1f, 0f, 1f, 0.01f),
            toggle("ARROW_FILLED", "Filled Arrows", true),
        )
        return EffectDefinition(
            id = id,
            key = key,
            name = name.ifBlank { "Tracery" },
            category = "stylize",
            description = "Marker tracking overlay ported from the Tracery AEX. It keys the chosen colour, finds marker regions, and draws boxes, labels, centre markers, connections and arrows in real time.",
            params = params,
            sksl = TRACERY,
            page = key,
        )
    }

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

    private const val TRACERY = """
uniform shader image;
uniform float2 size;
uniform float time;

uniform float4 keyColor;
uniform float threshold;
uniform float showMask;
uniform float blurStrength;
uniform float alphaLayerMode;
uniform float detectionQuality;

uniform float BOX_ENABLED;
uniform float4 BOX_BORDER_COLOR;
uniform float BOX_BORDER_THICKNESS;
uniform float4 BOX_FILL_COLOR;
uniform float BOX_FILL_OPACITY;
uniform float BOX_ENABLE_GAP;
uniform float BOX_GAP_SIZE;

uniform float TEXT_ENABLED;
uniform float4 TEXT_COLOR;
uniform float TEXT_SCALE;
uniform float TEXT_VERTICAL_OFFSET;

uniform float MARKER_ENABLED;
uniform float MARKER_TYPE;
uniform float4 MARKER_COLOR;
uniform float MARKER_SIZE;
uniform float MARKER_LINE_THICKNESS;

uniform float CONNECTION_LINE_ENABLED;
uniform float CONNECTION_TYPE;
uniform float4 CONNECTION_LINE_COLOR;
uniform float CONNECTION_LINE_OPACITY;
uniform float CONNECTION_LINE_THICKNESS;
uniform float CONNECTION_BEZIER_CURVATURE;
uniform float CONNECTION_ENABLE_DASH;
uniform float CONNECTION_LINE_DASH_LENGTH;
uniform float CONNECTION_LINE_GAP_LENGTH;

uniform float ARROW_ENABLED;
uniform float ARROW_SIZE;
uniform float ARROW_ANGLE;
uniform float ARROW_POSITION;
uniform float ARROW_FILLED;

float lineDist(float2 p, float2 a, float2 b, float thickness) {
    float2 ab = b - a;
    float len2 = max(dot(ab, ab), 0.0001);
    float t = clamp(dot(p - a, ab) / len2, 0.0, 1.0);
    return length(p - (a + ab * t)) - thickness;
}

float boxDist(float2 p, float2 c, float2 halfSize) {
    float2 d = abs(p - c) - halfSize;
    return max(d.x, d.y);
}

float rectMask(float2 p, float2 c, float2 halfSize) {
    float2 d = abs(p - c);
    return step(d.x, halfSize.x) * step(d.y, halfSize.y);
}

float lineMask(float2 p, float2 a, float2 b, float thickness) {
    return 1.0 - smoothstep(-1.0, 0.5, lineDist(p, a, b, thickness));
}

float dashMask(float2 p, float2 a, float2 b, float thickness) {
    if (CONNECTION_ENABLE_DASH < 0.5) return lineMask(p, a, b, thickness);
    float2 ab = b - a;
    float len = max(length(ab), 0.001);
    float along = clamp(dot(p - a, ab) / (len * len), 0.0, 1.0) * len;
    float period = max(CONNECTION_LINE_DASH_LENGTH + CONNECTION_LINE_GAP_LENGTH, 1.0);
    float phase = along - floor(along / period) * period;
    return lineMask(p, a, b, thickness) * step(phase, CONNECTION_LINE_DASH_LENGTH);
}

float segment(float2 p, float2 o, float2 s) {
    return rectMask(p, o + s * 0.5, float2(abs(s.x), abs(s.y)) * 0.5);
}

float digitMask(float2 p, float2 origin, float scale, int digit) {
    float x = p.x - origin.x;
    float y = p.y - origin.y;
    if (x < -scale || y < -scale || x > 4.0 * scale || y > 7.0 * scale) return 0.0;
    float t = max(scale * 0.8, 0.8);
    float2 q = float2(x, y);
    int m = 0;
    if (digit == 0) m = 119;
    else if (digit == 1) m = 36;
    else if (digit == 2) m = 93;
    else if (digit == 3) m = 109;
    else if (digit == 4) m = 46;
    else if (digit == 5) m = 107;
    else if (digit == 6) m = 123;
    else if (digit == 7) m = 37;
    else if (digit == 8) m = 127;
    else if (digit == 9) m = 111;
    float hit = 0.0;
    if ((m & 1) != 0) hit = max(hit, segment(q, float2(scale, 0.0), float2(scale * 2.0, t)));
    if ((m & 2) != 0) hit = max(hit, segment(q, float2(scale * 3.0, 0.0), float2(t, scale * 2.0)));
    if ((m & 4) != 0) hit = max(hit, segment(q, float2(scale * 3.0, scale * 3.0), float2(t, scale * 2.0)));
    if ((m & 8) != 0) hit = max(hit, segment(q, float2(scale, scale * 5.0), float2(scale * 2.0, t)));
    if ((m & 16) != 0) hit = max(hit, segment(q, float2(0.0, scale * 3.0), float2(t, scale * 2.0)));
    if ((m & 32) != 0) hit = max(hit, segment(q, float2(0.0, 0.0), float2(t, scale * 2.0)));
    if ((m & 64) != 0) hit = max(hit, segment(q, float2(scale, scale * 3.0), float2(scale * 2.0, t)));
    return hit;
}

float markerMask(float2 p, float2 c) {
    float m = 0.0;
    float2 d = p - c;
    float s = MARKER_SIZE;
    float t = MARKER_LINE_THICKNESS;
    if (MARKER_TYPE < 0.5) {
        m = 1.0 - smoothstep(s * 0.55, s * 0.55 + 1.5, length(d));
    } else if (MARKER_TYPE < 1.5) {
        m = max(segment(d, float2(-s, -t * 0.5), float2(s * 2.0, t)), segment(d, float2(-t * 0.5, -s), float2(t, s * 2.0)));
    } else {
        float2 q = abs(d);
        m = max(segment(q, float2(-s, -t * 0.5), float2(s * 2.0, t)), segment(q, float2(-t * 0.5, -s), float2(t, s * 2.0)));
        m = max(m, max(segment(float2(q.x, -q.y), float2(-s, -t * 0.5), float2(s * 2.0, t)), segment(float2(q.x, -q.y), float2(-t * 0.5, -s), float2(t, s * 2.0))));
    }
    return m;
}

float arrowMask(float2 p, float2 from, float2 to) {
    if (ARROW_ENABLED < 0.5) return 0.0;
    float2 dir = to - from;
    float len = max(length(dir), 0.001);
    float2 n = dir / len;
    float2 tip = from + dir * ARROW_POSITION;
    float a = ARROW_ANGLE * 0.0174532925;
    float2 left = tip - (n * cos(a) - float2(-n.y, n.x) * sin(a)) * ARROW_SIZE;
    float2 right = tip - (n * cos(a) + float2(-n.y, n.x) * sin(a)) * ARROW_SIZE;
    float m = max(lineMask(p, tip, left, max(1.0, ARROW_SIZE * 0.06)), lineMask(p, tip, right, max(1.0, ARROW_SIZE * 0.06)));
    if (ARROW_FILLED > 0.5) {
        float2 e1 = left - tip, e2 = right - tip, ep = p - tip;
        float c1 = e1.x * ep.y - e1.y * ep.x;
        float c2 = e2.x * (p - right).y - e2.y * (p - right).x;
        float c3 = (tip - right).x * (p - right).y - (tip - right).y * (p - right).x;
        if (c1 >= 0.0 && c2 >= 0.0 && c3 >= 0.0) m = 1.0;
        if (c1 <= 0.0 && c2 <= 0.0 && c3 <= 0.0) m = 1.0;
    }
    return m;
}

half4 main(float2 coord) {
    half4 src = image.eval(coord);
    float2 cellSize = size / float2(10.0, 6.0);
    float2 halfBox = cellSize * 0.62;
    float border = max(BOX_BORDER_THICKNESS, 0.5);
    float maskSoft = max(blurStrength, 0.001) * 0.015;
    float keyT = threshold / 100.0;
    float quality = 1.0 - detectionQuality * 0.035;
    float4 graphics = float4(0.0);
    float graphicsA = 0.0;
    float2 prev = float2(-1.0);
    float2 first = float2(-1.0);
    int index = 0;
    float maskShow = 0.0;

    for (int gy = 0; gy < 6; gy++) {
        for (int gx = 0; gx < 10; gx++) {
            float2 c = (float2(gx, gy) + 0.5) * cellSize;
            half4 sampleColor = image.eval(c);
            float d = length(float3(sampleColor.rgb) - keyColor.rgb);
            float m = 1.0 - smoothstep(keyT - maskSoft, keyT + maskSoft, d);
            m = step(0.5 - detectionQuality * 0.045, m);
            maskShow = max(maskShow, m);
            if (m > 0.5) {
                if (BOX_ENABLED > 0.5) {
                    float dd = boxDist(coord, c, halfBox);
                    float bm = 1.0 - smoothstep(0.0, border, abs(dd));
                    if (BOX_ENABLE_GAP > 0.5) {
                        float2 ad = abs(coord - c);
                        if (ad.x < halfBox.x * BOX_GAP_SIZE || ad.y < halfBox.y * BOX_GAP_SIZE) bm = 0.0;
                    }
                    if (BOX_FILL_OPACITY > 0.001) {
                        float fm = step(dd, 0.0) * BOX_FILL_OPACITY;
                        graphics.rgb = BOX_FILL_COLOR.rgb * fm + graphics.rgb * (1.0 - fm);
                        graphicsA = fm + graphicsA * (1.0 - fm);
                    }
                    float ba = bm * BOX_BORDER_COLOR.a;
                    graphics.rgb = BOX_BORDER_COLOR.rgb * ba + graphics.rgb * (1.0 - ba);
                    graphicsA = ba + graphicsA * (1.0 - ba);
                }
                if (MARKER_ENABLED > 0.5) {
                    float mm = markerMask(coord, c) * MARKER_COLOR.a;
                    graphics.rgb = MARKER_COLOR.rgb * mm + graphics.rgb * (1.0 - mm);
                    graphicsA = mm + graphicsA * (1.0 - mm);
                }
                if (TEXT_ENABLED > 0.5) {
                    int digits = (index % 100);
                    float scale = max(1.0, TEXT_SCALE * 2.2);
                    float2 topLeft = c + float2(halfBox.x * 0.25, halfBox.y * 0.45 + TEXT_VERTICAL_OFFSET);
                    float tm = max(digitMask(coord, topLeft, scale, digits / 10), digitMask(coord, topLeft + float2(scale * 5.0, 0.0), scale, digits % 10));
                    tm *= TEXT_COLOR.a;
                    graphics.rgb = TEXT_COLOR.rgb * tm + graphics.rgb * (1.0 - tm);
                    graphicsA = tm + graphicsA * (1.0 - tm);
                }
                if (CONNECTION_LINE_ENABLED > 0.5) {
                    float2 start = first.x < 0.0 ? c : (CONNECTION_TYPE < 1.5 ? prev : first);
                    if (start.x >= 0.0) {
                        float2 mid = (start + c) * 0.5 + normalize(float2(-(c - start).y, (c - start).x)) * length(c - start) * CONNECTION_BEZIER_CURVATURE * 0.35;
                        float cm = max(dashMask(coord, start, mid, CONNECTION_LINE_THICKNESS), dashMask(coord, mid, c, CONNECTION_LINE_THICKNESS));
                        if (CONNECTION_TYPE > 2.5) cm = max(cm, dashMask(coord, prev, c, CONNECTION_LINE_THICKNESS));
                        float la = cm * CONNECTION_LINE_COLOR.a * CONNECTION_LINE_OPACITY;
                        graphics.rgb = CONNECTION_LINE_COLOR.rgb * la + graphics.rgb * (1.0 - la);
                        graphicsA = la + graphicsA * (1.0 - la);
                        float am = arrowMask(coord, start, c) * CONNECTION_LINE_COLOR.a * CONNECTION_LINE_OPACITY;
                        graphics.rgb = CONNECTION_LINE_COLOR.rgb * am + graphics.rgb * (1.0 - am);
                        graphicsA = am + graphicsA * (1.0 - am);
                    }
                }
                if (first.x < 0.0) first = c;
                prev = c;
                index++;
            }
        }
    }
    if (showMask > 0.5) return half4(maskShow, maskShow, maskShow, 1.0);
    if (alphaLayerMode > 0.5) return half4(graphics.rgb, graphicsA);
    return half4(graphics.rgb + float3(src.rgb) * (1.0 - graphicsA), max(float(src.a), graphicsA));
}
"""
}
