# Auto Scroll

Auto Scroll follows the layer that is actually playing, starting from the bottom and ignoring the topmost layer by default. It can also keep the selected layer in view or sweep through every row.

It only changes the timeline scroll position. It does not change the scene, the playhead, the playback clock or the playback speed.

## Properties

- **Follow during playback** - turn the behavior off without removing the extension.
- **Follow** - follow the playing layer, keep the selected layer in view, or scroll through layers.
- **Start from** - start at the bottom layer or the top layer.
- **Skip layers at top** - leave the top 1-100 layers out of following. Use this when a full-length overlay sits above the layers you want to watch.
- **Include hidden layers** - include hidden rows when scrolling through layers.
- **Snap layer to centre** - centre the active row. Turn this off to scroll only when the row leaves the comfortable middle band.
- **Scroll speed** - 0.25x to 3x. This changes only the easing time, not playback.
- **Smoothing time** - how long the ease takes before speed is applied.

## Scroll through layers

Scroll through layers is the default mode. It starts at the first bottom layer and gradually rises to the top as the project plays. It does not jump to a pinned top layer first. Every row gets its turn, even when a layer above it covers the whole project. Set **Start from** to Top layer to run the sweep in reverse, and use **Skip layers at top** for layers you never want the follow position to land on.

## Package

Install `auto-scroll.sc` from **Home -> Tools -> Extensions -> Import .sc**. Restart once after installing or changing a property.
