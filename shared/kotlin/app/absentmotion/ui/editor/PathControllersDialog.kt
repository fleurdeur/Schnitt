package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.PathControllerOptions
import app.absentmotion.editor.PointMode
import app.absentmotion.editor.bakePathControllers
import app.absentmotion.editor.createPathControllers
import app.absentmotion.editor.pathControllers
import app.absentmotion.editor.setPathControllerScale
import app.absentmotion.editor.setPathControllersParented
import app.absentmotion.editor.setPathControllersVisible
import app.absentmotion.ui.editor.panels.ThinSlider
import app.absentmotion.ui.editor.panels.ThinSwitch
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

/**
 * Lightweight controller palette for Animate Path Points.  The controls stay
 * out of the way while drawing and expose the AE-style rig in one sheet.
 */
@Composable
internal fun PathControllersDialog(state: EditorState, mode: PointMode, open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = state.pathControllers(mode.layerId)
    var show by remember(existing.size) { mutableStateOf(existing.none { it.hidden }) }
    var handles by remember { mutableStateOf(existing.any { it.node["pathHandle"] != "point" }) }
    var parented by remember { mutableStateOf(existing.all { it.node["parent"]?.toLongOrNull() == mode.layerId }) }
    var scale by remember { mutableStateOf(existing.firstOrNull { it.node["pathHandle"] == "point" }?.node?.child("transform")?.child("scale")?.get("value")?.split(',')?.firstOrNull()?.toFloatOrNull() ?: 1f) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    Dialog({ onDismiss() }, DialogProperties(usePlatformDefaultWidth = true)) {
        Column(
            Modifier.widthIn(min = 320.dp, max = 430.dp).clip(RoundedCornerShape(18.dp)).background(Am.Panel)
                .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ANIMATE PATH POINTS", color = Am.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("${state.scene.layer(mode.layerId)?.label ?: "Path"} · ${existing.size} controllers", color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
                }
                Text("CLOSE", Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss).padding(8.dp), color = Am.OnDarkDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Show nulls", Modifier.weight(1f), color = Am.OnDark, fontSize = 13.sp)
                ThinSwitch(show) { on -> show = on; state.setPathControllersVisible(mode.layerId, on) }
            }
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bezier tangent handles", Modifier.weight(1f), color = Am.OnDark, fontSize = 13.sp)
                ThinSwitch(handles) { on -> handles = on }
            }
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Parent to layer", Modifier.weight(1f), color = Am.OnDark, fontSize = 13.sp)
                ThinSwitch(parented) { on -> parented = on; scope.launch { state.setPathControllersParented(mode.layerId, on, state.sampleMs) } }
            }
            Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Controller scale", Modifier.widthIn(min = 112.dp), color = Am.OnDark, fontSize = 13.sp)
                ThinSlider(scale, { scale = it; state.setPathControllerScale(mode.layerId, it) }, Modifier.weight(1f), valueRange = 0.2f..3f)
                Text("${(scale * 100).toInt()}%", Modifier.padding(start = 8.dp), color = Am.OnDarkDim, fontSize = 10.sp)
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PathAction("${if (existing.isEmpty()) "CREATE NULLS" else "REBUILD"}", Modifier.weight(1f), filled = existing.isEmpty()) {
                    scope.launch {
                        val made = state.createPathControllers(mode, PathControllerOptions(show, handles, parented, scale))
                        toast(if (made == 0) "No path points to rig" else "$made controllers created")
                    }
                }
                PathAction("BAKE ANIMATION", Modifier.weight(1f), filled = false) {
                    scope.launch {
                        val keys = state.bakePathControllers(mode)
                        toast(if (keys == 0) "Animate a null first, then bake" else "$keys shape keys baked")
                    }
                }
            }
            Text("Nulls are ordinary layers: keyframe them like AE null controllers. Bake writes shape keys, so export and undo stay compatible.", color = Am.Caption, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun PathAction(label: String, modifier: Modifier, filled: Boolean, onClick: () -> Unit) {
    Text(
        label, modifier.clip(RoundedCornerShape(7.dp)).background(if (filled) Am.Accent else Am.Button).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp), color = if (filled) Am.OnAccent else Am.OnDark,
        fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    )
}
