# Schnitt 5.7.0 nightly notes

## MCP server

`shared/kotlin/app/absentmotion/ai/McpServer.kt` implements a JSON-RPC 2.0
streamable-HTTP MCP endpoint.

- Off by default. Enable it in Settings → AI agent (MCP).
- Listens on TCP port 8765 and advertises the LAN address.
- A generated 24-character bearer token is required for every request.
- Endpoint: `http://<phone-ip>:8765/mcp`.
- Health check: `GET /health`.
- Supported methods: `initialize`, `ping`, `tools/list`, `tools/call`.
- Notifications such as `notifications/initialized` are accepted with 202.

Tools:

| Tool | Effect |
| --- | --- |
| `scene.info` | Size, FPS, duration, selection, layer count |
| `scene.xml` | Full project XML |
| `scene.apply_xml` | Replace the project from XML (undoable) |
| `layer.list` | Layer ids, labels, kinds, time ranges |
| `layer.select` | Select a layer |
| `layer.add` | Add null, camera, light or text |
| `layer.delete` | Delete the selected layer |
| `layer.set` | Set any property from a float list |
| `keyframe.add` | Add a keyframe at an absolute time |
| `time.set` | Move the playhead and change playback |
| `edit.undo` / `edit.redo` | History control |

The bridge only answers while `EditorScreen` is composed. All mutations run on
the Compose main thread and go through the normal undo and autosave path.

## Model parts

The glTF reader now stores node parts alongside the mesh:

- `Mesh.partOfVertex` — one part index per vertex.
- `Mesh.partNames` — node names in scene order.
- `Mesh.partCenters` — normalized part pivot points.

`MeshBuilder` and the decimator preserve part assignment. `NativeEngine`
forwards the arrays to `nativeSetModel`, and `shared/cpp/model.cpp` applies a
rigid part matrix before the model body transform.

Per-part properties use the existing layer property system:

```
part0x, part0y, part0z
part0rx, part0ry, part0rz
part0s
```

They can be keyframed from the model panel or through MCP `layer.set` and
`keyframe.add`. Part animation is rigid; it does not deform vertices with a
runtime bone hierarchy.

## Engine boundaries

Schnitt keeps its own native Skia/SkSL engine. Aurea's decompiled Java and its
native library are not linked. The feature surface maps as follows:

| Aurea idea | Schnitt implementation |
| --- | --- |
| Camera track | `CameraTrack.kt` planar homography solver |
| Motion track | `MotionTrack.kt` point tracker |
| Audio to keyframes | `Beats`, `BeatActions` |
| Deep glow | `deepglow.json` SkSL |
| Native DSP | `audio_fx_jni.cpp`, `audio_dsp.h` |
| AI agent | new local MCP server |

## Release optimization

- R8 full mode.
- Resource shrinking.
- Existing release signing, per-ABI APKs and baseline profiles.
