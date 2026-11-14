package app.absentmotion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.GradientStyle
import app.absentmotion.model.withGradient
import app.absentmotion.model.XmlNode
import app.absentmotion.model.addEffect
import app.absentmotion.ui.editor.SearchField
import app.absentmotion.ui.editor.effects.Thumbnail
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PresetRecipe(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val effects: List<String>,
    val color: Int = 0xFFFFFFFF.toInt(),
)

private val Presets = listOf(
    PresetRecipe("clean-warm", "Warm Clean", "Color", "Soft warm colour, lifted shadows and a clean contrast curve for everyday footage.", listOf("brightnesscontrast", "huesaturation", "tint")),
    PresetRecipe("cold-cinema", "Cold Cinema", "Color", "Teal shadows and cool highlights with a restrained, cinematic contrast.", listOf("colorbalance", "levels", "tint")),
    PresetRecipe("film-fade", "Film Fade", "Color", "Muted colours, faded blacks and gentle film grain.", listOf("levels", "filmgrain", "huesaturation")),
    PresetRecipe("amber-night", "Amber Night", "Color", "Warm amber highlights for night scenes without losing the shadows.", listOf("temperature", "exposure", "glow")),
    PresetRecipe("micro-shake", "Micro Shake", "Shake", "A small handheld shake that gives static shots a natural pulse.", listOf("autoshake")),
    PresetRecipe("beat-shake", "Beat Shake", "Shake", "A stronger shake that lands on every beat. Pair it with Mark Beats.", listOf("beatshake", "beatglitch")),
    PresetRecipe("camera-glitch", "Camera Glitch", "Shake", "Short digital glitches with chromatic separation.", listOf("glitch", "chromaticzoomblur")),
    PresetRecipe("handheld", "Handheld", "Shake", "A loose, organic camera drift for documentary edits.", listOf("autoshake", "directionalblur")),
    PresetRecipe("soft-glow", "Soft Glow", "Light", "A gentle bloom around bright areas without washing out the image.", listOf("softglow", "exposure")),
    PresetRecipe("neon-edge", "Neon Edge", "Light", "Bright contour edges with a coloured glow.", listOf("contourlines", "edgeglow", "glow")),
    PresetRecipe("vhs-1987", "VHS 1987", "Retro", "Tape wobble, scan noise and a faded analogue palette.", listOf("vhs", "filmgrain", "levels")),
    PresetRecipe("dream-blur", "Dream Blur", "Motion", "A soft diffusion and slow directional blur for dreamy transitions.", listOf("softglow", "directionalblur", "blur")),
    PresetRecipe("speed-smear", "Speed Smear", "Motion", "Motion smear that stretches fast moves into clean streaks.", listOf("motionblur", "directionalblur")),
    PresetRecipe("pixel-fade", "Pixel Fade", "Retro", "Posterised colour with a blocky pixel reveal.", listOf("posterize", "pixelate", "filmgrain")),
)

@Composable
internal fun PresetsScreen(onClose: () -> Unit, onUse: (Scene) -> Unit) {
    val context = LocalContext.current
    val catalog by produceState<EffectCatalog?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { EffectCatalog.read(context) }.getOrNull() }
    }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    val scope = rememberCoroutineScope()
    val community by produceState<List<CommunityPreset>>(emptyList()) { value = CommunityPresets.load() }
    var communityType by remember { mutableStateOf("preset") }
    var communityBusy by remember { mutableStateOf<String?>(null) }
    var communityNotice by remember { mutableStateOf<String?>(null) }
    fun useCommunity(item: CommunityPreset) {
        if (communityBusy != null) return
        communityBusy = item.id
        scope.launch {
            val scene = CommunityPresets.scene(item.url)
            if (scene == null) communityNotice = "This community file could not be opened."
            else onUse(scene)
            communityBusy = null
        }
    }
    val communityShown = community.filter { it.type == communityType && (search.isBlank() || it.title.contains(search, true) || it.author.contains(search, true)) }
    val categories = listOf("All") + Presets.map { it.category }.distinct()
    val shown = Presets.filter { (category == "All" || it.category == category) && (search.isBlank() || it.name.contains(search, true) || it.description.contains(search, true)) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Am.HomeBg).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onClose), Alignment.Center) {
                    androidx.compose.material3.Icon(ExportIcons.Back, stringResource(R.string.action_close), Modifier.size(24.dp), tint = Am.Navy)
                }
                Text(stringResource(R.string.presets_title), Modifier.weight(1f), color = Am.Navy, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            SearchField(search, { search = it }, Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(38.dp), hint = stringResource(R.string.presets_search), background = Am.Card, content = Am.Navy, faint = Am.HomeDim)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                categories.forEach { item ->
                    val on = item == category
                    Box(
                        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Am.Navy else Am.Card).clickable { category = item }
                            .padding(horizontal = 12.dp), Alignment.Center,
                    ) { Text(item, color = if (on) Am.OnNavy else Am.HomeInk, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                item(key = "community-header") {
                    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Community", Modifier.weight(1f), color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Approved by fleurdeur", color = Am.HomeDim, fontSize = 10.sp)
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf("preset" to "Presets", "xml" to "XML").forEach { (id, label) ->
                                val on = id == communityType
                                Box(
                                    Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Am.Navy else Am.Card)
                                        .clickable { communityType = id }.padding(horizontal = 12.dp), Alignment.Center,
                                ) { Text(label, color = if (on) Am.OnNavy else Am.HomeInk, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
                            }
                        }
                    }
                }
                communityNotice?.let { notice ->
                    item(key = "community-notice") { Text(notice, Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Am.HomeDim, fontSize = 12.sp) }
                }
                if (communityShown.isEmpty()) {
                    item(key = "community-empty") {
                        Text("No approved ${if (communityType == "preset") "presets" else "XML files"} yet. Upload yours on the Schnitt community page.", Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Am.HomeDim, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                } else {
                    items(communityShown.distinctBy { it.id }, key = { "community-" + it.id }) { item ->
                        CommunityCard(item, communityBusy == item.id) { useCommunity(item) }
                    }
                }
                item(key = "presets-header") {
                    Text("Built-in presets", Modifier.padding(top = 12.dp), color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                items(shown, key = { it.id }) { preset ->
                    PresetCard(preset, catalog) { if (catalog != null) onUse(buildScene(preset, catalog)) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun CommunityCard(item: CommunityPreset, busy: Boolean, onUse: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Am.Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(11.dp)).background(Am.HomeBg), Alignment.Center) {
            Text(if (item.type == "xml") "XML" else "PRE", color = Am.HomeAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, color = Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.author, Modifier.padding(top = 2.dp), color = Am.HomeDim, fontSize = 11.sp, maxLines = 1)
        }
        Box(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(Am.Navy).clickable(enabled = !busy, onClick = onUse).padding(horizontal = 14.dp), Alignment.Center) {
            Text(if (busy) "Opening…" else "Use", color = Am.OnNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PresetCard(preset: PresetRecipe, catalog: EffectCatalog?, onUse: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Am.Card).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Am.HomeBg), Alignment.Center) {
                Image(painterResource(R.drawable.preset_placeholder), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                val effect = preset.effects.firstNotNullOfOrNull { id -> catalog?.let { c -> c.find("absent.$id") ?: c.definitions.firstOrNull { it.key == id } } }
                if (effect != null && catalog != null) Thumbnail(catalog, effect.thumbnail, Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(7.dp)), ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(preset.name, color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(preset.category + " · " + preset.effects.size + " effects", Modifier.padding(top = 2.dp), color = Am.HomeDim, fontSize = 11.sp, maxLines = 1)
                Text(preset.description, Modifier.padding(top = 4.dp), color = Am.HomeMid, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            preset.effects.forEach { id ->
                Text(id, Modifier.clip(RoundedCornerShape(8.dp)).background(Am.HomeBg).padding(horizontal = 7.dp, vertical = 3.dp), color = Am.HomeDim, fontSize = 9.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Am.Navy).clickable(onClick = onUse).padding(horizontal = 14.dp), Alignment.Center) {
                Text(stringResource(if (catalog == null) R.string.about_loading else R.string.presets_use), color = Am.OnNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun buildScene(preset: PresetRecipe, catalog: EffectCatalog?): Scene {
    val base = Scene.create(preset.name, 1080, 1920, 30, 0xFF101010.toInt())
    val (start, end) = when (preset.category) {
        "Color" -> 0xFF497C6A.toInt() to 0xFFC78C63.toInt()
        "Shake" -> 0xFF6D7485.toInt() to 0xFF262B35.toInt()
        "Light" -> 0xFF2A3A32.toInt() to 0xFF9ED9B0.toInt()
        "Retro" -> 0xFF7A5B3A.toInt() to 0xFF2A1D2F.toInt()
        "Motion" -> 0xFF3B5A78.toInt() to 0xFFB26F68.toInt()
        else -> 0xFF556B5F.toInt() to 0xFFD6B36A.toInt()
    }
    var layer = Layer(XmlNode("shape", linkedMapOf("id" to "${Layer.newId()}", "label" to preset.name,
        "startTime" to "0", "endTime" to "5000", "fillType" to "gradient", "s" to ".rect")))
        .withTransform("location", 540f, 960f, 0f)
        .withProp("size", "vec2", 540f, 960f)
        .withFill("gradient")
        .withGradient(GradientStyle("linear", start, end, 0f, 0f, 1f, 1f))
    preset.effects.forEach { id ->
        val definition = catalog?.find("absent.$id") ?: catalog?.definitions?.firstOrNull { it.key == id }
        if (definition != null) layer = layer.addEffect(definition.instance(null, 5000))
    }
    return base.add(layer)
}
