package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.toggleKeyframe
import app.absentmotion.model.PropRef
import app.absentmotion.model.effectProperty
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.valueAt
import app.absentmotion.ui.editor.panels.EffectSettings
import app.absentmotion.ui.editor.panels.PUPPET_ID
import app.absentmotion.ui.editor.panels.PuppetSection
import app.absentmotion.ui.editor.panels.puppetPoints
import app.absentmotion.ui.editor.panels.rememberPinsFull
import app.absentmotion.ui.theme.Am

private val PuppetTabs = listOf(
    Triple(0, R.string.puppet_pin_tab, PanelIcons.Pin),
    Triple(1, R.string.puppet_mesh_tab, PanelIcons.EditShape),
    Triple(2, R.string.puppet_sway_tab, PanelIcons.Animate),
)

@Composable
internal fun PuppetPanel(state: EditorState, modifier: Modifier = Modifier) {
    val layer = state.selected
    val index = state.effectIndex
    val instance = layer?.effects?.getOrNull(index)?.takeIf { it["id"] == PUPPET_ID }
    if (layer == null || instance == null) {
        LaunchedEffect(Unit) { state.puppetOpen = false }
        return
    }
    val definition = state.engine.effects.value.find(PUPPET_ID)
    var tab by remember { mutableIntStateOf(0) }
    var pin by remember(layer.id, index) { mutableStateOf<String?>(null) }
    var field by remember(layer.id, index) { mutableStateOf("position") }
    var adding by remember(layer.id, index) { mutableStateOf(false) }
    var active by remember(layer.id, index, tab) { mutableStateOf<app.absentmotion.effects.EffectParam?>(null) }
    val full = rememberPinsFull()
    val points = puppetPoints(state, layer, index, instance, pin, adding, { pin = it; field = "position"; if (it != null) active = null }, full)
    SideEffect { state.keyTarget = null; state.pointAdjust = null; state.layerPoints = points }
    DisposableEffect(Unit) { onDispose { state.layerPoints = null } }
    val filtered = definition?.let { def ->
        val ids = when (tab) {
            1 -> setOf("falloff", "density", "expansion")
            2 -> setOf("sway", "swayAmount", "swaySpeed", "swayDirection", "swayAngle", "swaySeed", "swayPins")
            else -> emptySet()
        }
        def.copy(params = def.params.filter { it.id in ids })
    }
    LaunchedEffect(tab) {
        if (tab == 2) {
            listOf("swayAmount", "swaySpeed", "swayAngle").forEach { id ->
                val ref = PropRef.effect(index, id)
                state.keyTarget = KeyTarget(ref, "0", "float")
                if (state.selected?.onKeyframe(ref) != true) state.toggleKeyframe()
            }
        }
    }
    Row(modifier.fillMaxWidth().height(244.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Am.Panel)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        PuppetSection(state, layer, index, instance, pin, field, { field = it }, adding, { pin = it; field = "position" }, { adding = it })
                    }
                    1 -> if (filtered != null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        EffectSettings(state, layer, instance, filtered, active) { active = it }
                    }
                    else -> if (filtered != null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        EffectSettings(state, layer, instance, filtered, active) { active = it }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(17.dp)).background(Am.Accent)
                                    .clickable {
                                        state.editSelected { current ->
                                            current.withEffect(index) { effect ->
                                                var next = effect
                                                for ((id, value) in listOf(
                                                    "sway" to "1", "swayPins" to "0", "swayAmount" to "18",
                                                    "swaySpeed" to "1", "swayDirection" to "1", "swayAngle" to "0",
                                                )) next = next.withEffectProperty(id, "float", value, current.t, current.snap)
                                                next
                                            }
                                        }
                                    },
                                Alignment.Center,
                            ) { Text("Auto Sway", color = Am.OnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            Box(
                                Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(17.dp)).background(Am.Button)
                                    .clickable {
                                        listOf("swayAmount", "swaySpeed", "swayAngle").forEach { id ->
                                            val ref = PropRef.effect(index, id)
                                            state.keyTarget = KeyTarget(ref, "0", "float")
                                            if (state.selected?.onKeyframe(ref) != true) state.toggleKeyframe()
                                        }
                                    },
                                Alignment.Center,
                            ) { Text(stringResource(R.string.puppet_keyframe_sway), color = Am.OnDark, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(52.dp).background(Am.Button)) {
                PuppetTabs.forEach { (id, label, icon) ->
                    val on = tab == id
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clickable { tab = id; active = null },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(icon, null, Modifier.size(18.dp), tint = if (on) Am.Accent else Am.OnDark)
                        Text(stringResource(label), color = if (on) Am.Accent else Am.OnDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
        Column(Modifier.width(52.dp).fillMaxHeight().background(Am.Button)) {
            PuppetRailButton("+", stringResource(R.string.puppet_zoom_in)) { state.zoomStep(true) }
            PuppetRailButton("−", stringResource(R.string.puppet_zoom_out)) { state.zoomStep(false) }
            PuppetRailButton("1:1", stringResource(R.string.puppet_reset_view)) { state.resetCanvasView() }
            Box(Modifier.weight(1f))
            PuppetRailButton("×", stringResource(R.string.action_close)) { state.puppetOpen = false }
        }
    }
}

@Composable
private fun PuppetRailButton(label: String, description: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick), Alignment.Center) {
        Text(label, color = Am.OnDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
