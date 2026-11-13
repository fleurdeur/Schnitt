package app.absentmotion.editor

import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.model.valueAt
import app.absentmotion.model.withKeyframes

private val Recorded = listOf(
    PropRef.Location to "0,0,0",
    PropRef.Scale to "1,1",
    PropRef.Rotation to "0",
    PropRef.Opacity to "1",
)

/** Makes the transform properties keyframed so edits while recording create keys. */
internal fun EditorState.ensureTransformKeyframes(layer: Layer) {
    for ((ref, fallback) in Recorded) {
        val element = layer.element(ref)
        if (element?.keyframes?.isNotEmpty() == true) continue
        val value = element?.valueAt(layer.t) ?: fallback
        val node = (element ?: XmlNode(ref.name, mapOf("value" to value)))
            .withKeyframes(listOf(Keyframe(0f, value), Keyframe(1f, value)))
        editSelected("record-transform") { it.withElement(ref, node) }
    }
}

fun EditorState.toggleTransformRecord() {
    if (recordingTransform) {
        recordingTransform = false
        playing = false
        return
    }
    val layer = selected ?: return
    ensureTransformKeyframes(layer)
    recordingTransform = true
    playing = true
}
