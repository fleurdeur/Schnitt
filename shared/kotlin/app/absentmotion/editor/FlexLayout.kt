package app.absentmotion.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import kotlin.math.max

/**
 * A small dynamic layout rig for a selection of layers.  It measures every
 * layer once, packs the bounding boxes into rows, then writes normal transform
 * keyframes through the existing world-space move helper.  No duplicate layer
 * is created, so undo, parenting and export stay untouched.
 */
data class FlexOptions(
    val spacing: Float = 24f,
    val alignment: Int = 1,
    val padding: Float = 32f,
    val wrapWidth: Float = 0f,
    val distribution: Int = 0,
    val growth: Float = 1f,
)

suspend fun EditorState.applyFlexLayout(ids: Set<Long>, options: FlexOptions): Int {
    val document = scene
    val at = sampleMs
    val chosen = ids.mapNotNull { id -> scene.layer(id)?.let { layer -> engine.layerBounds(id, at)?.let { layer to it } } }
    if (chosen.size < 2 || scene.node !== document.node) return 0
    val ordered = chosen.sortedWith(compareBy({ it.second[1] }, { it.second[0] }))
    val width = if (options.wrapWidth > 0f) options.wrapWidth.coerceAtLeast(120f) else scene.width.toFloat()
    val left = options.padding.coerceAtLeast(0f)
    val available = (width - left * 2f).coerceAtLeast(40f)
    val gap = (options.spacing * options.growth).coerceAtLeast(0f)
    val rows = ArrayList<MutableList<Pair<Layer, FloatArray>>>()
    var row = ArrayList<Pair<Layer, FloatArray>>()
    var used = 0f
    for (item in ordered) {
        val w = (item.second[2] - item.second[0]).coerceAtLeast(1f)
        if (row.isNotEmpty() && used + gap + w > available) {
            rows += row
            row = ArrayList()
            used = 0f
        }
        row += item
        used += w + if (row.size > 1) gap else 0f
    }
    if (row.isNotEmpty()) rows += row
    val moves = ArrayList<Pair<Layer, Pair<Float, Float>>>()
    var top = left
    for (line in rows) {
        val heights = line.map { (it.second[3] - it.second[1]).coerceAtLeast(1f) }
        val rowHeight = heights.maxOrNull() ?: 1f
        val widths = line.map { (it.second[2] - it.second[0]).coerceAtLeast(1f) }
        val total = widths.sum() + gap * (line.size - 1).coerceAtLeast(0)
        val free = (available - total).coerceAtLeast(0f)
        val step = when (options.distribution) {
            1 -> if (line.size > 1) free / (line.size - 1) else 0f
            2 -> if (line.size > 0) free / line.size else 0f
            3 -> free / (line.size + 1)
            else -> 0f
        }
        var x = left + when (options.distribution) {
            2 -> step * 0.5f
            3 -> step
            else -> 0f
        }
        line.forEachIndexed { i, (layer, box) ->
            val h = heights[i]
            val targetY = top + when (options.alignment) { 0 -> 0f; 2 -> rowHeight - h; else -> (rowHeight - h) / 2f }
            moves += layer to (x - box[0] to targetY - box[1])
            x += widths[i] + gap + if (options.distribution == 0) 0f else step
        }
        top += rowHeight + gap
    }
    if (moves.isEmpty()) return 0
    edit { s -> moves.fold(s) { acc, (layer, delta) ->
        acc.update(layer.id, at) { current -> acc.withWorldLocation(current, at) { x, y -> x + delta.first to y + delta.second } }
    } }
    return moves.size
}

