package app.absentmotion.ui.editor

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.audio.BeatAnalysis
import app.absentmotion.audio.BeatBand
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.analyseBeats
import app.absentmotion.editor.bakeBeatKeyframes
import app.absentmotion.editor.beatFrames
import app.absentmotion.editor.beatSourceLayers
import app.absentmotion.model.Layer
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun BeatSheet(state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = remember(state.scene) { state.beatSourceLayers() }
    val targets = remember(state.scene) { state.scene.layers.filter { it.kind != "audio" } }
    var sourceId by remember { mutableStateOf(sources.firstOrNull { it.id == state.selectedId }?.id ?: sources.firstOrNull()?.id ?: 0L) }
    var targetId by remember { mutableStateOf(state.selectedId?.takeIf { id -> targets.any { it.id == id } } ?: targets.firstOrNull()?.id ?: 0L) }
    var sensitivity by remember { mutableFloatStateOf(1f) }
    var band by remember { mutableStateOf(BeatBand.Full) }
    var minBpm by remember { mutableIntStateOf(70) }
    var maxBpm by remember { mutableIntStateOf(180) }
    var analysis by remember { mutableStateOf<BeatAnalysis?>(null) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var notice by remember { mutableStateOf<String?>(null) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        FloatingCard("beat-window") {
        Column(Modifier.fillMaxSize().background(Am.Panel).systemBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onClose), Alignment.Center) {
                    Icon(ExportIcons.Back, stringResource(R.string.action_close), Modifier.size(24.dp), tint = Am.OnDark)
                }
                Text(stringResource(R.string.beat_title), Modifier.weight(1f), color = Am.OnDark, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) content@ {
                if (sources.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(200.dp).padding(24.dp), Alignment.Center) {
                        Text(stringResource(R.string.beat_no_audio), color = Am.OnDarkDim, fontSize = 14.sp)
                    }
                    return@content
                }
                Section(stringResource(R.string.beat_source))
                Chips(sources, sourceId, { "Audio · " + it.label.ifBlank { "layer" } }) { sourceId = it; analysis = null }
                Section(stringResource(R.string.beat_target))
                if (targets.isEmpty()) Text(stringResource(R.string.beat_no_audio), Modifier.padding(horizontal = 16.dp), color = Am.Warning, fontSize = 13.sp)
                else Chips(targets, targetId, { it.label.ifBlank { it.kind } }) { targetId = it }
                Section(stringResource(R.string.beat_sensitivity))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = sensitivity, onValueChange = { sensitivity = it; analysis = null }, valueRange = 0.3f..3f,
                        modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Am.Accent, activeTrackColor = Am.Accent),
                    )
                    Text(String.format(java.util.Locale.US, "%.2f", sensitivity), Modifier.padding(start = 12.dp).width(42.dp), color = Am.OnDark, fontSize = 13.sp)
                }
                Section("Band")
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BandChip(stringResource(R.string.beat_full), band == BeatBand.Full) { band = BeatBand.Full; analysis = null }
                    BandChip(stringResource(R.string.beat_bass), band == BeatBand.Bass) { band = BeatBand.Bass; analysis = null }
                    BandChip(stringResource(R.string.beat_vocal), band == BeatBand.Vocal) { band = BeatBand.Vocal; analysis = null }
                }
                Section("BPM range")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.beat_min_bpm), color = Am.OnDarkDim, fontSize = 12.sp)
                    Slider(
                        value = minBpm.toFloat(), onValueChange = { minBpm = it.roundToInt().coerceAtLeast(40); if (minBpm >= maxBpm) maxBpm = minBpm + 10; analysis = null },
                        valueRange = 40f..160f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Am.Accent, activeTrackColor = Am.Accent),
                    )
                    Text("$minBpm", color = Am.OnDark, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                    Text(stringResource(R.string.beat_max_bpm), color = Am.OnDarkDim, fontSize = 12.sp)
                    Slider(
                        value = maxBpm.toFloat(), onValueChange = { maxBpm = it.roundToInt().coerceAtLeast(minBpm + 10); analysis = null },
                        valueRange = 80f..220f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Am.Accent, activeTrackColor = Am.Accent),
                    )
                    Text("$maxBpm", color = Am.OnDark, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Am.Accent)
                            .clickable(enabled = !running && sourceId != 0L) {
                                running = true; progress = 0f; notice = null
                                scope.launch {
                                    try {
                                        val found = state.analyseBeats(context, sourceId, sensitivity, band, minBpm, maxBpm) { progress = it }
                                        analysis = found
                                        if (found.beats.isEmpty()) notice = context.getString(R.string.beat_failed)
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        notice = app.absentmotion.i18n.failureText(context.getString(R.string.beat_failed), e)
                                    } finally {
                                        running = false
                                    }
                                }
                            },
                        Alignment.Center,
                    ) { Text(if (running) stringResource(R.string.beat_detecting) else stringResource(R.string.beat_detect), color = Am.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    analysis?.let { found ->
                        Text(
                            "${found.bpm.roundToInt()} BPM · ${found.beats.size} beats", Modifier.align(Alignment.CenterVertically), color = Am.Accent, fontSize = 13.sp,
                        )
                    }
                }
                if (running) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(3.dp).background(Am.Well)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Am.Accent))
                }
                analysis?.let { found ->
                    val layer = state.scene.layer(sourceId)
                    if (layer != null) BeatRuler(found, layer, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
                    val frames = state.beatFrames(found, sourceId)
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(46.dp).clip(RoundedCornerShape(23.dp)).background(Am.Accent)
                            .clickable(enabled = targetId != 0L) {
                                val baked = state.bakeBeatKeyframes(found.beats, targetId)
                                Toast.makeText(context, context.getString(R.string.beat_baked, baked), Toast.LENGTH_SHORT).show()
                                if (baked > 0) onClose()
                            },
                        Alignment.Center,
                    ) {
                        Text(stringResource(R.string.beat_bake) + " · ${frames.size}", color = Am.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                notice?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Am.Warning, fontSize = 13.sp, lineHeight = 18.sp) }
                Spacer(Modifier.height(24.dp))
            }
        }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), Modifier.padding(start = 16.dp, top = 18.dp, bottom = 7.dp), color = Am.OnDarkDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Chips(layers: List<Layer>, chosen: Long, label: (Layer) -> String, onPick: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        layers.forEach { layer ->
            val active = layer.id == chosen
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (active) Am.Accent else Am.Well)
                    .clickable { onPick(layer.id) }.padding(horizontal = 12.dp),
                Alignment.Center,
            ) {
                Text(label(layer), color = if (active) Am.OnAccent else Am.OnDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BandChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (active) Am.Accent else Am.Well).clickable(onClick = onClick).padding(horizontal = 14.dp),
        Alignment.Center,
    ) { Text(label, color = if (active) Am.OnAccent else Am.OnDark, fontSize = 12.sp) }
}

@Composable
private fun BeatRuler(analysis: BeatAnalysis, layer: Layer, modifier: Modifier) {
    val span = (layer.endTime - layer.startTime).coerceAtLeast(1)
    Canvas(modifier.height(44.dp).clip(RoundedCornerShape(8.dp)).background(Am.Well)) {
        drawLine(Color(0x33FFFFFF), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
        analysis.beats.forEach { beat ->
            val x = ((beat / span).toFloat()).coerceIn(0f, 1f) * size.width
            drawLine(Am.Accent, Offset(x, size.height * 0.15f), Offset(x, size.height * 0.85f), 2.dp.toPx())
        }
    }
}
