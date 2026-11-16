# 3D camera tracking, expressions and adaptive rendering

This document covers the 5.6.0 feature set and the dependency surface it uses.

## Dependencies

| Area | Dependency | Notes |
| --- | --- | --- |
| UI | Jetpack Compose, Material 3 | `ThreeDPanel`, `ExpressionDialog`, preview quality bar |
| Rendering | Skia / SkSL through the existing native engine | Effects are ordinary `EffectDefinition` JSON + SkSL |
| Tracking | Existing `MotionTracker` / `FrameReader` | No ARCore, OpenCV or Google Play Services dependency |
| Camera solve | Kotlin only (`CameraTrack.kt`) | 8×8 normal-equation homography with partial pivoting |
| Expressions | Kotlin recursive-descent parser | No script engine or reflection; every result is baked to keyframes |
| Audio | Existing native C++ DSP (`audio_fx_jni.cpp`, `audio_dsp.h`) | EQ, compressor, gate, limiter, reverb, delay, pitch |

The 3D and expression features add no new Gradle dependencies. Camera tracking
uses the same media reader and point tracker as the existing 2D tracker.

## Camera tracking functions

`shared/kotlin/app/absentmotion/editor/CameraTrack.kt`

- `homography(from, to): Homography?` — solves the 8 unknowns of a planar
  homography from four or more point pairs.
- `rotationOf(h, fov): Rotation?` — extracts yaw, pitch and roll from the
  homography using the camera focal length.
- `Scene.cameraTrack(camera, steps, width, height, toScene, base, baseZoom)` —
  converts tracked image points into `CameraPose` values (X/Y/Z, rotation,
  tilt X/Y, FOV). Translation and scale come from the stable similarity part;
  the homography supplies perspective.
- `Scene.withCameraTrack(cameraId, poses)` — writes ordinary Location,
  Rotation and Tilt keyframes on the camera layer, preserving keys outside the
  tracked range.
- `cameraBase(camera)` — reads the camera's current transform and FOV.

`shared/kotlin/app/absentmotion/editor/TrackActions.kt`

- `EditorState.startCameraTracking()` — creates a camera when needed, seeds
  four tracking points, and enters the 3D solve.
- `EditorState.startStabilize()` — runs a two-point inverse track on the
  selected clip.
- Tracking writes keyframes through `state.edit`, so undo and autosave work
  normally.

## Expression functions

`shared/kotlin/app/absentmotion/editor/Expression.kt`

Variables: `time` (seconds), `t` (0..1), `frame`, `fps`, `width`, `height`,
`index`, `value`, `pi`.

Functions: `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `abs`, `floor`, `ceil`,
`round`, `sqrt`, `pow`, `exp`, `log`, `min`, `max`, `clamp`, `lerp`/`mix`,
`step`, `smoothstep`, `random`, `noise`, `wiggle`, `if`.

Operators: `+ - * / % ^`, comparisons, `&&`, `||`, `!`.

`Scene.bakeExpression` samples the layer's frame range at the project frame
rate and writes `Keyframe(t, value)` entries. The source text is stored in an
`<expression value="...">` child on the property. Baking means the engine does
not need a runtime expression evaluator and imported/exported XML stays fully
compatible.

## Shaders

New or updated effects are data-only and use the existing SkSL effect pipeline:

- `headbop.json` — beat bounce, sway, tilt and squash/stretch in one shader.
- `deepglow.json` — multi-tap radial glow with threshold, tint and HDR-style
  intensity.
- `lumakey.json` — range/legacy luma-key formulas.
- `wavewarp.json` — Alight-compatible cosine wave warp.
- `innershadow.json` — inner shadow with blur, X/Y offset and colour.

Uniform conventions:

- `size` / `acLayerSize` — the layer texture size.
- `screen.zw` / `acProjectSize` — the composition size.
- `blurred` — engine-generated blurred copy for glow-style effects.
- `time` — composition time in seconds.

Effects are registered in `shared/assets/effects/index.json`, versioned in
`releases.json`, described in `help/effects/*.html` and translated in
`i18n/*.json`.

## Aurea and Keyflow notes

`Documents/aur` (Aurea) is a decompiled Java project. Its useful ideas are the
camera/POI API shape, the `deep_glow` look and the audio-to-keyframe workflow.
Its native engine is not linked here; the camera solver and glow effect are
independent Kotlin/SkSL implementations so the app keeps its current build.

`Keyflow v1.6.zip` is a split APK dump, not source. Its GLSL effects use a
different control set from Alight, so effects such as Motion Tile are not
blindly mapped onto Alight `tiles`; mapping would break imported property names
and keyframes.
