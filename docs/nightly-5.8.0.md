# Schnitt 5.8.0 nightly notes

This experimental build adds the latest Raster Extrude, 3D slider and performance work on top of the MCP agent, expression editing and model-part changes.

## Raster Extrude

- Raster Extrude is Alight Motion's Raster Extrude effect. Imported XML and links now keep all of its settings instead of reporting them as lost.
- Added the missing Shading Type, Ambient, Specularity, Light Color, Light Direction, Elevation, Interior Blend, Fade Color and Fade Blend controls.
- Added Width, Height and Edge Strength controls so imported extrusions keep their shape and march quality.
- Light, shine, shading, depth, interior color, position, scale and rotation continue to work and keep their keyframes.
- Existing saved Raster Extrude effects keep their old look.

## 3D layer controls

- The 3D layer panel now has touch sliders for position, rotation, scale and camera field of view, not only number boxes.
- Slider drags group their changes into one undo step.

## Performance

- Adaptive preview reacts to real render times, not only the selected quality.
- Heavy scenes automatically lower the background preview and pre-render resolution in steps, while light scenes keep full detail.
- Depth maps use the model's native 518 px size, sample long clips at a lower rate and choose parallel workers by backend.
- Scene size, fps, total time, layer maps, media maps and effect lists are cached, so large projects avoid re-parsing the same data every frame.

## MCP agent

- New MCP tile in the Tools tab, using the app palette and icon set.
- The tile opens a control panel with the server switch, endpoint, bearer token and the full live tool list.
- Tokens can be copied straight into an MCP client.
- The server stays off by default and only runs while the editor is open.
- More agent tools: property list, keyframe list and remove, expression get, set and eval.

## Expression editor

- Redesigned to match the editor: dark panel, accent chips, monospace field and a live curve.
- Variables and common functions insert at the cursor.
- Baking still writes ordinary keyframes, so export, undo and every importer keep working.

## Model parts

- glTF nodes now become a true parented rigid part hierarchy.
- A part inherits the nearest animated ancestor, not only a direct parent.
- Fixed native validation that rejected models with root parts.
- Part position, rotation and scale remain keyframable per part.

## Directional Blur and imports

- Directional Blur rebuilt on a 65-sample gather adapted from Keyflow's directional blur.
- Bundled Alight copies of effects that already have a native implementation are no longer listed twice.
- Imported Alight Motion Blur resolves to Schnitt's own keyframe-aware Motion Blur.

## Reliability

- Full unit suite passes, including the new Raster Extrude migration and keyframe tests.
- Preview now allows the full 1920 px long side and keeps one more frame cached.
- Read-only project folders and odd file URIs are handled safely.
