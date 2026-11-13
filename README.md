<div align="center">

<br/>
<br/>

<img src="docs/schnitt-icon.png" alt="Schnitt app icon" width="190" />

# Schnitt

### Cut. Animate. Render.

**A focused Android video editor with a native GPU engine, a real timeline, and no paywalls.**

<br/>

[![Latest release](https://img.shields.io/github/v/release/fleurdeur/Schnitt?style=for-the-badge&labelColor=0d1117)](https://github.com/fleurdeur/Schnitt/releases)
[![License](https://img.shields.io/github/license/fleurdeur/Schnitt?style=for-the-badge&labelColor=0d1117)](https://github.com/fleurdeur/Schnitt/blob/main/LICENSE)
[![Downloads](https://img.shields.io/github/downloads/fleurdeur/Schnitt/total?style=for-the-badge&labelColor=0d1117)](https://github.com/fleurdeur/Schnitt/releases)
[![Android](https://img.shields.io/badge/Android-13%2B-7ED9A0?style=for-the-badge&labelColor=0d1117)](https://github.com/fleurdeur/Schnitt/releases)

<br/>

[**Download**](#download) · [**Features**](#features) · [**Presets**](#presets) · [**Plugins**](#plugins) · [**Build**](#build) · [**Credits**](#credits)

<br/>

<img src="docs/schnitt-banner.png" alt="Schnitt banner" width="100%" />

</div>

> [!IMPORTANT]
> Schnitt is an independent editor. It is not affiliated with Adobe, Alight Motion, After Effects, YouTube, SoundCloud, Deezer, Bandcamp, Mixkit, or their parent companies. Only use media you have the right to edit.

---

<div align="center">

<h1><a id="features"></a>Features</h1>

<table>
  <tr>
    <td width="50%" valign="top">

#### Timeline and animation
- **Layered timeline** with groups, masks, trims, splits, snapping, and multi-select.
- **Keyframes and easing** for every transform, effect and audio property.
- **Transform recording** — press record and move the layer while playback writes keys.
- **Puppet mesh** — Pin, Mesh and Sway sections for real mesh bending.
- **Speed, time remap, reverse, smoothing, captions and motion tracking.**
- **Templates and presets** that open straight into an editable project.

#### Effects and shaders
- **GPU effect browser** for colour, blur, distort, light, retro, text, 3D and beat effects.
- **Beat detection** with Bass/Vocal/Full bands, sensitivity, BPM range and exact beat keyframes.
- **Real Depth Anything V2** depth maps for video layers with on-device ONNX inference.
- **Effect JSON import** and an AEX metadata/porting path.
- **Global shader effects** shared by preview, timeline and export.

    </td>
    <td width="50%" valign="top">

#### Audio
- **Audio layers** with volume, pitch, fades, speed mapping and a live meter.
- **Full audio effect rack** — EQ, filters, reverb, echo, compressor, gate, voice and pitch.
- **Audio effect preset JSON import.**
- **Audio extraction up to 320 kbps.**
- **Audio library** with Mixkit, custom pages, Deezer, Bandcamp and SoundCloud fallbacks.
- **yt-dlp add-on** with a one-time dependency install and direct insertion into the timeline.
- **High-quality preview playback** through Media3 ExoPlayer and OkHttp.

#### Export and compatibility
- **Video, audio, GIF and project export** with encoder fallbacks.
- **Universal APK** for ARMv7, ARM64 and x86_64.
- **Vulkan, Ganesh and OpenGL fallback** instead of device blacklists.
- **AMOLED green and green Daylight themes**, high-contrast text, and theme-aware preview.
- **No feature paywalls.** All included presets and effects are free to use.

    </td>
  </tr>
</table>

</div>

---

<div align="center">

<h1><a id="download"></a>Download</h1>

<a href="https://github.com/fleurdeur/Schnitt/releases" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/supported/android/cozy.svg" alt="Download for Android" height="55"/></a>

Grab the latest signed APK from **[Releases](https://github.com/fleurdeur/Schnitt/releases)**.

| Package | Best for | Size |
| :--- | :--- | ---: |
| **Schnitt Universal** | Any supported phone, one APK | ~108 MB |
| **Schnitt ARM64** | Modern 64-bit phones | ~71 MB |
| **Schnitt ARMv7** | Older 32-bit phones | ~70 MB |

**Requirements:** Android 13 or newer, a 64-bit or 32-bit ARM device / x86_64 emulator, and Vulkan 1.1 or OpenGL ES 3.0.

</div>

---

<div align="center">

<h1><a id="presets"></a>Presets</h1>

A free preset library lives in **Home → Tools → Presets**. Pick a recipe, open it as a project, then edit every layer, keyframe and shader normally.

- **Colour** — Warm Clean, Cold Cinema, Film Fade, Amber Night
- **Shake** — Micro Shake, Beat Shake, Camera Glitch, Handheld
- **Light** — Soft Glow, Neon Edge
- **Retro** — VHS 1987, Pixel Fade
- **Motion** — Dream Blur, Speed Smear

The presets are built from Schnitt's own free effect shaders. They are not copied paid presets, and nothing is locked behind an account or a purchase.

</div>

---

<div align="center">

<h1><a id="plugins"></a>Plugins</h1>

Schnitt has a sandboxed plugin host for Adobe-style panels and effect packages.

- **ZXP** — CEP extension bundles with a floating, resizable HTML panel.
- **JSX / JSXBIN** — ExtendScript panels hosted in the sandbox.
- **AEX** — PE/PiPL metadata is read and the effect is ported to a compatible global shader with editable properties.
- **Floating windows** — minimize, restore, move, resize, reset and persist position.
- **Target layer picker** — apply a plugin effect to any layer on the timeline.
- **Undo-friendly** — plugin effects use the app's normal effect history.

Native Windows Adobe binaries cannot execute on Android. Where a binary cannot run, Schnitt ports its effect contract and exposes the compatible shader properties rather than pretending to execute the DLL.

</div>

---

<div align="center">

<h1><a id="build"></a>Build</h1>

```bash
# ARM64 release
./gradlew :app:assembleFossRelease -PamAbi=arm64-v8a

# Universal release
./gradlew :app:assembleFossRelease -PamAbi=universal
```

**Toolchain**

- Android SDK 37 / build-tools 37
- NDK `30.0.16248370`
- JDK 21+
- CMake 3.31.6 and Ninja
- Skia and native third-party sources for a full native build

</div>

---

<div align="center">

<h1>Project layout</h1>

</div>

```text
android/            Android app, Gradle module and platform layers
shared/kotlin/      Compose UI, editor model, engine bridge
shared/cpp/         Native drawing, media and export engine
shared/res/         Translations and Android resources
shared/assets/      Effects, shapes, preset metadata, fonts
tools/              Build, verification and quality checks
testdata/           Test scenes and media samples
docs/               Repository artwork
```

The repository intentionally excludes generated native libraries, downloaded AI models, keystores, local SDK paths and APKs. Native binaries and signed APKs are attached to releases, and the Depth Anything V2 model downloads on demand inside the app.

---

<div align="center">

<h1><a id="credits"></a>Credits</h1>

**Schnitt** is built on the open-source AbsentMotion engine and UI, then extended with a new identity, theme, presets, audio library, plugin host, AEX importer, real Depth Anything V2 inference, transform recording and puppet workflow.

Special thanks to the upstream projects and libraries that make this possible:

- **Skia** — GPU drawing and text
- **ncnn / RIFE** — AI inference and frame interpolation
- **whisper.cpp** — on-device captions
- **RNNoise** — noise reduction
- **LAME** — MP3 encoding
- **ONNX Runtime** — Depth Anything V2
- **Media3 / ExoPlayer** — preview playback
- **OkHttp** — streaming audio transport
- **Kotlin, Jetpack Compose and AndroidX**

Third-party licences are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

</div>

---

<div align="center">

<h1>Free and open</h1>

Schnitt has **no paywall, no account requirement, and no locked presets**. If you edit with it, keep the credits and share what you make.

</div>

---

<div align="center">

<h1>License</h1>

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See the [LICENSE](LICENSE) file for details.

</div>
