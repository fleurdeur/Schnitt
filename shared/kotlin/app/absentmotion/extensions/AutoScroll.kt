package app.absentmotion.extensions

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene

/** Pure playback lookup used by the Auto Scroll extension and its tests. */
object AutoScroll {
    /**
     * The layer Auto Scroll follows while a project plays.
     *
     * Layers are stored bottom-first. The default tie-break is the bottom
     * layer, so an always-visible overlay at the very top no longer steals the
     * follow position from the layers underneath it. Later-starting layers
     * still win, which makes the timeline rise through the project as they
     * begin.
     */
    fun activeLayerId(scene: Scene, frame: Int, fps: Int, start: String = "bottom", skipTop: Int = 0, includeHidden: Boolean = false): Long? {
        val candidates = candidates(scene, skipTop, includeHidden)
        if (candidates.isEmpty()) return null
        val ms = frame.coerceAtLeast(0) * 1000L / fps.coerceAtLeast(1)
        val bottomFirst = start != "top"
        return candidates.withIndex()
            .filter { (_, layer) -> layer.startTime <= ms && layer.endTime >= ms }
            .maxWithOrNull(
                compareBy<IndexedValue<Layer>>({ it.value.startTime }, { if (bottomFirst) -it.index else it.index })
            )?.value?.id
    }

    /**
     * Bottom-to-top (or top-to-bottom) sweep used by the "Scroll through
     * layers" mode. It deliberately ignores layer start times: the timeline
     * starts at the first row and advances one row at a time, so every layer is
     * visible even when a full-length layer sits above it.
     */
    fun sweepLayerId(scene: Scene, frame: Int, fps: Int, start: String = "bottom", skipTop: Int = 0, includeHidden: Boolean = false): Long? {
        val candidates = candidates(scene, skipTop, includeHidden)
        if (candidates.isEmpty()) return null
        val total = scene.totalTime.coerceAtLeast(1)
        val ms = frame.coerceAtLeast(0) * 1000L / fps.coerceAtLeast(1)
        val progress = (ms.toFloat() / total).coerceIn(0f, 0.999999f)
        val bottomFirst = start != "top"
        val index = if (bottomFirst) (progress * candidates.size).toInt() else ((1f - progress) * candidates.size).toInt()
        return candidates[index.coerceIn(0, candidates.lastIndex)].id
    }

    fun targetLayerId(scene: Scene, frame: Int, fps: Int, config: AutoScrollConfig): Long? = when (config.mode) {
        "sweep" -> sweepLayerId(scene, frame, fps, config.start, config.skipTop, config.includeHidden)
        else -> activeLayerId(scene, frame, fps, config.start, config.skipTop, config.includeHidden)
    }

    private fun candidates(scene: Scene, skipTop: Int, includeHidden: Boolean): List<Layer> {
        val layers = scene.layers.filter { includeHidden || !it.hidden }
        val skip = skipTop.coerceIn(0, layers.size)
        return if (skip == 0) layers else layers.dropLast(skip)
    }
}
