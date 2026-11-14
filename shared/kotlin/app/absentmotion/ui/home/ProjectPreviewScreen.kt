package app.absentmotion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.PreviewView
import app.absentmotion.library.ProjectStore
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.model.MediaFillMode
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import app.absentmotion.platform.BackHandler
import app.absentmotion.platform.SceneSurface
import app.absentmotion.platform.Toast
import app.absentmotion.platform.slotPicture
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.TimelineMath
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shown after a project/XML import and before the timeline. Mirrors the
 * template-editor flow: preview first, auto-matched media below it, then the
 * imported effects. Everything can be relinked here and is persisted before
 * the timeline opens.
 */
@Composable
fun ProjectPreviewScreen(store: ProjectStore, engine: EngineThread, projectId: String, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded by produceState<Scene?>(null, projectId) { value = withContext(Dispatchers.IO) { store.load(projectId) } }
    val template = loaded
    if (template == null) {
        Box(Modifier.fillMaxSize().background(Am.HomeBg))
        return
    }
    val slots = remember(template) { slotsOf(template) }
    var choices by remember(projectId) { mutableStateOf<Map<String, Choice>>(emptyMap()) }
    var selected by remember(projectId) { mutableStateOf<Slot?>(null) }
    var playing by remember(projectId) { mutableStateOf(false) }
    var pos by remember(projectId) { mutableIntStateOf(0) }
    var box by remember(projectId) { mutableStateOf(IntSize.Zero) }
    val working = remember(template, slots, choices) { applied(template, slots, choices) }
    val total = template.totalTime.coerceAtLeast(1)
    val current by remember(selected) { mutableStateOf(selected) }

    val catalog by produceState<app.absentmotion.effects.EffectCatalog?>(null) {
        value = withContext(Dispatchers.IO) { app.absentmotion.effects.EffectCatalog.read(context) }
    }
    val effectIds = remember(template) { template.layers.flatMap { it.effects }.mapNotNull { it["id"] }.distinct() }

    BackHandler(true, onClose)
    LaunchedEffect(working) {
        withContext(Dispatchers.IO) { runCatching { store.save(projectId, working) } }
        engine.setScene(working)
    }
    DisposableEffect(Unit) { onDispose { engine.setPlaying(false) } }
    LaunchedEffect(playing) { engine.setPlaying(playing) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        if (pos >= total) pos = 0
        val from = pos
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            if (!playing) break
            val next = from + ((now - start) / 1_000_000).toInt()
            if (next >= total) { pos = total; playing = false; break }
            pos = next
        }
    }
    val fit = if (box.width == 0) 1f else min(box.width.toFloat() / working.width.coerceAtLeast(1), box.height.toFloat() / working.height.coerceAtLeast(1))
    LaunchedEffect(fit, pos, working, box) {
        if (box.width > 0) engine.setView(PreviewView(pos, fit, (box.width - working.width * fit) / 2, (box.height - working.height * fit) / 2, 0, -1))
    }

    fun timecode(ms: Int) = TimelineMath.timecode((ms * template.fps / 1000f).roundToInt(), template.fps)
    val picker = app.absentmotion.platform.rememberMediaPicker { uri ->
        val slot = current ?: return@rememberMediaPicker
        if (uri == null) return@rememberMediaPicker
        scope.launch {
            val media = mediaDeclaration(context, uri)
            if (media == null) {
                Toast.makeText(context, context.getString(R.string.library_err_unsupported_type), Toast.LENGTH_SHORT).show()
                return@launch
            }
            choices = choices + (slot.uri to Choice(media, 0, MediaFillMode.Fill))
            pos = slot.start
            playing = false
        }
    }

    Column(Modifier.fillMaxSize().background(Am.HomeBg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(ChromeIcons.Back, stringResource(R.string.home_back), Modifier.size(24.dp), tint = Am.Navy)
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(template.title.ifBlank { stringResource(R.string.home_your_projects) }, color = Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${working.width}x${working.height} · ${template.fps} fps · ${timecode(total)}", color = Am.HomeDim, fontSize = 11.sp, maxLines = 1)
            }
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Am.Accent).clickable { playing = false; onOpen(projectId) }.padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.template_go_to_timeline), color = Am.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "preview") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Am.Card).padding(10.dp)) {
                    Box(Modifier.fillMaxWidth().height(232.dp).clip(RoundedCornerShape(11.dp)).background(Am.PreviewBg).onSizeChanged { box = it }
                        .clickable { playing = !playing }) {
                        SceneSurface(engine, 1, box, Modifier.fillMaxSize())
                        if (!playing) Box(Modifier.align(Alignment.Center).size(54.dp).clip(CircleShape).background(Color(0x66000000)), Alignment.Center) {
                            androidx.compose.material3.Icon(ChromeIcons.Play, null, Modifier.size(30.dp), tint = Color.White)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Am.Navy.copy(alpha = 0.12f)).clickable { playing = !playing }, Alignment.Center) {
                            androidx.compose.material3.Icon(if (playing) ChromeIcons.Pause else ChromeIcons.Play, null, Modifier.size(22.dp), tint = Am.Navy)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Slider(
                                value = pos.toFloat().coerceIn(0f, total.toFloat()), onValueChange = { playing = false; pos = it.toInt().coerceIn(0, total) },
                                valueRange = 0f..total.toFloat().coerceAtLeast(1f),
                                colors = SliderDefaults.colors(thumbColor = Am.Accent, activeTrackColor = Am.Accent, inactiveTrackColor = Am.Well),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("00:00", color = Am.HomeDim, fontSize = 10.sp)
                                Text(timecode(pos), color = Am.Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                Text(timecode(total), color = Am.HomeDim, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            item(key = "assets_header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.media_slots), Modifier.weight(1f), color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(if (slots.isEmpty()) "No external media" else "${slots.size} matched", color = Am.HomeDim, fontSize = 11.sp)
                }
            }
            item(key = "assets") {
                if (slots.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(14.dp)) {
                        Text("All assets are embedded in this project.", color = Am.HomeDim, fontSize = 13.sp)
                    }
                } else {
                    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        itemsIndexed(slots, key = { _, slot -> slot.uri }) { _, slot ->
                            val choice = choices[slot.uri]
                            val uri = choice?.media?.uri ?: slot.uri
                            val video = choice?.media?.type?.startsWith("video/") ?: slot.video
                            val picture by produceState<ImageBitmap?>(null, uri, video) { value = slotPicture(context, uri, video) }
                            AssetCard(slot, picture, slot === selected, choice?.mode ?: slot.mode, onClick = {
                                if (slot === selected) picker.launch() else { selected = slot; playing = false; pos = slot.start }
                            })
                        }
                    }
                }
            }
            item(key = "effects_header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Imported effects", Modifier.weight(1f), color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("${effectIds.size} total", color = Am.HomeDim, fontSize = 11.sp)
                }
            }
            if (effectIds.isEmpty()) {
                item(key = "effects_empty") {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(14.dp)) {
                        Text("No layer effects found in this import.", color = Am.HomeDim, fontSize = 13.sp)
                    }
                }
            } else {
                itemsIndexed(effectIds, key = { _, id -> id }) { _, id ->
                    val effect = catalog?.definitions?.firstOrNull { it.id == id }
                    val available = effect?.available ?: true
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (available) Am.Accent.copy(alpha = 0.15f) else Am.Warning.copy(alpha = 0.16f)), Alignment.Center) {
                            Text(if (available) "✓" else "!", color = if (available) Am.Accent else Am.Warning, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(effect?.name ?: id.substringAfterLast('.'), color = Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (available) "Ported" else "Unsupported · layer kept intact", color = if (available) Am.HomeDim else Am.Warning, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetCard(slot: Slot, picture: ImageBitmap?, selected: Boolean, mode: MediaFillMode, onClick: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    Column(Modifier.width(104.dp).clip(shape).background(Am.Card).then(if (selected) Modifier.border(2.dp, Am.Accent, shape) else Modifier).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(Am.PreviewBg)) {
            picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (picture == null) Text(if (slot.video) "▶" else "▣", Modifier.align(Alignment.Center), color = Am.Accent, fontSize = 24.sp)
            Text(
                if (slot.video) "Video" else "Image",
                Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                when (mode) { MediaFillMode.Fill -> "Fill"; MediaFillMode.Fit -> "Fit"; MediaFillMode.Stretch -> "Stretch" },
                Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color.White, fontSize = 9.sp,
            )
        }
        Column(Modifier.padding(horizontal = 7.dp, vertical = 6.dp)) {
            Text(slot.uri.substringAfterLast('/').substringBefore('?').ifBlank { "Asset" }, color = Am.Navy, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (selected) "Tap to swap" else "Select", color = Am.HomeDim, fontSize = 9.sp, maxLines = 1)
        }
    }
}
