# Schnitt

Schnitt is an Android video editor built for fast, direct timeline work. It combines a native GPU drawing engine with a Compose editor: layer animation, text, shapes, effects, audio, masks, templates, export presets, plugins, and AI-assisted tools.

## Highlights

- **Timeline editor** with layers, groups, masks, keyframes, easing, speed, time remapping, and transform recording.
- **Puppet mesh** with Pin, Mesh and Sway sections for bending layers.
- **Effects and shaders** for colour, blur, distortion, light, retro, text and motion, with a shader-based effect browser.
- **Audio tools** including waveform editing, audio effects, effect-preset JSON import, beat detection, and audio extraction up to 320 kbps.
- **Audio library** with Mixkit sound effects/music, custom page loading, Deezer/Bandcamp/SoundCloud fallbacks, and an optional yt-dlp add-on.
- **Plugins** with ZXP, JSX, JSXBIN, CEP panel hosting, AEX metadata import and shader porting, draggable panels, and per-layer application.
- **Depth Anything V2** on video layers using on-device ONNX inference for real depth maps.
- **Export** to video, audio, GIF and projects with device-aware encoder fallbacks.
- **AMOLED green theme** plus a green Daylight theme and high-contrast text.
- **Universal release** for ARM64, ARMv7 and x86_64.

## Requirements

- Android Studio with SDK 37 and build-tools 37
- NDK 30.0.16248370 for a native-engine build
- JDK 21 or newer
- CMake 3.31.6 and Ninja
- Skia and the native third-party sources for a from-source native build

The repository intentionally excludes generated native libraries, downloaded AI models, signing keys, local SDK paths and release APKs. Prebuilt native libraries are attached to the release, and the Depth Anything V2 model is downloaded on demand by the app.

## Build

```bash
./gradlew :app:assembleFossRelease -PamAbi=arm64-v8a
./gradlew :app:assembleFossRelease -PamAbi=universal
```

## Project layout

```
android/            Android app, Gradle module and platform code
shared/kotlin/      Shared Compose UI, editor model and engine bridge
shared/cpp/         Native drawing and media engine
shared/res/         Translations and Android resources
shared/assets/      Effects, shapes, audio/preset metadata, fonts
tools/              Build, verification and quality checks
testdata/           Test scenes and media samples
```

## Releases

Release APKs are published under **fleurdeur/Nova**:

- `Schnitt-5.1.3-armv7-release.apk`
- `Schnitt-5.1.3-arm64-v8a-release.apk`
- `Schnitt-5.1.3-universal-release.apk`

## Licence

See `LICENSE` and `THIRD_PARTY_NOTICES.md`. Third-party libraries and assets keep their own licences.
