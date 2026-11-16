package app.absentmotion.ui.editor.panels

import app.absentmotion.ui.editor.deskVerticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ui.theme.Am

private val ModelParams = listOf(
    EffectParam("size", "", "float", "300", 1f, 20000f, 1f) to R.string.model_param_size,
    EffectParam("spin", "", "angle", "0", -3600f, 3600f, 1f) to R.string.model_param_auto_rotate,
    EffectParam("lightAngle", "", "angle", "135", -3600f, 3600f, 1f) to R.string.model_param_light_angle,
    EffectParam("ambient", "", "float", "0.35", 0f, 1f, 0.01f) to R.string.model_param_ambient_light,
    EffectParam("intensity", "", "float", "1", 0f, 4f, 0.01f) to R.string.model_param_light_intensity,
)

@Composable
internal fun ModelPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    var active by remember { mutableStateOf(ModelParams[0].first.id) }
    val parts = remember(layer.id, layer.node) {
        layer.node.child("parts")?.get("names")?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }
    var part by remember(layer.id) { mutableStateOf(0) }
    SideEffect { state.keyTarget = KeyTarget(PropRef.shape(active), layer.propText(active) ?: "0") }
    Column(modifier.deskVerticalScroll()) {
        if (!app.absentmotion.platform.PlatformFeatures.desktop) MixPiecesRow(state, layer)
        if (parts.size > 1) {
            Text("Model parts", Modifier.padding(start = 12.dp, top = 6.dp), color = Am.OnDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).horizontalScroll(rememberScrollState())) {
                parts.forEachIndexed { index, name ->
                    Box(
                        Modifier.padding(end = 4.dp).height(26.dp).clip(RoundedCornerShape(13.dp))
                            .background(if (index == part) Am.Accent else Am.Well)
                            .clickable { part = index; active = "part${index}x" }.padding(horizontal = 9.dp),
                        Alignment.Center,
                    ) { Text(name.take(18), color = if (index == part) Am.OnAccent else Am.OnDark, fontSize = 10.sp) }
                }
            }
            val chosen = part.coerceIn(0, parts.lastIndex)
            val axes = listOf(
                EffectParam("part${chosen}x", "", "float", "0", -10000f, 10000f, 1f) to "Part X",
                EffectParam("part${chosen}y", "", "float", "0", -10000f, 10000f, 1f) to "Part Y",
                EffectParam("part${chosen}z", "", "float", "0", -10000f, 10000f, 1f) to "Part Z",
                EffectParam("part${chosen}rx", "", "angle", "0", -3600f, 3600f, 1f) to "Part rotate X",
                EffectParam("part${chosen}ry", "", "angle", "0", -3600f, 3600f, 1f) to "Part rotate Y",
                EffectParam("part${chosen}rz", "", "angle", "0", -3600f, 3600f, 1f) to "Part rotate Z",
                EffectParam("part${chosen}s", "", "float", "1", 0.05f, 20f, 0.01f) to "Part scale",
            )
            for ((plain, label) in axes) {
                EffectNumbers(state, plain.copy(label = label), layer.propText(plain.id) ?: plain.default, true, active == plain.id, { active = plain.id }) { text, gesture ->
                    active = plain.id
                    state.editSelected(if (gesture) "model-${plain.id}" else null) { it.withProp(plain.id, "float", text) }
                }
            }
        }
        for ((plain, label) in ModelParams) {
            val param = plain.copy(label = stringResource(label))
            EffectNumbers(state, param, layer.propText(param.id) ?: param.default, true, active == param.id, { active = plain.id }) { text, gesture ->
                active = plain.id
                state.editSelected(if (gesture) "model-${param.id}" else null) { it.withProp(param.id, "float", text) }
            }
        }
        if (app.absentmotion.platform.PlatformFeatures.desktop) MixPiecesRow(state, layer)
    }
}
