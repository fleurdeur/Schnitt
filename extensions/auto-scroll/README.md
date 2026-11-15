# Auto Scroll

Auto Scroll keeps the timeline's vertical scroll on the layer that is playing. When playback moves into another layer, the timeline eases to that row instead of jumping.

It only changes the timeline scroll position. It does not change the scene, the playhead, the playback clock or the playback speed.

## Properties

- **Follow during playback** - turn the behavior off without removing the extension.
- **Follow** - follow the layer under the playhead, or keep the selected layer in view.
- **Snap layer to centre** - centre the active row. Turn this off to scroll only when the row leaves the comfortable middle band.
- **Scroll speed** - 0.25x to 3x. This changes only the easing time, not playback.
- **Smoothing time** - how long the ease takes before speed is applied.

## Package

Install `auto-scroll.sc` from **Home -> Tools -> Extensions -> Import .sc**. Restart once after installing or changing a property.
