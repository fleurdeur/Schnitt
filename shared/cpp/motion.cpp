#include "scene.h"
#include "include/core/SkSamplingOptions.h"
#include "include/effects/SkImageFilters.h"

#include "include/core/SkPathMeasure.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string_view>
#include <vector>

#include "include/core/SkColor.h"
#include "include/core/SkPathMeasure.h"

namespace am {
namespace {

constexpr double kTau = 6.283185307179586;

const char* const kMotionIds[] = {"absent.autoshake", "absent.randomdisplace", "absent.randomjitter", "absent.oscillate",
                                  "absent.growparts", "absent.morphpath", "absent.shakeparts", "absent.poseshake",
                                  "absent.vshake", "absent.vshakenew", "absent.transform2", "absent.vtransform", "absent.skexnxxposeshake",
                                  "absent.facemotion", "absent.perlindisplace",
                                  "absent.swing", "absent.spin", "absent.pulsesize", "absent.pulseopacity",
                                  "absent.echo", "absent.videofeedback", "absent.timequant", "absent.shutterblur", "absent.motionblur", "absent.movealongpath",
                                  "absent.scaleassist", "absent.parenthelper",
                                  "com.alightcreative.effects.oscillate", "com.alightcreative.effects.oscillate2",
                                  "com.alightcreative.effects.oscillate3", "com.alightcreative.effects.spin",
                                  "com.alightcreative.effects.pulsate", "com.alightcreative.effects.pulsate2",
                                  "com.alightcreative.effects.swing", "com.alightcreative.effects.swing2",
                                  "com.alightcreative.effects.jitter", "com.alightcreative.effects.randomdisplace",
                                  "com.alightcreative.effects.shake", "com.alightcreative.effects.shake2",
                                  "com.alightcreative.effects.scaleassist", "com.alightcreative.effects.blink",
                                  "com.alightcreative.effects.blink2", "com.alightcreative.effects.fade",
                                  "com.alightcreative.effects.flicker", "com.alightcreative.effects.flicker2",
                                  "com.alightcreative.effects.pulse-opacity", "com.alightcreative.effects.pulse_opacity2"};

thread_local bool gFastShutter = false;

const Anim* Find(const LayerEffect& e, const char* name) {
    for (const Property& p : e.props) {
        if (p.name == name) return &p.anim;
    }
    return nullptr;
}

float Param(const LayerEffect& e, const char* name, float t, float fallback, int i = 0) {
    const Anim* a = Find(e, name);
    return a ? a->get(i, t, fallback) : fallback;
}

double Integral(const LayerEffect& e, const char* name, const Layer& l, double seconds, float fallback) {
    const Anim* a = Find(e, name);
    if (!a) return fallback * seconds;
    const double length = (l.endTime - l.startTime) / 1000.0;
    if ((a->keys.empty() && a->links.empty()) || !(length > 0)) return a->get(0, 0, fallback) * seconds;
    auto f = [&](double s) { return (double)a->get(0, (float)(s / length), fallback); };
    std::vector<double> marks = {0, seconds};
    const auto mark = [&](double s) { if (s > 0 && s < seconds) marks.push_back(s); };
    for (size_t k = 0; k < a->keys.size(); k++) {
        const Keyframe& key = a->keys[k];
        mark(key.t * length);
        const double step = key.easing.p[0];
        if (k == 0 || key.easing.type != Easing::kStep || !(step > 0)) continue;
        const double from = a->keys[k - 1].t * length, span = key.t * length - from;
        const double rise = std::clamp((double)key.easing.p[1], 0.0, 1.0) * step;
        const bool reversed = key.easing.reversals & 1;
        for (int n = 1; n <= 256 && n * step - rise < 1; n++) {
            for (const double u : {n * step - rise, n * step}) {
                if (u > 0 && u < 1) mark(from + span * (reversed ? 1 - u : u));
            }
        }
    }
    std::sort(marks.begin(), marks.end());
    const double inside = length * 1e-6;
    double sum = 0;
    for (size_t i = 0; i + 1 < marks.size(); i++) {
        const double a0 = marks[i], b = marks[i + 1], h = (b - a0) / 8;
        if (!(h > 0)) continue;
        const double in = std::min(inside, h);
        double part = f(a0 + in) + f(b - in);
        for (int k = 1; k < 8; k++) part += (k % 2 ? 4 : 2) * f(a0 + k * h);
        sum += part * h / 3;
    }
    return sum;
}

uint64_t Mix(uint64_t x) {
    x += 0x9e3779b97f4a7c15ull;
    x = (x ^ (x >> 30)) * 0xbf58476d1ce4e5b9ull;
    x = (x ^ (x >> 27)) * 0x94d049bb133111ebull;
    return x ^ (x >> 31);
}

uint64_t SeedOf(const Layer& l, const LayerEffect& e, float t, uint64_t channel) {
    const int64_t seed = std::llround((double)Param(e, "seed", t, 1) * 1000);
    return Mix(Mix((uint64_t)(l.noiseId ? l.noiseId : l.id)) ^ Mix((uint64_t)seed + 0x51ed27ull) ^ (channel * 0x632be59bd9b4e019ull));
}

double Unit(uint64_t seed, int64_t k) { return (Mix(seed ^ Mix((uint64_t)k)) >> 11) * (1.0 / 9007199254740992.0); }

double Noise(double x, double y, bool standard = false) { return std::clamp(SimplexNoise2(x, y, standard), -1.0, 1.0); }

double Plane(float seed, int channel) { return seed * 7.919 + channel * 101.37 + 0.5; }

double Wave(double cycles, float kind) {
    const double f = cycles - std::floor(cycles);
    if (kind >= 0.5f) return 1 - 4 * std::fabs(std::fmod(f + 0.25, 1.0) - 0.5);
    return std::sin(kTau * f);
}

SkVector Displacement(const LayerEffect& e, float t, double drift, SkPoint at) {
    const float scatter = Param(e, "scatter", t, 0.5f) / 100, m = Param(e, "magnitude", t, 50);
    const double x = Param(e, "evolution", t, 0) + drift + scatter * at.fX;
    const double y = Plane(Param(e, "seed", t, 0), 0) + scatter * at.fY;
    return {(float)(m * Noise(x, y)), (float)(m * Noise(x + 57.31, y + 23.17))};
}

SkVector Toward(float degrees, double distance) {
    const double a = degrees * kTau / 360;
    return {(float)(std::cos(a) * distance), (float)(std::sin(a) * distance)};
}

double Seconds(const Layer& l, double timeMs) { return std::max(0.0, (l.steppedMs(timeMs) - l.startTime) / 1000.0); }

const char* const kRepeatIds[] = {"absent.repeat", "absent.linearrepeat", "absent.radialrepeat", "absent.gridrepeat",
                                  "absent.repeatalongpath", "absent.scatterfield", "absent.scatterrepeat"};

constexpr size_t kMaxCopies = 25000;

float ParamAt(const LayerEffect& e, const char* name, int i, float t, float fallback) {
    const Anim* a = Find(e, name);
    return a ? a->get(i, t, fallback) : fallback;
}

int CountOf(const LayerEffect& e, float t, float fallback, int most) {
    const float count = Param(e, "count", t, fallback);
    return std::isfinite(count) ? (int)std::lround(std::clamp(count, 0.0f, (float)most)) : 0;
}

SkColor4f ColourOf(const LayerEffect& e, const char* name, float t, SkColor4f fallback) {
    const Anim* a = Find(e, name);
    if (!a) return fallback;
    return {a->get(1, t, fallback.fR), a->get(2, t, fallback.fG), a->get(3, t, fallback.fB), a->get(0, t, fallback.fA)};
}

float Lerp(float a, float b, float u) { return a + (b - a) * u; }

struct Spot {
    SkPoint at = {0, 0};
    float turn = 0, size = 1, u = 0;
    SkMatrix outer = SkMatrix::I();
};

float Eased(int easing, float x) {
    switch (easing) {
        case 1: return x * x;
        case 2: return 1 - (1 - x) * (1 - x);
        case 3: return x * x * (3 - 2 * x);
        default: return x;
    }
}

void Ranged(const Layer& l, const LayerEffect& e, float t, std::vector<Spot> spots, std::vector<RepeatCopy>* out) {
    const SkPoint shift = {ParamAt(e, "rangePosition", 0, t, 0), ParamAt(e, "rangePosition", 1, t, 0)};
    const float scale = std::max(Param(e, "rangeScale", t, 1), 0.0f), turn = Param(e, "rangeRotation", t, 0);
    const float opacity = std::clamp(Param(e, "rangeOpacity", t, 1), 0.0f, 1.0f);
    const SkColor4f fill = ColourOf(e, "fillColor", t, {1, 1, 1, 1});
    const float fillAmount = std::clamp(Param(e, "fillAmount", t, 0), 0.0f, 1.0f);
    const float offset = Param(e, "rangeOffset", t, 0);
    const float start = Param(e, "rangeStart", t, 0) + offset, end = Param(e, "rangeEnd", t, 1) + offset;
    const int easing = (int)std::lround(Param(e, "easing", t, 0));
    if (Param(e, "randomOrder", t, 0) >= 0.5f && spots.size() > 1) {
        const uint64_t seed = SeedOf(l, e, t, 9);
        std::vector<size_t> order(spots.size());
        for (size_t i = 0; i < order.size(); i++) order[i] = i;
        std::sort(order.begin(), order.end(), [&](size_t a, size_t b) { return Unit(seed, (int64_t)a) < Unit(seed, (int64_t)b); });
        for (size_t k = 0; k < order.size(); k++) spots[order[k]].u = (float)k / (order.size() - 1);
    }
    const float delay = std::clamp(Param(e, "copyDelay", t, 0), -60.0f, 60.0f);
    const float randomX = std::clamp(ParamAt(e, "randomPosition", 0, t, 0), 0.0f, 99999.0f);
    const float randomY = std::clamp(ParamAt(e, "randomPosition", 1, t, 0), 0.0f, 99999.0f);
    const float randomTurn = std::clamp(Param(e, "randomRotation", t, 0), 0.0f, 3600.0f);
    const float randomScale = std::clamp(Param(e, "randomScale", t, 0), 0.0f, 10.0f);
    const float randomAlpha = std::clamp(Param(e, "randomOpacity", t, 0), 0.0f, 1.0f);
    const uint64_t seed = SeedOf(l, e, t, 21);
    if ((delay != 0 || randomX != 0 || randomY != 0 || randomTurn != 0 || randomScale != 0 || randomAlpha != 0) && spots.size() > 256) spots.resize(256);
    for (auto s = spots.rbegin(); s != spots.rend(); ++s) {
        const float x = end - start > 1e-6f ? std::clamp((s->u - start) / (end - start), 0.0f, 1.0f) : (s->u >= start ? 1.0f : 0.0f);
        const float w = Eased(easing, x);
        RepeatCopy c;
        c.outer = s->outer;
        const int64_t index = (int64_t)(s.base() - spots.begin() - 1);
        auto random = [&](int channel) { return (float)(Unit(seed, index * 5 + channel) * 2 - 1); };
        c.shiftMs = -delay * 1000.0 * index;
        c.local = SkMatrix::Translate(s->at.fX + shift.fX * w + randomX * random(0), s->at.fY + shift.fY * w + randomY * random(1));
        c.local.preRotate(s->turn + turn * w + randomTurn * random(2));
        const float k = s->size * Lerp(1, scale, w) * std::max(0.0f, 1 + randomScale * random(3));
        c.local.preScale(k, k);
        c.alpha = std::clamp(Lerp(1, opacity, w) + randomAlpha * random(4), 0.0f, 1.0f);
        c.tint = fill;
        c.tinted = fillAmount * w;
        out->push_back(c);
    }
}

float Along(int i, int n) { return n > 1 ? (float)i / (n - 1) : 0.5f; }

void Basic(const LayerEffect& e, float t, std::vector<RepeatCopy>* out) {
    const int n = CountOf(e, t, 10, 9999);
    SkMatrix each = SkMatrix::Translate(ParamAt(e, "offset", 0, t, 0), ParamAt(e, "offset", 1, t, 0));
    each.preRotate(Param(e, "angle", t, 0));
    const float scale = std::max(Param(e, "scale", t, 1), 0.0f);
    each.preScale(scale, scale);
    const float alpha = std::clamp(Param(e, "alpha", t, 1), 0.0f, 1.0f);
    const double step = std::max(Param(e, "time", t, 0), 0.0f) * 1000.0;
    std::vector<RepeatCopy> copies;
    copies.reserve(n);
    SkMatrix m = SkMatrix::I();
    for (int i = 0; i < n && m.isFinite(); i++) {
        RepeatCopy copy;
        copy.local = m;
        copy.shiftMs = -step * i;
        copy.alpha = n > 1 ? Lerp(1, alpha, (float)i / (n - 1)) : 1;
        copies.push_back(copy);
        m.preConcat(each);
    }
    out->insert(out->end(), copies.rbegin(), copies.rend());
}

void Linear(const LayerEffect& e, float t, std::vector<Spot>* spots) {
    const int n = CountOf(e, t, 5, 150);
    const SkPoint span = {ParamAt(e, "position", 0, t, 500), ParamAt(e, "position", 1, t, 0)};
    for (int i = 0; i < n; i++) {
        Spot s;
        s.u = Along(i, n);
        s.at = {(s.u - 0.5f) * span.fX, (s.u - 0.5f) * span.fY};
        if (n == 1) s.u = 0;
        spots->push_back(s);
    }
}

void Grid(const LayerEffect& e, float t, std::vector<Spot>* spots, int most) {
    const int n = CountOf(e, t, 5, most);
    const SkPoint span = {ParamAt(e, "position", 0, t, 500), ParamAt(e, "position", 1, t, 500)};
    const float stagger = Param(e, "stagger", t, 0);
    const int total = n * n;
    for (int row = 0; row < n; row++) {
        for (int col = 0; col < n; col++) {
            Spot s;
            s.at = {(Along(col, n) - 0.5f) * span.fX + (row % 2 ? stagger : 0), (Along(row, n) - 0.5f) * span.fY};
            s.u = total > 1 ? (float)(row * n + col) / (total - 1) : 0;
            spots->push_back(s);
        }
    }
}

void Radial(const LayerEffect& e, float t, std::vector<Spot>* spots) {
    const int n = CountOf(e, t, 8, 150);
    const float radius = Param(e, "radius", t, 300), orientation = Param(e, "orientation", t, 0);
    const float angle = Param(e, "angle", t, 0), sweep = Param(e, "sweep", t, 360);
    const float size = std::max(Param(e, "scale", t, 1), 0.0f);
    const bool round = std::fabs(sweep) >= 360 - 1e-3f;
    for (int i = 0; i < n; i++) {
        Spot s;
        const float a = angle + (n > 1 ? sweep * i / (round ? n : n - 1) : 0);
        const double r = a * kTau / 360;
        s.at = {(float)(std::sin(r) * radius), (float)(-std::cos(r) * radius)};
        s.turn = a + orientation;
        s.size = size;
        s.u = n > 1 ? (float)i / (n - 1) : 0;
        spots->push_back(s);
    }
}

void Scattered(const Layer& l, const LayerEffect& e, float t, std::vector<Spot>* spots) {
    const int n = CountOf(e, t, 20, 150);
    const float radius = std::max(Param(e, "radius", t, 300), 0.0f), evolution = Param(e, "evolution", t, 0);
    const uint64_t base = Mix(Mix((uint64_t)(l.noiseId ? l.noiseId : l.id)) ^ Mix((uint64_t)std::llround((double)Param(e, "scatterSeed", t, 0) * 1000) + 0x5ca7ull));
    for (int i = 0; i < n; i++) {
        Spot s;
        const double a = kTau * (Unit(base, i * 3) + evolution * (Unit(base, i * 3 + 1) * 2 - 1));
        const double r = radius * std::sqrt(Unit(base, i * 3 + 2));
        s.at = {(float)(std::cos(a) * r), (float)(std::sin(a) * r)};
        s.u = n > 1 ? (float)i / (n - 1) : 0;
        spots->push_back(s);
    }
}

void OnPath(const LayerEffect& e, float t, const RepeatSpace& space, std::vector<Spot>* spots) {
    const int n = CountOf(e, t, 25, 150);
    if (n == 0) return;
    float total = 0;
    int contours = 0;
    bool closed = false;
    SkPathMeasure measure(space.below, false);
    do {
        const float length = measure.getLength();
        if (!(length > 0)) continue;
        total += length;
        closed = ++contours == 1 && measure.isClosed();
    } while (measure.nextContour());
    if (!(total > 0)) {
        spots->push_back(Spot());
        return;
    }
    const float start = std::clamp(Param(e, "start", t, 0), 0.0f, 1.0f), end = std::clamp(Param(e, "end", t, 1), 0.0f, 1.0f);
    const float phase = Param(e, "phase", t, 0), span = end - start;
    const float spacing = n > 1 ? span / (closed && span >= 1 - 1e-4f ? n : n - 1) : 0;
    const bool tangent = Param(e, "tangent", t, 0) >= 0.5f;
    std::vector<std::pair<float, float>> placed;
    for (int i = 0; i < n; i++) {
        float f = start + phase + spacing * i;
        if (closed) f -= std::floor(f);
        else if (f < -1e-4f || f > 1 + 1e-4f) continue;
        placed.push_back({std::clamp(f, 0.0f, 1.0f) * total, n > 1 ? (float)i / (n - 1) : 0});
    }
    std::vector<size_t> order(placed.size());
    for (size_t i = 0; i < order.size(); i++) order[i] = i;
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) { return placed[a].first < placed[b].first; });
    std::vector<Spot> found(placed.size());
    std::vector<bool> ok(placed.size());
    SkPathMeasure contour(space.below, false);
    float contourStart = 0, contourLength = contour.getLength();
    for (size_t k : order) {
        const float d = placed[k].first;
        while (contourStart + contourLength < d - 1e-3f && contour.nextContour()) {
            contourStart += contourLength;
            contourLength = contour.getLength();
        }
        SkPoint p;
        SkVector v;
        if (!contour.getPosTan(std::clamp(d - contourStart, 0.0f, contourLength), &p, &v)) continue;
        Spot s;
        s.u = placed[k].second;
        s.outer = SkMatrix::Translate(p.fX, p.fY);
        if (tangent) s.outer.preRotate(std::atan2(v.fY, v.fX) * 360 / (float)kTau);
        s.outer.preTranslate(-space.anchor.fX, -space.anchor.fY);
        found[k] = s;
        ok[k] = true;
    }
    for (size_t k = 0; k < found.size(); k++) {
        if (ok[k]) spots->push_back(found[k]);
    }
}

SkColor4f Gradient3(const SkColor4f c[3], float u, bool hue) {
    u = std::clamp(u, 0.0f, 1.0f) * 2;
    const int i = std::min((int)u, 1);
    const float f = u - i;
    const SkColor4f a = c[i], b = c[i + 1];
    if (!hue) return {Lerp(a.fR, b.fR, f), Lerp(a.fG, b.fG, f), Lerp(a.fB, b.fB, f), 1};
    SkScalar ha[3], hb[3];
    SkColorToHSV(a.toSkColor(), ha);
    SkColorToHSV(b.toSkColor(), hb);
    float dh = hb[0] - ha[0];
    if (dh > 180) dh -= 360;
    if (dh < -180) dh += 360;
    SkScalar mixed[3] = {std::fmod(ha[0] + dh * f + 360, 360.0f), Lerp(ha[1], hb[1], f), Lerp(ha[2], hb[2], f)};
    return SkColor4f::FromColor(SkHSVToColor(mixed));
}

void Scatter(const Layer& l, const LayerEffect& e, float t, const RepeatSpace& space, std::vector<RepeatCopy>* out) {
    const float w = std::max(space.width, 1.0f), h = std::max(space.height, 1.0f), focal = std::max(w, h);
    const float cell = focal * 0.2f * std::clamp(Param(e, "scale", t, 1), 0.05f, 20.0f);
    const float density = std::clamp(Param(e, "density", t, 0.3f), 0.0f, 1.0f);
    const float nearZ = cell, farZ = std::max(std::clamp(Param(e, "amount", t, 15), 0.0f, 40.0f) * cell, nearZ + 1);
    const SkPoint shift = {ParamAt(e, "position", 0, t, 0), ParamAt(e, "position", 1, t, 0)};
    const float depth = Param(e, "z", t, 0), evolution = Param(e, "evolution", t, 0);
    const float slack = std::clamp(Param(e, "slack", t, 1), 0.0f, 1.0f), size = std::max(Param(e, "size", t, 0.5f), 0.0f);
    const float sizeVariance = std::clamp(Param(e, "sizeVariance", t, 0.5f), 0.0f, 1.0f);
    const float angle = Param(e, "angle", t, 0), angleVariance = std::clamp(Param(e, "angleVariance", t, 0), 0.0f, 1.0f);
    const float fade = std::clamp(Param(e, "fade", t, 1), 0.0f, 1.0f), alpha = std::clamp(Param(e, "alpha", t, 1), 0.0f, 1.0f);
    const SkBlendMode modes[] = {SkBlendMode::kSrcOver, SkBlendMode::kPlus, SkBlendMode::kScreen, SkBlendMode::kMultiply,
                                 SkBlendMode::kLighten, SkBlendMode::kDarken, SkBlendMode::kOverlay, SkBlendMode::kDifference};
    const SkBlendMode mode = modes[std::clamp((int)std::lround(Param(e, "blending", t, 0)), 0, 7)];
    const int coloring = (int)std::lround(Param(e, "coloring", t, 0));
    const SkColor4f colours[3] = {ColourOf(e, "color1", t, SkColor4f::FromColor(0xffff55aa)), ColourOf(e, "color2", t, SkColor4f::FromColor(0xff55aaff)),
                                  ColourOf(e, "color3", t, SkColor4f::FromColor(0xffffaa55))};
    const bool lockLuma = Param(e, "lockLuminance", t, 1) >= 0.5f, lockSaturation = Param(e, "lockSaturation", t, 0) >= 0.5f;
    const bool hue = Param(e, "interpolateHue", t, 0) >= 0.5f;
    const SkBlendMode tintMode = lockSaturation ? SkBlendMode::kHue : lockLuma ? SkBlendMode::kColor : SkBlendMode::kSrcIn;
    if (!(density > 0) || !(alpha > 0) || !(size > 0)) return;
    const uint64_t seed = SeedOf(l, e, t, 11);
    std::vector<std::pair<float, RepeatCopy>> found;
    size_t visited = 0;
    const auto room = [&] { return found.size() < kMaxCopies && visited < 1000000; };
    const int z0 = (int)std::floor((nearZ + depth) / cell) - 1, z1 = (int)std::ceil((farZ + depth) / cell) + 1;
    const float margin = std::min(cell + space.radius, cell * (1.5f + size * (1 + sizeVariance) * 0.5f));
    for (int iz = z0; iz <= z1 && room(); iz++) {
        const float reach = ((iz + 1.5f) * cell - depth) / focal;
        if (!(reach > 0)) continue;
        const float hx = w / 2 * reach + margin, hy = h / 2 * reach + margin;
        const int x0 = (int)std::floor((shift.fX - hx) / cell), x1 = (int)std::ceil((shift.fX + hx) / cell);
        const int y0 = (int)std::floor((shift.fY - hy) / cell), y1 = (int)std::ceil((shift.fY + hy) / cell);
        for (int iy = y0; iy <= y1 && room(); iy++) {
            for (int ix = x0; ix <= x1 && room(); ix++) {
                visited++;
                const uint64_t k = Mix(seed ^ Mix((uint64_t)(int64_t)ix * 0x9e37ull) ^ Mix((uint64_t)(int64_t)iy * 0x85ebull + 7) ^ Mix((uint64_t)(int64_t)iz * 0xc2b2ull + 13));
                if (Unit(k, 0) >= density) continue;
                auto jitter = [&](int channel) {
                    return slack * (float)(Unit(k, channel) - 0.5 + 0.35 * SimplexNoise2(evolution * 0.5 + Unit(k, channel + 10) * 97, channel * 3.7));
                };
                const float z = (iz + 0.5f + jitter(3)) * cell - depth;
                if (z <= nearZ || z >= farZ) continue;
                const float k3 = focal / z;
                const SkPoint world = {(ix + 0.5f + jitter(1)) * cell - shift.fX, (iy + 0.5f + jitter(2)) * cell - shift.fY};
                const SkPoint at = {w / 2 + world.fX * k3, h / 2 + world.fY * k3};
                const float scale = size * std::max(0.0f, 1 + sizeVariance * (float)(2 * Unit(k, 4) - 1)) * k3 * cell * 0.5f / std::max(space.radius, 1.0f);
                const float reachOut = space.radius * scale;
                if (!(scale > 0) || at.fX + reachOut < 0 || at.fX - reachOut > w || at.fY + reachOut < 0 || at.fY - reachOut > h) continue;
                const float depthU = (z - nearZ) / (farZ - nearZ);
                RepeatCopy c;
                c.outer = SkMatrix::Translate(at.fX, at.fY);
                c.outer.preRotate(angle + angleVariance * 180 * (float)(2 * Unit(k, 5) - 1));
                c.outer.preScale(scale, scale);
                c.outer.preTranslate(-space.anchor.fX, -space.anchor.fY);
                const float nearFade = std::clamp((z - nearZ) / cell, 0.0f, 1.0f);
                c.alpha = alpha * (1 - fade * depthU) * nearFade * nearFade * (3 - 2 * nearFade);
                if (!(c.alpha > 0.002f)) continue;
                c.mode = mode;
                if (coloring > 0) {
                    c.tint = Gradient3(colours, coloring == 1 ? (float)Unit(k, 6) : depthU, hue);
                    c.tinted = 1;
                    c.tintMode = tintMode;
                }
                found.push_back({z, c});
            }
        }
    }
    std::stable_sort(found.begin(), found.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
    for (const auto& f : found) out->push_back(f.second);
}

}

std::string_view Stem(const std::string& id) {
    const size_t dot = id.rfind('.');
    return dot == std::string::npos ? std::string_view(id) : std::string_view(id).substr(dot + 1);
}

bool MotionEffect(const std::string& id) {
    return std::any_of(std::begin(kMotionIds), std::end(kMotionIds), [&](const char* m) {
        return id == m || Stem(id) == std::string_view(m).substr(7);
    });
}

bool PartEffect(const std::string& id) {
    const std::string_view stem = Stem(id);
    return stem == "growparts" || stem == "shakeparts";
}

void ApplyMotion(const Layer& l, double timeMs, Placement* p) {
    if (l.placed) return;
    const float t = l.localTime(timeMs);
    const double s = Seconds(l, timeMs);
    bool repeated = false;
    for (const LayerEffect& e : l.effects) {
        const bool alight = e.id.rfind("com.alightcreative.", 0) == 0;
        if (e.hidden || (!alight && e.id.compare(0, 7, "absent.") != 0)) continue;
        const std::string_view id = Stem(e.id);
        if (RepeatEffect(e.id)) {
            repeated = true;
            continue;
        }
        if (id == "growparts") {
            const int want = (int)std::lround(Param(e, "depth", t, 1));
            const int depth = e.depth > 0 ? e.depth : 1;
            if (want != depth) continue;
            const float progress = std::clamp(Param(e, "progress", t, 0), 0.0f, 1.0f);
            const float lo = progress * 1000.0f, hi = (progress + 0.1f) * 1000.0f;
            const float u = std::clamp((p->location.fY - lo) / std::max(hi - lo, 1e-4f), 0.0f, 1.0f);
            const float f = u * u * (3.0f - 2.0f * u);
            p->scale = {p->scale.fX * f, p->scale.fY * f};
            continue;
        }
        if (id == "shakeparts") {
            const float mag = Param(e, "magnitude", t, 10), ev = Param(e, "evolution", t, 0);
            const double raw = (double)(l.noiseId ? l.noiseId : l.id);
            const double seed = raw - std::floor(raw / 10000.0) * 10000.0;
            const float dx = (float)Noise(p->location.fX + ev + 1.0 + seed, p->location.fY + seed);
            const float dy = (float)Noise(p->location.fX + ev + 812.3417 + seed, p->location.fY + seed + 137.0);
            const int depth = e.depth > 0 ? e.depth : 1;
            p->location += SkPoint{dx * mag, dy * mag * (depth / 10.0f)};
            continue;
        }
        if (id == "poseshake" || id == "skexnxxposeshake") {
            const double phase = s * Param(e, "gamma", t, 12) * Param(e, "beta", t, 1);
            p->location += SkPoint{(float)(std::sin(phase) * Param(e, "Xmag", t, 25)),
                                   (float)(std::sin(phase + 1.047) * Param(e, "Ymag", t, 25) * 0.7)};
            p->rotation += (float)(std::sin(phase + 2.094) * Param(e, "rotMag", t, 3));
            continue;
        }
        if (id == "vshake") {
            const float duration = std::max(0.001f, (l.endTime - l.startTime) / 1000.0f);
            auto wave = [&](const char* type, const char* freq, const char* offset) {
                const int kind = (int)std::lround(Param(e, type, t, 1));
                const float x = (float)s * duration * Param(e, "freq", t, 2) * (Param(e, freq, t, 10) / 10.0f) + Param(e, offset, t, 0);
                if (kind == 0) return std::cos(x * 3.14159265f);
                if (kind == 1) return std::sin(x * 3.14159265f);
                if (kind == 2) return (float)(2.0 * std::abs(2.0 * (x / 2.0 - std::floor(x / 2.0 + 0.5))) - 1.0);
                return (float)Noise(p->location.fX * (Param(e, "freq", t, 2) * (Param(e, freq, t, 10) / 10.0f)) + 49235.319 + Param(e, offset, t, 0),
                                    p->location.fY + 137.0);
            };
            const float mx = wave("xtype", "xfreq", "xoffset"), my = wave("ytype", "yfreq", "yoffset");
            const float mz = wave("ztype", "zfreq", "zoffset"), mt = wave("ttype", "tfreq", "toffset");
            const float mag = Param(e, "mag", t, 4);
            const float dx = (Param(e, "xmag", t, 15) / 10.0f) * (Param(e, "xmag", t, 15) / 10.0f) * mx * mag;
            const float dy = (Param(e, "ymag", t, 40) / 10.0f) * (Param(e, "ymag", t, 40) / 10.0f) * my * mag;
            const float dt = (Param(e, "tmag", t, 0) / 10.0f) * (Param(e, "tmag", t, 0) / 10.0f) * mt * mag;
            const float dz = (Param(e, "zmag", t, 1) / 50.0f) * (Param(e, "zmag", t, 1) / 50.0f) * mz * mag;
            p->location += SkPoint{dx, dy};
            p->rotation += Param(e, "rot", t, 0) + dt;
            const float scale = Param(e, "zdist", t, 1) + dz - 1.0f;
            p->scale = {p->scale.fX + scale, p->scale.fY + scale};
            continue;
        }
        if (id == "vshakenew") {
            const float duration = std::max(0.001f, (l.endTime - l.startTime) / 1000.0f);
            auto part = [&](const char* randAmp, const char* randFreq, const char* waveAmp, const char* waveFreq, const char* offset, int axis) {
                const float base = (float)s * duration * Param(e, "freq", t, 8);
                const float r = (float)Noise(base * (Param(e, randFreq, t, 1) / 5.0f) + axis * 1013.0 + Param(e, offset, t, 0),
                                             base + 17337.94f + Param(e, offset, t, 0));
                const float w = (float)std::sin(base * (Param(e, waveFreq, t, 0.5f) / 5.0f) * 3.14159265f + Param(e, offset, t, 0));
                (void)randAmp;
                return r + w * (Param(e, waveAmp, t, 0) / 10.0f);
            };
            const float mx = part("xrandamp", "xrandfreq", "xwaveamp", "xwavefreq", "xoffset", 1);
            const float my = part("yrandamp", "yrandfreq", "ywaveamp", "ywavefreq", "yoffset", 2);
            const float mz = part("zrandamp", "zrandfreq", "zwaveamp", "zwavefreq", "zoffset", 3);
            const float mt = part("trandamp", "trandfreq", "twaveamp", "twavefreq", "toffset", 4);
            const float amp = Param(e, "amp", t, 1) * 15.0f;
            p->location += SkPoint{(Param(e, "xrandamp", t, 192) / 40.0f) * (Param(e, "xrandamp", t, 192) / 40.0f) * mx * amp,
                                   (Param(e, "yrandamp", t, 96) / 40.0f) * (Param(e, "yrandamp", t, 96) / 40.0f) * my * amp};
            p->rotation += (Param(e, "trandamp", t, 0) / 40.0f) * (Param(e, "trandamp", t, 0) / 40.0f) * mt * amp;
            const float ds = (Param(e, "zrandamp", t, 0) / 50.0f) * (Param(e, "zrandamp", t, 0) / 50.0f) * mz * amp;
            p->scale = {p->scale.fX + ds, p->scale.fY + ds};
            continue;
        }
        if (id == "transform2" || id == "skexnxx15") {
            const float master = Param(e, "amp", t, 1);
            const float x = Param(e, "posx", t, 0) * (Param(e, "xinv", t, 0) >= 0.5f ? -1.0f : 1.0f) * master;
            const float y = Param(e, "posy", t, 0) * (Param(e, "yinv", t, 0) >= 0.5f ? -1.0f : 1.0f) * master;
            const float a = Param(e, "angle", t, 0) * (Param(e, "ainv", t, 0) >= 0.5f ? -1.0f : 1.0f) * master;
            float z = std::max(Param(e, "posz", t, 1), 0.0001f);
            if (Param(e, "zinv", t, 0) >= 0.5f) z = 1.0f / z;
            const float ds = (z - 1.0f) * master;
            p->location += SkPoint{x, y};
            p->rotation += a;
            p->scale = {p->scale.fX + ds, p->scale.fY + ds};
            continue;
        }
        if (id == "facemotion") {
            const float dt = 0.001f, next = std::min(1.0f, t + dt);
            const float dx = l.location.get(0, next, 0) - l.location.get(0, t, 0);
            const float dy = l.location.get(1, next, 0) - l.location.get(1, t, 0);
            if (std::fabs(dx) + std::fabs(dy) > 1e-5f) p->rotation += (float)(std::atan2(dy, dx) * 180.0 / 3.141592653589793);
            continue;
        }
        if (id == "perlindisplace") {
            const float freq = Param(e, "freq", t, 2), mag = Param(e, "mag", t, 25), seed = Param(e, "seed", t, 0);
            const bool position = Param(e, "basis", t, 0) >= 0.5f;
            const float x = position ? p->location.fX * freq / 500.0f : (float)s * freq * 4.0f;
            const float y = position ? p->location.fY * freq / 500.0f : (float)s * freq * 4.0f;
            p->location += SkPoint{(float)(Noise(x, seed) * mag), (float)(Noise(y, seed + 7468.329) * mag)};
            continue;
        }
        if (alight) {
            const double sec = Seconds(l, timeMs);
            if (id == "oscillate" || id == "oscillate2" || id == "oscillate3") {
                const float freq = Param(e, "freq", t, 2), mag = Param(e, "mag", t, 25), angle = Param(e, "angle", t, 45);
                const float phase = Param(e, "phase", t, 0), kind = Param(e, "type", t, 0);
                const long direction = std::lround(Param(e, "direction", t, 0));
                double cycles = sec * freq;
                if (id == "oscillate2") cycles = freq + phase * 2.0;
                else if (id == "oscillate3") cycles = freq * 2.0 + phase * 2.0;
                else cycles += phase * 2.0;
                const double m = kind >= 0.5f ? 2.0 * Wave(cycles / 2.0, 1) - 1.0 : std::sin(cycles * 3.141592653589793);
                const float a = (id == "oscillate3" ? (90.0f - angle) : angle) * 0.0174532925f;
                if (direction == 1) p->z += (float)(mag * m);
                else if (direction == 2) {
                    p->location += Toward(a, (float)(mag * m)) + Toward(a + 90, (float)(mag * m));
                    const double m2 = kind >= 0.5f ? 2.0 * Wave((cycles + 0.5) / 2.0, 1) - 1.0 : std::sin((cycles + 0.5) * 3.141592653589793);
                    p->z += (float)(mag * m2);
                } else p->location += Toward(a, (float)(mag * m));
            } else if (id == "spin") {
                p->rotation += (float)(sec * Param(e, "rpm", t, 60) * 6.0);
            } else if (id == "pulsate" || id == "pulsate2") {
                const double cycles = id == "pulsate" ? sec * Param(e, "freq", t, 2) + Param(e, "phase", t, 0)
                                                       : Param(e, "freq", t, 2) + Param(e, "phase", t, 0);
                const double m = Param(e, "type", t, 0) >= 0.5f ? 2.0 * Wave(cycles / 2.0, 1) - 1.0 : std::sin(cycles * 3.141592653589793);
                const float lo = Param(e, "minsize", t, 0.9f), hi = Param(e, "maxsize", t, 1.1f);
                const float ds = (float)(lo + (hi - lo) * ((m + 1.0) / 2.0));
                p->scale = {p->scale.fX * ds, p->scale.fY * ds};
            } else if (id == "swing" || id == "swing2") {
                const double cycles = id == "swing" ? sec * Param(e, "freq", t, 2) + Param(e, "phase", t, 0)
                                                    : Param(e, "freq", t, 2) + Param(e, "phase", t, 0);
                const double m = Param(e, "type", t, 0) >= 0.5f ? 2.0 * Wave(cycles / 2.0, 1) - 1.0 : std::sin(cycles * 3.141592653589793);
                const float a1 = Param(e, "a1", t, -30), a2 = Param(e, "a2", t, 30);
                p->rotation += (float)(((a2 - a1) * ((m + 1.0) / 2.0)) + a1);
            } else if (id == "scaleassist") {
                const float sc = Param(e, "scale", t, 1), damp = Param(e, "damp", t, 1);
                const long axis = std::lround(Param(e, "axis", t, 3));
                if (axis == 0) p->scale.fX *= sc;
                else if (axis == 1) p->scale.fY *= sc;
                else if (axis == 2) { p->scale.fX *= sc; p->scale.fY *= sc * damp; }
                else { p->scale.fX *= sc; p->scale.fY *= 1 - (sc - 1) * damp; }
            } else if (id == "jitter") {
                const double global = sec, step = std::max(Param(e, "freq", t, 2), 0.01f);
                const double tq = global - std::fmod(global, 1.0 / step);
                const float a = Param(e, "angle", t, 45) * 0.0174532925f, m = Param(e, "mag", t, 25);
                const float n = (float)Noise(tq * 637.729, Param(e, "seed", t, 0) * 394.417);
                p->location += Toward(a, m * n);
                if (Param(e, "slack", t, 0) > 0) p->location += Toward(a + 90, m * Param(e, "slack", t, 0) * (float)Noise(tq * 951.217 + 149.231, Param(e, "seed", t, 0) * 894.417 + 2773.908));
                p->z += Param(e, "zjitter", t, 0) * (float)Noise(tq * 637.729 + 241.386, Param(e, "seed", t, 0) * 394.417 + 1729.361);
            } else if (id == "randomdisplace") {
                const float ev = Param(e, "evolution", t, 0), seed = Param(e, "seed", t, 0), mag = Param(e, "mag", t, 50);
                p->location += SkPoint{(float)(mag * Noise(p->location.fX * Param(e, "scatter", t, .5f) / 50 + seed * 54623.245,
                                                               p->location.fY * Param(e, "scatter", t, .5f) / 500, ev + seed * 49235.319)),
                                       (float)(mag * Noise(p->location.fX * Param(e, "scatter", t, .5f) / 50,
                                                           p->location.fY * Param(e, "scatter", t, .5f) / 500 + seed * 8723.565, ev + 7468.329 + seed * 19337.94))};
            } else if (id == "shake" || id == "shake2") {
                const float ev = Param(e, "evolution", t, 0) + (id == "shake" ? (float)sec * Param(e, "speed", t, 1) : Param(e, "freq", t, 1));
                const float a = Param(e, "angle", t, 0) * 0.0174532925f, m = Param(e, "mag", t, 25);
                const float dx = m * (float)Noise(ev + Param(e, "seed", t, 0) * 49235.319, Param(e, "seed", t, 0) * 49235.319);
                const float dy = m * Param(e, "slack", t, .25f) * (float)Noise(ev + 7468.329 + Param(e, "seed", t, 0) * 19337.94, 0);
                p->location += SkPoint{dx * std::cos(a) - dy * std::sin(a), dx * std::sin(a) + dy * std::cos(a)};
                p->z += Param(e, "zshake", t, 0) * (float)Noise(ev + 14192.277, Param(e, "seed", t, 0) * 71401.17);
            }
            continue;
        }
        if (id == "autoshake") {
            const double x = Integral(e, "frequency", l, s, 2) + Param(e, "evolution", t, 0);
            const float seed = Param(e, "seed", t, 0), m = Param(e, "magnitude", t, 50), angle = Param(e, "angle", t, 45);
            const bool standard = Param(e, "pattern", t, 0) > 0.5f;
            const double along = standard ? Noise(x, seed, true) : Noise(x, Plane(seed, 0));
            const double across = standard ? Noise(x + 7.225, seed - 8.075, true) : Noise(x, Plane(seed, 1));
            const double depth = standard ? Noise(x + 3.61, seed + 5.27, true) : Noise(x, Plane(seed, 2));
            p->location += Toward(angle, m * along) + Toward(angle + 90, m * Param(e, "slack", t, 0.25f) * across);
            p->z += (float)(Param(e, "zshake", t, 0) * depth);
        } else if (id == "randomdisplace") {
            if (!repeated) p->location += Displacement(e, t, Integral(e, "speed", l, s, 1), p->location);
        } else if (id == "randomjitter") {
            const int64_t k = (int64_t)std::floor(Integral(e, "frequency", l, LatestMs(s * 1000) / 1000, 30));
            const uint64_t seed = Mix((uint64_t)std::llround((double)Param(e, "seed", t, 0) * 1000) + 0x51ed27ull);
            const float m = Param(e, "magnitude", t, 25), angle = Param(e, "angle", t, 45);
            const double along = ((k & 1) ? -1 : 1) * (0.35 + 0.65 * Unit(seed, k));
            p->location += Toward(angle, m * along) + Toward(angle + 90, m * Param(e, "slack", t, 0) * (2 * Unit(seed ^ 1, k) - 1));
            p->z += (float)(Param(e, "zjitter", t, 0) * (2 * Unit(seed ^ 2, k) - 1));
        } else if (id == "oscillate") {
            const double c = Integral(e, "frequency", l, s, 2) + Param(e, "phase", t, 0) / 2;
            const float kind = Param(e, "wave", t, 0), m = Param(e, "magnitude", t, 25), angle = Param(e, "angle", t, 45);
            const long direction = std::lround(Param(e, "direction", t, 0));
            if (direction == 1) p->z += (float)(m * Wave(c, kind));
            else if (direction == 2) p->location += Toward(angle, m * Wave(c + 0.25, kind)) + Toward(angle + 90, m * Wave(c, kind));
            else p->location += Toward(angle, m * Wave(c, kind));
        } else if (id == "swing") {
            const double w = Wave(Integral(e, "frequency", l, s, 2) + Param(e, "phase", t, 0) / 2, Param(e, "wave", t, 0));
            const float a1 = Param(e, "angle1", t, -30), a2 = Param(e, "angle2", t, 30);
            p->rotation += (float)((a1 + a2) / 2 + (a2 - a1) / 2 * w);
        } else if (id == "spin") {
            p->rotation += (float)std::fmod(6 * Integral(e, "rpm", l, s, 60), 360.0);
        } else if (id == "pulsesize") {
            const double w = Wave(Integral(e, "frequency", l, s, 2) + Param(e, "phase", t, 0) / 2, Param(e, "wave", t, 0));
            const float lo = Param(e, "shrink", t, 0.9f), hi = Param(e, "grow", t, 1.1f);
            const float k = (float)std::max(0.0, (lo + hi) / 2 + (hi - lo) / 2 * w);
            p->scale = {p->scale.fX * k, p->scale.fY * k};
        } else if (id == "scaleassist") {
            const float s = Param(e, "scale", t, 1), damp = Param(e, "damp", t, 1);
            const long axis = std::lround(Param(e, "axis", t, 3));
            if (axis != 1) p->scale.fX *= s;
            if (axis == 1) p->scale.fY *= s;
            else if (axis == 2) p->scale.fY *= s * damp;
            else if (axis == 3) p->scale.fY *= 1 - damp * (s - 1);
        }
    }
}

namespace {

const LayerEffect* ParentHelper(const Layer& l) {
    for (const LayerEffect& e : l.effects) {
        if (!e.hidden && e.id == "absent.parenthelper") return &e;
    }
    return nullptr;
}

float Pulled(long mode, float weight, float neutral, float parent) {
    return mode == 1 ? neutral : mode == 2 ? neutral + (parent - neutral) * weight : parent;
}

}

void ParentTerms(const Layer& l, double timeMs, const Placement& parent, SkPoint* scale, float* rotation) {
    *scale = parent.scale;
    *rotation = parent.rotation;
    const LayerEffect* e = l.effects.empty() || l.placed ? nullptr : ParentHelper(l);
    if (!e) return;
    const float t = l.localTime(timeMs);
    const long scaleMode = std::lround(Param(*e, "scaleMode", t, 0)), rotateMode = std::lround(Param(*e, "rotateMode", t, 0));
    const float scaleWeight = Param(*e, "scaleWeight", t, 1), rotateWeight = Param(*e, "rotateWeight", t, 1);
    *scale = {Pulled(scaleMode, scaleWeight, 1, parent.scale.fX), Pulled(scaleMode, scaleWeight, 1, parent.scale.fY)};
    *rotation = Pulled(rotateMode, rotateWeight, 0, parent.rotation);
}

void ParentRoll(const Layer& l, double timeMs, Placement* own) {
    const LayerEffect* e = l.effects.empty() || l.placed ? nullptr : ParentHelper(l);
    if (!e) return;
    const float t = l.localTime(timeMs);
    const long axis = std::lround(Param(*e, "autoRotate", t, 0));
    if (axis != 1 && axis != 2) return;
    const SkRect box = ShapePath(l, t).getBounds();
    const float across = std::fabs(box.width() * own->scale.fX), along = std::fabs(box.height() * own->scale.fY);
    const bool narrow = across <= along;
    const float radius = std::min(across, along) / 2 + Param(*e, "radiusAdjust", t, 0) * std::fabs(narrow ? own->scale.fX : own->scale.fY);
    if (!std::isfinite(radius) || std::fabs(radius) < 1e-3f) return;
    const float roll = (axis == 1 ? own->location.fX : own->location.fY) / radius * 180 / (float)(kTau / 2);
    if (std::isfinite(roll)) own->rotation += roll;
}

float MotionOpacity(const Layer& l, double timeMs) {
    float opacity = 1;
    const double sec = Seconds(l, timeMs);
    for (const LayerEffect& e : l.effects) {
        if (e.hidden) continue;
        const float t = l.localTime(timeMs);
        if (e.id == "absent.pulseopacity") {
            const double w = Wave(Integral(e, "frequency", l, sec, 2) + Param(e, "phase", t, 0) / 2, Param(e, "wave", t, 0));
            opacity *= 1 - std::clamp(Param(e, "strength", t, 1), 0.0f, 1.0f) * (float)(0.5 - 0.5 * w);
        } else if (e.id == "com.alightcreative.effects.fade") {
            const double inTime = Param(e, "inTime", t, 0.5f), outTime = Param(e, "outTime", t, 0.5f);
            const double duration = std::max(0.001, (l.endTime - l.startTime) / 1000.0);
            auto ease = [](double x) { x = std::clamp(x, 0.0, 1.0); return x < .5 ? 2 * x * x : -1 + (4 - 2 * x) * x; };
            if (sec < inTime) opacity *= (float)ease(sec / std::max(0.001, inTime));
            if (sec > duration - outTime) opacity *= (float)ease((duration - sec) / std::max(0.001, outTime));
        } else if (e.id == "com.alightcreative.effects.blink" || e.id == "com.alightcreative.effects.blink2") {
            const double freq = Param(e, "freq", t, 1);
            const bool off = e.id == "com.alightcreative.effects.blink" ? std::fmod(sec * freq, 1.0) > 0.5 : std::fmod(freq, 1.0) > 0.5;
            if (off) opacity = 0;
        } else if (e.id == "com.alightcreative.effects.pulse-opacity" || e.id == "com.alightcreative.effects.pulse_opacity2") {
            const double cycles = e.id == "com.alightcreative.effects.pulse-opacity"
                ? sec * Param(e, "freq", t, 2) + Param(e, "phase", t, 0)
                : Param(e, "freq", t, 2) + Param(e, "phase", t, 0);
            const double m = Param(e, "type", t, 0) >= 0.5f ? 2.0 * Wave(cycles / 2.0, 1) - 1.0 : std::sin(cycles * 3.141592653589793);
            const float strength = std::clamp(Param(e, "strength", t, 1), 0.0f, 1.0f);
            opacity *= (1 - strength) + (float)((m + 1.0) / 2.0) * strength;
        } else if (e.id == "com.alightcreative.effects.flicker" || e.id == "com.alightcreative.effects.flicker2") {
            const float freq = Param(e, "freq", t, 8), mag = Param(e, "mag", t, 0.5f), seed = Param(e, "seed", t, 0);
            const double x = (e.id == "com.alightcreative.effects.flicker" ? sec : 1.0) * freq / 4.0;
            const float a = (float)Noise(x, seed);
            const float b = (float)Noise(x * 2.0 + 234.34, seed + 547.931);
            const float c = (float)Noise(x * 4.0 + 468.68, seed + 1095.862);
            opacity *= (1 - mag) + mag * (0.25f + 0.25f * a + 0.25f * b + 0.25f * c);
        }
    }
    return opacity;
}

bool FollowsPath(const Layer& l) {
    return !l.placed && std::any_of(l.effects.begin(), l.effects.end(), [](const LayerEffect& e) { return !e.hidden && e.id == "absent.movealongpath"; });
}

void FollowPath(const Layer& l, double timeMs, const SkPath& path, Placement* p) {
    for (const LayerEffect& e : l.effects) {
        if (e.hidden || e.id != "absent.movealongpath") continue;
        const float t = l.localTime(timeMs);
        std::vector<double> lengths;
        double total = 0;
        SkPathMeasure measure(path, false);
        do {
            lengths.push_back(measure.getLength());
            total += lengths.back();
        } while (measure.nextContour());
        if (!(total > 0)) return;
        double at = std::clamp((double)Param(e, "progress", t, 0), 0.0, 1.0) * total;
        SkPathMeasure walk(path, false);
        for (size_t i = 0; i + 1 < lengths.size() && (at > lengths[i] || !(lengths[i] > 0)); i++) {
            at -= lengths[i];
            walk.nextContour();
        }
        SkPoint pos;
        SkVector tangent;
        if (!walk.getPosTan((SkScalar)at, &pos, &tangent)) return;
        SkVector out{tangent.fY, -tangent.fX};
        if (walk.isClosed()) {
            if (path.contains(pos.fX + out.fX * 0.5f, pos.fY + out.fY * 0.5f)) out = -out;
        } else {
            const float length = walk.getLength();
            double area = 0;
            SkPoint first = pos, prev = pos;
            for (int k = 0; k <= 64; k++) {
                SkPoint q;
                if (!walk.getPosTan(length * k / 64, &q, nullptr)) continue;
                if (k == 0) first = q;
                else area += (double)prev.fX * q.fY - (double)q.fX * prev.fY;
                prev = q;
            }
            area += (double)prev.fX * first.fY - (double)first.fX * prev.fY;
            if (area < -1e-3 * length * length) out = -out;
        }
        const float turn = (Param(e, "rotate", t, 1) >= 0.5f ? (float)(std::atan2(tangent.fY, tangent.fX) * 360 / kTau) : 0.0f) + Param(e, "orientation", t, 0);
        const SkVector shift = SkMatrix::RotateDeg(turn).mapVector(Param(e, "offset", t, 0, 0), Param(e, "offset", t, 0, 1));
        p->location = pos + out * Param(e, "inset", t, 0) + shift;
        p->rotation += turn;
        return;
    }
}

bool EchoOf(const Layer& l, double timeMs, Echo* echo) {
    for (const LayerEffect& e : l.effects) {
        if (e.hidden || e.id != "absent.echo") continue;
        const float t = l.localTime(timeMs);
        echo->count = std::clamp((int)std::floor(Param(e, "count", t, 1) + 1e-4f), 0, 20);
        echo->seconds = std::clamp(Param(e, "seconds", t, 0.5f), -60.0f, 60.0f);
        echo->alpha = std::clamp(Param(e, "alpha", t, 1), 0.0f, 1.0f);
        echo->atop = Param(e, "composite", t, 0) >= 0.5f;
        return echo->count > 0 && echo->seconds != 0;
    }
    return false;
}

bool ShutterBlurOf(const Layer& l, double timeMs, ShutterBlur* blur) {
    for (const LayerEffect& e : l.effects) {
        const bool motion = e.id == "absent.motionblur" || e.id.rfind("absent.motionblur", 0) == 0;
        if (e.hidden || (e.id != "absent.shutterblur" && !motion)) continue;
        const float t = l.localTime(timeMs);
        // The fast preview smear is intentionally skipped for the keyframed
        // Motion Blur effect: it samples two poses and can flash a dark frame
        // when the layer is also scaled or masked. The accurate path is stable
        // and still cheap at the default eight samples.
        blur->fast = gFastShutter && !motion;
        if (e.id == "absent.shutterblur") {
            blur->angle = std::clamp(Param(e, "shutter", t, 180), 0.0f, 360.0f);
            blur->samples = std::clamp((int)std::lround(Param(e, "samples", t, 8)), 2, 32);
            return blur->angle > 0;
        }
        const float tune = std::clamp(Param(e, "tune", t, 1), 0.0f, 4.0f);
        blur->angle = 360 * tune;
        blur->samples = std::clamp((int)std::lround(8 * tune), 8, 32);
        blur->adaptive = true;
        blur->trailing = true;
        blur->position = Param(e, "position", t, 1) >= 0.5f;
        blur->scale = Param(e, "scale", t, 1) >= 0.5f;
        blur->rotation = Param(e, "rotation", t, 1) >= 0.5f;
        return tune > 0 && (blur->position || blur->scale || blur->rotation);
    }
    return false;
}

void SetFastShutterBlur(bool fast) { gFastShutter = fast; }

sk_sp<SkImageFilter> SmearFilter(SkVector moved) {
    const float length = moved.length();
    if (!(length >= 0.5f) || !std::isfinite(length)) return nullptr;
    const float angle = atan2f(moved.fY, moved.fX) * 180 / 3.14159265f;
    const SkSamplingOptions linear(SkFilterMode::kLinear);
    auto turned = SkImageFilters::MatrixTransform(SkMatrix::RotateDeg(-angle), linear, nullptr);
    auto blurred = SkImageFilters::Blur(length * 0.3f, 0, SkTileMode::kDecal, turned);
    return SkImageFilters::MatrixTransform(SkMatrix::RotateDeg(angle), linear, blurred);
}

bool RepeatEffect(const std::string& id) {
    return std::any_of(std::begin(kRepeatIds), std::end(kRepeatIds), [&](const char* m) { return id == m; });
}

bool HasRepeat(const Layer& l) {
    return std::any_of(l.effects.begin(), l.effects.end(), [](const LayerEffect& e) { return !e.hidden && RepeatEffect(e.id); });
}

bool RepeatsAlongPath(const Layer& l) {
    return std::any_of(l.effects.begin(), l.effects.end(), [](const LayerEffect& e) { return !e.hidden && e.id == "absent.repeatalongpath"; });
}

void RepeatCopies(const Layer& l, double timeMs, const RepeatSpace& space, std::vector<RepeatCopy>* copies) {
    const float t = l.localTime(timeMs);
    const double s = Seconds(l, timeMs);
    std::vector<RepeatCopy> result(1);
    bool repeated = false;
    size_t limit = kMaxCopies;
    for (const LayerEffect& e : l.effects) {
        if (e.hidden) continue;
        if (e.id == "absent.randomdisplace") {
            if (!repeated) continue;
            const double drift = Integral(e, "speed", l, s, 1);
            for (RepeatCopy& c : result) {
                const SkPoint at = (c.outer * space.layer * c.local).mapPoint(space.pivot);
                const SkVector d = Displacement(e, t, drift, at);
                c.outer.postTranslate(d.fX, d.fY);
            }
            continue;
        }
        if (!RepeatEffect(e.id)) continue;
        repeated = true;
        for (const char* key : {"copyDelay", "randomRotation", "randomScale", "randomOpacity"}) if (Param(e, key, t, 0) != 0) limit = 256;
        if (ParamAt(e, "randomPosition", 0, t, 0) != 0 || ParamAt(e, "randomPosition", 1, t, 0) != 0) limit = 256;
        const std::string_view id = std::string_view(e.id).substr(7);
        std::vector<RepeatCopy> made;
        std::vector<Spot> spots;
        if (id == "repeat") Basic(e, t, &made);
        else if (id == "scatterfield") Scatter(l, e, t, space, &made);
        else {
            if (id == "linearrepeat") Linear(e, t, &spots);
            else if (id == "gridrepeat") Grid(e, t, &spots, limit == 256 ? 16 : 150);
            else if (id == "radialrepeat") Radial(e, t, &spots);
            else if (id == "scatterrepeat") Scattered(l, e, t, &spots);
            else OnPath(e, t, space, &spots);
            Ranged(l, e, t, std::move(spots), &made);
        }
        std::vector<RepeatCopy> next;
        next.reserve(std::min(limit, made.size() * result.size()));
        for (const RepeatCopy& m : made) {
            if (next.size() >= limit) break;
            for (const RepeatCopy& r : result) {
                if (next.size() >= limit) break;
                RepeatCopy c = r;
                c.outer = m.outer * r.outer;
                c.local = m.local * r.local;
                if (!c.outer.isFinite() || !c.local.isFinite()) continue;
                c.shiftMs = r.shiftMs + m.shiftMs;
                c.alpha = r.alpha * m.alpha;
                if (m.tinted > r.tinted) {
                    c.tint = m.tint;
                    c.tinted = m.tinted;
                    c.tintMode = m.tintMode;
                }
                if (m.mode != SkBlendMode::kSrcOver) c.mode = m.mode;
                next.push_back(c);
            }
        }
        result = std::move(next);
    }
    *copies = std::move(result);
}

void ParseSteps(Layer* l) {
    for (const LayerEffect& e : l->effects) {
        if (e.hidden || e.id != "absent.timequant") continue;
        const Anim* fps = Find(e, "fps");
        l->steps = fps && !fps->empty() ? *fps : Anim{{8.0f}};
        return;
    }
}

}
