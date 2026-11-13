package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.audio.AudioFx
import app.absentmotion.audio.AudioPresets
import app.absentmotion.audio.FxKind
import app.absentmotion.audio.MAX_AUDIO_FX
import app.absentmotion.audio.audioEffects
import app.absentmotion.audio.updateAudioEffect
import app.absentmotion.audio.withAudioEffects
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.editor.panels.CardSwap
import app.absentmotion.ui.editor.panels.sliderSemantics
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.PressIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication

private class AudioRecipe(val name: String, val effects: List<AudioFx>)

private val Tone = listOf(
    AudioRecipe("Bass Boost", listOf(AudioFx.of(FxKind.Eq, "lowFreq" to 110f, "lowGain" to 8f, "mid1Freq" to 300f, "mid1Gain" to -2f))),
    AudioRecipe("Warm", listOf(AudioFx.of(FxKind.LowPass, "freq" to 6000f, "resonance" to 0.8f))),
    AudioRecipe("Air", listOf(AudioFx.of(FxKind.Eq, "highFreq" to 12000f, "highGain" to 6f, "mid2Freq" to 3000f, "mid2Gain" to 2f))),
    AudioRecipe("Vocal Clarity", listOf(AudioFx.of(FxKind.HighPass, "freq" to 90f), AudioFx.of(FxKind.Eq, "mid2Freq" to 3200f, "mid2Gain" to 4f, "highGain" to 3f))),
    AudioRecipe("Telephone", listOf(AudioFx.of(FxKind.HighPass, "freq" to 300f, "slope" to 24f), AudioFx.of(FxKind.LowPass, "freq" to 3400f, "slope" to 24f))),
    AudioRecipe("Muffle", listOf(AudioFx.of(FxKind.LowPass, "freq" to 900f, "resonance" to 1.4f))),
    AudioRecipe("Radio", listOf(AudioFx.of(FxKind.HighPass, "freq" to 500f), AudioFx.of(FxKind.LowPass, "freq" to 4000f, "slope" to 24f), AudioFx.of(FxKind.Eq, "mid2Freq" to 1800f, "mid2Gain" to 5f, "mid2Q" to 1.2f))),
    AudioRecipe("Cut Low", listOf(AudioFx.of(FxKind.HighPass, "freq" to 140f, "slope" to 24f))),
)

private val Dynamics = listOf(
    AudioRecipe("Compressor", listOf(AudioFx.of(FxKind.Compressor))),
    AudioRecipe("Punch", listOf(AudioFx.of(FxKind.Compressor, "threshold" to -24f, "ratio" to 6f, "attack" to 4f, "release" to 80f, "makeup" to 6f), AudioFx.of(FxKind.Eq, "lowFreq" to 120f, "lowGain" to 4f))),
    AudioRecipe("Limiter", listOf(AudioFx.of(FxKind.Compressor, "threshold" to -8f, "ratio" to 20f, "attack" to 1f, "release" to 60f, "knee" to 0f))),
    AudioRecipe("Noise Gate", listOf(AudioFx.of(FxKind.Gate, "threshold" to -45f, "attack" to 2f, "hold" to 80f, "release" to 150f, "range" to 60f))),
    AudioRecipe("Soft Gate", listOf(AudioFx.of(FxKind.Gate, "threshold" to -60f, "attack" to 8f, "hold" to 120f, "release" to 300f, "range" to 35f))),
    AudioRecipe("Vocal Level", listOf(AudioFx.of(FxKind.HighPass, "freq" to 100f), AudioFx.of(FxKind.Compressor, "threshold" to -22f, "ratio" to 3f, "attack" to 5f, "release" to 120f, "makeup" to 4f))),
)

private val Space = listOf(
    AudioRecipe("Small Room", listOf(AudioFx.of(FxKind.Reverb, "room" to 0.35f, "damping" to 0.6f, "wet" to 0.22f, "width" to 0.8f, "preDelay" to 4f))),
    AudioRecipe("Hall", listOf(AudioFx.of(FxKind.Reverb, "room" to 0.78f, "damping" to 0.45f, "wet" to 0.35f, "dry" to 0.9f, "preDelay" to 22f))),
    AudioRecipe("Stadium", listOf(AudioFx.of(FxKind.Reverb, "room" to 0.95f, "damping" to 0.3f, "wet" to 0.45f, "dry" to 0.85f, "preDelay" to 55f))),
    AudioRecipe("Plate", listOf(AudioFx.of(FxKind.Reverb, "room" to 0.7f, "damping" to 0.2f, "wet" to 0.3f, "width" to 1f, "preDelay" to 12f))),
    AudioRecipe("Echo", listOf(AudioFx.of(FxKind.Echo, "time" to 250f, "feedback" to 0.35f, "level" to 0.3f, "lowCut" to 80f))),
    AudioRecipe("Ping Pong", listOf(AudioFx.of(FxKind.Echo, "time" to 380f, "feedback" to 0.45f, "level" to 0.35f, "pingPong" to 1f, "lowCut" to 120f))),
    AudioRecipe("Slapback", listOf(AudioFx.of(FxKind.Echo, "time" to 95f, "feedback" to 0.18f, "level" to 0.22f, "lowCut" to 200f))),
    AudioRecipe("Underwater", listOf(AudioFx.of(FxKind.LowPass, "freq" to 500f, "resonance" to 2.5f, "slope" to 24f), AudioFx.of(FxKind.Reverb, "room" to 0.6f, "damping" to 0.85f, "wet" to 0.35f))),
)

private val Pitch = listOf(
    AudioRecipe("Chipmunk", listOf(AudioFx.of(FxKind.Voice, "pitch" to 7f, "thin" to 0.4f))),
    AudioRecipe("Deep Voice", listOf(AudioFx.of(FxKind.Voice, "pitch" to -6f, "bass" to 4f))),
    AudioRecipe("High Pitch", listOf(AudioFx.of(FxKind.HighPitch, "amount" to 5f))),
    AudioRecipe("Low Pitch", listOf(AudioFx.of(FxKind.LowPitch, "amount" to 5f))),
    AudioRecipe("Robot", listOf(AudioFx.of(FxKind.Voice, "pitch" to -2f, "muffle" to 0.3f, "echo" to 0.25f))),
    AudioRecipe("Whisper", listOf(AudioFx.of(FxKind.Voice, "treble" to 3f, "thin" to 0.5f, "muffle" to 0.1f))),
)

private val Sections = linkedMapOf("Tone" to Tone, "Dynamics" to Dynamics, "Space" to Space, "Pitch" to Pitch)

@Composable
internal fun AudioFxShelf(state: EditorState, modifier: Modifier = Modifier) {
    val layer = state.selected ?: return
    var section by remember(layer.id) { mutableIntStateOf(0) }
    var applied by remember(layer.id) { mutableStateOf(false) }
    var editing by remember(layer.id) { mutableIntStateOf(-1) }
    val effects = layer.audioEffects
    fun add(recipe: AudioRecipe) {
        val room = (MAX_AUDIO_FX - effects.size).coerceAtLeast(0)
        if (room == 0) return
        state.editSelected("audio-preset-${recipe.name}") { it.withAudioEffects(it.audioEffects + recipe.effects.take(room)) }
        applied = true
        editing = state.selected?.audioEffects?.lastIndex ?: -1
    }
    Column(modifier.fillMaxWidth().height(244.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Am.Panel)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (applied) Applied(state, effects, editing, { editing = it }, { applied = false })
            else {
                val recipes = when (section) {
                    0 -> Tone; 1 -> Dynamics; 2 -> Space; 3 -> Pitch
                    else -> AudioPresets.map { AudioRecipe(app.absentmotion.i18n.AppText.get(it.name), it.effects) }
                }
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(recipes, key = { it.name }) { recipe -> RecipeCard(recipe) { add(recipe) } }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(52.dp).background(Am.Button).horizontalScroll(rememberScrollState())) {
            (Sections.keys.toList() + "Presets").forEachIndexed { i, title ->
                val on = !applied && section == i
                Box(Modifier.width(74.dp).fillMaxHeight().clickable { applied = false; section = i; editing = -1 }, Alignment.Center) {
                    Text(title, color = if (on) Am.Accent else Am.OnDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Box(Modifier.width(68.dp).fillMaxHeight().clickable { applied = true }, Alignment.Center) {
                Text("Applied", color = if (applied) Am.Accent else Am.OnDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Box(Modifier.width(44.dp).fillMaxHeight().clickable { state.audioFxOpen = false }, Alignment.Center) {
                Text("×", color = Am.OnDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: AudioRecipe, add: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(9.dp)).background(Am.Well)
            .indication(press, PressIndication(Am.Accent, soft = true)).clickable(press, null, onClick = add).padding(horizontal = 9.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(recipe.name, Modifier.weight(1f), color = Am.OnDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("+", color = Am.Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Text(recipe.effects.joinToString(" · ") { it.kind?.xml ?: "fx" }, color = Am.OnDarkDim, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Applied(state: EditorState, effects: List<AudioFx>, editing: Int, onEdit: (Int) -> Unit, onBack: () -> Unit) {
    if (effects.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No audio effects yet", color = Am.OnDarkDim, fontSize = 13.sp) }
        return
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(170.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            effects.forEachIndexed { index, fx ->
                val on = index == editing
                Row(Modifier.fillMaxWidth().height(40.dp).background(if (on) Am.Chosen else Color.Transparent).clickable { onEdit(index) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fx.kind?.xml?.replaceFirstChar { it.uppercase() } ?: "Effect", Modifier.weight(1f),
                        color = if (fx.bypass) Am.OnDarkDim else Am.OnDark, fontSize = 11.sp, maxLines = 1)
                    Text("B", Modifier.padding(horizontal = 4.dp).clickable {
                        state.editSelected { it.updateAudioEffect(index) { e -> e.withBypass(!e.bypass) } }
                    }, color = if (fx.bypass) Am.Warning else Am.OnDarkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("×", Modifier.padding(horizontal = 4.dp).clickable {
                        state.editSelected { it.withAudioEffects(it.audioEffects.filterIndexed { i, _ -> i != index }) }
                        if (effects.size <= 1) onBack()
                    }, color = Am.Warning, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(6.dp)) {
            effects.getOrNull(editing.coerceAtLeast(0))?.let { fx -> FxEditor(state, editing.coerceAtLeast(0), fx) }
        }
    }
}

@Composable
private fun FxEditor(state: EditorState, index: Int, fx: AudioFx) {
    val kind = fx.kind ?: return
    kind.params.forEach { param ->
        val value = fx[param]
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(param.label), Modifier.width(90.dp), color = Am.OnDarkDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Slider(
                value = value, onValueChange = { v ->
                    state.editSelected { layer -> layer.updateAudioEffect(index) { e -> e.with(param, v) } }
                }, valueRange = param.min..param.max,
                modifier = Modifier.weight(1f).height(26.dp),
                colors = SliderDefaults.colors(thumbColor = Am.Accent, activeTrackColor = Am.Accent),
            )
            Text("%.2f".format(java.util.Locale.US, value), Modifier.width(42.dp), color = Am.OnDark, fontSize = 9.sp, maxLines = 1)
        }
    }
}
