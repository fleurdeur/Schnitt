package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.canTrack
import app.absentmotion.editor.startCameraTracking
import app.absentmotion.editor.startStabilize
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.cameraFov
import app.absentmotion.model.formatFloats
import app.absentmotion.ui.theme.Am

private data class Axis(
    val label: String, val ref: PropRef, val component: Int, val default: Float,
    val min: Float, val max: Float, val unit: String = "",
    val sliderMin: Float = min, val sliderMax: Float = max,
)

private val Position = listOf(
    Axis("X", PropRef.Location, 0, 0f, -100000f, 100000f, " px", -2000f, 2000f),
    Axis("Y", PropRef.Location, 1, 0f, -100000f, 100000f, " px", -2000f, 2000f),
    Axis("Z", PropRef.Location, 2, 0f, -100000f, 100000f, " px", -5000f, 5000f),
)
private val Tilt = listOf(
    Axis("Rotate X", PropRef.Tilt, 0, 0f, -89f, 89f, "°"),
    Axis("Rotate Y", PropRef.Tilt, 1, 0f, -89f, 89f, "°"),
)
private val Scale = listOf(
    Axis("Scale X", PropRef.Scale, 0, 1f, 0.01f, 100f, sliderMax = 5f),
    Axis("Scale Y", PropRef.Scale, 1, 1f, 0.01f, 100f, sliderMax = 5f),
)

/** Layer-level 3D controls and the entry point for camera solving/stabilizing. */
@Composable
internal fun ThreeDPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    var active by remember { mutableIntStateOf(0) }
    val camera = layer.kind == "camera"
    val activeAxis = activeRow(active, camera)
    SideEffect {
        state.keyTarget = when {
            activeAxis != null -> KeyTarget(activeAxis.ref, formatFloats(layer.vector(activeAxis.ref)))
            active == 100 -> KeyTarget(PropRef(PropRef.Kind.Child, "fov"), formatFloats(floatArrayOf(layer.cameraFov)))
            else -> null
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("3D layer", Modifier.weight(1f), color = Am.OnDark, fontSize = 13.sp)
            PanelSwitch(layer.node["threeD"] == "true") { on ->
                state.editSelected { l -> l.copy(node = l.node.with("threeD", if (on) "true" else null)) }
            }
        }
        if (!state.scene.depthOrder) {
            Text("Depth sorting is off. Turn it on to sort this layer against other 3D layers.", Modifier.padding(top = 2.dp, bottom = 4.dp), color = Am.Caption, fontSize = 11.sp, lineHeight = 14.sp)
            Box(
                Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(6.dp)).background(Am.Button)
                    .clickable { state.edit { it.withDepthOrder(true) } },
                Alignment.Center,
            ) { Text("Enable depth sorting", color = Am.Accent, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Section("Position", Position, state, layer, active, { active = it })
        Section("3D rotation", Tilt, state, layer, active, { active = it })
        Section("Scale", Scale, state, layer, active, { active = it })
        if (camera) {
            val fov = layer.cameraFov
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Field of view", Modifier.width(96.dp), color = Am.OnDark, fontSize = 13.sp)
                NumberBox(
                    "${formatPlain(fov)}°", if (active == 100) Am.Accent else Am.OnDark, Modifier.width(105.dp).height(32.dp),
                    onClick = {
                        active = 100
                        state.askNumber("Field of view", fov, "°", 1f, 179f) { v ->
                            state.editSelected("3d-fov") { it.withValue(PropRef(PropRef.Kind.Child, "fov"), formatFloats(floatArrayOf(v))) }
                        }
                    },
                )
                ThinSlider(
                    fov, { v ->
                        active = 100
                        state.editSelected("3d-fov") { it.withValue(PropRef(PropRef.Kind.Child, "fov"), formatFloats(floatArrayOf(v))) }
                    },
                    Modifier.weight(1f).padding(start = 6.dp), valueRange = 1f..179f,
                    onValueChangeFinished = state::endGesture,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Camera tools", color = Am.Caption, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            "Track a flat surface with four points, then let the solver animate this camera in 3D. Stabilize locks a clip against shake.",
            Modifier.padding(top = 2.dp, bottom = 6.dp), color = Am.OnDarkDim, fontSize = 11.sp, lineHeight = 14.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Action("3D camera track", camera || state.selected?.canTrack == true) { state.startCameraTracking() }
            Action("Stabilize", state.selected?.canTrack == true) { state.startStabilize() }
        }
        Text(
            if (camera) "Selected camera: ${layer.label.ifBlank { "Camera" }}" else "Select a video layer to track.",
            Modifier.padding(top = 6.dp), color = Am.Caption, fontSize = 11.sp, maxLines = 2,
        )
    }
    ExpressionDialog(state)
}

private fun Layer.vector(ref: PropRef): FloatArray = when (ref) {
    PropRef.Location -> location
    PropRef.Tilt -> tilt
    PropRef.Scale -> scale
    else -> floatArrayOf(0f)
}

private fun activeRow(active: Int, camera: Boolean): Axis? = when {
    active in 0..2 -> Position[active]
    active in 3..4 -> Tilt[active - 3]
    active in 5..6 -> Scale[active - 5]
    else -> null
}

private fun sliderRange(axis: Axis, value: Float): ClosedFloatingPointRange<Float> {
    val low = axis.sliderMin
    val high = axis.sliderMax
    if (value in low..high) return low..high
    val half = ((high - low) / 2f).coerceAtLeast(0.5f)
    return (value - half)..(value + half)
}

private fun write(state: EditorState, axis: Axis, value: Float) {
    state.editSelected("3d-${axis.ref}-${axis.component}") { layer ->
        val current = layer.vector(axis.ref).copyOf(maxOf(axis.component + 1, 2))
        current[axis.component] = value.coerceIn(axis.min, axis.max)
        layer.withValue(axis.ref, formatFloats(current))
    }
}

@Composable
private fun Section(title: String, rows: List<Axis>, state: EditorState, layer: Layer, active: Int, select: (Int) -> Unit) {
    Text(title, Modifier.padding(top = 4.dp, bottom = 2.dp), color = Am.Caption, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    rows.forEachIndexed { i, axis ->
        val index = when (axis.ref) {
            PropRef.Location -> axis.component
            PropRef.Tilt -> 3 + axis.component
            else -> 5 + axis.component
        }
        val value = layer.vector(axis.ref).getOrElse(axis.component) { axis.default }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(axis.label, Modifier.width(96.dp), color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
            NumberBox(
                formatPlain(value) + axis.unit, if (active == index) Am.Accent else Am.OnDark, Modifier.width(105.dp).height(32.dp),
                onClick = {
                    select(index)
                    state.askNumber(axis.label, value, axis.unit, axis.min, axis.max) { typed -> write(state, axis, typed) }
                },
            )
            val range = sliderRange(axis, value)
            ThinSlider(
                value, { v -> select(index); write(state, axis, v) },
                Modifier.weight(1f).padding(horizontal = 6.dp), valueRange = range,
                onValueChangeFinished = state::endGesture,
            )
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)).background(Am.Well)
                    .clickable { select(index); state.openExpression(axis.ref, axis.label, layer.vector(axis.ref)) },
                Alignment.Center,
            ) { Text("fx", color = Am.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun RowScope.Action(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(6.dp)).background(if (enabled) Am.Button else Am.Well)
            .clickable(enabled = enabled, onClick = onClick),
        Alignment.Center,
    ) { Text(label, color = if (enabled) Am.Accent else Am.OnDarkDim, fontSize = 12.sp, maxLines = 1) }
}
