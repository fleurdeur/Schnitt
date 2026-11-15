package app.absentmotion.extensions

import app.absentmotion.model.Scene

/** Pure playback lookup used by the Auto Scroll extension and its tests. */
object AutoScroll {
    fun activeLayerId(scene: Scene, frame: Int, fps: Int): Long? {
        val ms = frame.coerceAtLeast(0) * 1000L / fps.coerceAtLeast(1)
        return scene.layers.withIndex()
            .filter { (_, layer) -> !layer.hidden && layer.startTime <= ms && layer.endTime >= ms }
            .maxWithOrNull(compareBy<IndexedValue<app.absentmotion.model.Layer>>({ it.value.startTime }, { it.index }))
            ?.value?.id
    }
}
