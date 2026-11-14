package app.absentmotion.ui.editor

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.audio.AudioShelf
import app.absentmotion.audio.OnlineAudio
import app.absentmotion.audio.OnlineSound
import app.absentmotion.audio.audioDeclaration
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addAudio
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.tools.VideoDownloader
import kotlinx.coroutines.launch

@Composable
internal fun AudioLibrarySheet(state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("audio-library", Context.MODE_PRIVATE) }
    var shelf by remember { mutableStateOf(AudioShelf.Sfx) }
    var site by remember { mutableStateOf(prefs.getString("site", "").orEmpty()) }
    var loadedSite by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<OnlineSound>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var moreLoading by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<String?>(null) }
    var ytdlpReady by remember { mutableStateOf(VideoDownloader.installed(context)) }
    var installing by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf(0f) }
    val query = debounced(search)
    val preview = remember { SoundPreview(context) }
    LaunchedEffect(shelf, ytdlpReady) {
        if (shelf == AudioShelf.YtDlp && ytdlpReady) runCatching { VideoDownloader.warmUp(context) }
    }
    DisposableEffect(Unit) { onDispose { preview.stop() } }
    LaunchedEffect(shelf, loadedSite, reload, ytdlpReady) {
        if ((shelf == AudioShelf.Custom || shelf == AudioShelf.YtDlp) && loadedSite.isBlank()) {
            items = emptyList(); loading = false; failed = false
            return@LaunchedEffect
        }
        loading = true; failed = false; moreLoading = false
        val found = runCatching { OnlineAudio.load(shelf, loadedSite, context, 1) }.getOrElse { emptyList() }
        items = found
        page = 1
        hasMore = found.isNotEmpty() && (shelf == AudioShelf.Sfx || shelf == AudioShelf.Music || shelf == AudioShelf.Custom)
        failed = found.isEmpty()
        loading = false
    }
    fun loadMore() {
        if (moreLoading || loading || !hasMore) return
        moreLoading = true
        scope.launch {
            val next = page + 1
            val found = runCatching { OnlineAudio.load(shelf, loadedSite, context, next) }.getOrElse { emptyList() }
            val merged = (items + found).distinctBy { it.key }
            hasMore = found.isNotEmpty() && merged.size > items.size
            items = merged
            page = next
            moreLoading = false
        }
    }
    val shown = remember(items, query, shelf) {
        if (shelf == AudioShelf.YtDlp) items else items.filter { matchesSearch(query, it.title, it.artist, it.license) }
    }
    fun loadYtdlp() {
        val text = search.trim()
        if (text.isBlank()) return
        val target = if (text.startsWith("http://") || text.startsWith("https://")) text else "ytsearch12:$text"
        loadedSite = target
        reload++
    }
    fun add(sound: OnlineSound) {
        if (adding != null) return
        adding = sound.key
        scope.launch {
            try {
                val file = OnlineAudio.download(context, sound)
                val media = audioDeclaration(context, Uri.fromFile(file))
                if (media == null || media.duration <= 0) {
                    Toast.makeText(context, context.getString(R.string.audio_library_add_failed), Toast.LENGTH_LONG).show()
                } else {
                    state.addAudio(media)
                    Toast.makeText(context, context.getString(R.string.audio_library_added), Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, app.absentmotion.i18n.failureText(context.getString(R.string.audio_library_add_failed), e), Toast.LENGTH_LONG).show()
            } finally {
                adding = null
            }
        }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Am.Panel).systemBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onClose), Alignment.Center) {
                    Icon(ExportIcons.Back, stringResource(R.string.action_close), Modifier.size(24.dp), tint = Am.OnDark)
                }
                Text(stringResource(R.string.audio_library_title), Modifier.weight(1f), color = Am.OnDark, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                if (loading) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(18.dp), color = Am.Accent, strokeWidth = 2.dp)
            }
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp)) {
                ShelfTab(stringResource(R.string.audio_library_sfx), shelf == AudioShelf.Sfx, Modifier.weight(1f)) { shelf = AudioShelf.Sfx; search = "" }
                ShelfTab(stringResource(R.string.audio_library_music), shelf == AudioShelf.Music, Modifier.weight(1f)) { shelf = AudioShelf.Music; search = "" }
                ShelfTab(stringResource(R.string.audio_library_custom), shelf == AudioShelf.Custom, Modifier.weight(1f)) { shelf = AudioShelf.Custom; search = "" }
                ShelfTab("yt-dlp", shelf == AudioShelf.YtDlp, Modifier.weight(1f)) { shelf = AudioShelf.YtDlp; search = ""; loadedSite = "" }
            }
            if (shelf == AudioShelf.Custom) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.audio_library_custom_hint), color = Am.OnDarkDim, fontSize = 12.sp, lineHeight = 17.sp)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(Am.Field).padding(horizontal = 14.dp), Alignment.CenterStart) {
                            BasicTextField(
                                value = site, onValueChange = { site = it }, singleLine = true,
                                textStyle = TextStyle(color = Am.OnDark, fontSize = 13.sp, fontWeight = FontWeight.Normal),
                                cursorBrush = SolidColor(Am.Accent),
                                decorationBox = { inner ->
                                    if (site.isBlank()) Text(stringResource(R.string.audio_library_site), color = Am.OnDarkDim, fontSize = 13.sp)
                                    inner()
                                },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Am.Accent)
                                .clickable { prefs.edit().putString("site", site).apply(); loadedSite = site; reload++ }.padding(horizontal = 16.dp),
                            Alignment.Center,
                        ) { Text(stringResource(R.string.audio_library_use_site), color = Am.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            if (shelf == AudioShelf.YtDlp && !ytdlpReady) {
                Column(Modifier.fillMaxWidth().padding(14.dp).clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(12.dp)) {
                    Text("yt-dlp add-on", color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Downloads the one-time Python + yt-dlp dependency into app storage. No account or external app is required.", Modifier.padding(top = 4.dp), color = Am.OnDarkDim, fontSize = 11.sp, lineHeight = 15.sp)
                    if (installing) {
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(3.dp).background(Am.Well)) { Box(Modifier.fillMaxWidth(installProgress.coerceIn(0f, 1f)).height(3.dp).background(Am.Accent)) }
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).background(Am.Accent).clickable(enabled = !installing) {
                        installing = true; installProgress = 0f
                        scope.launch {
                            try {
                                VideoDownloader.install(context) { installProgress = it }
                                ytdlpReady = VideoDownloader.installed(context)
                            } catch (e: Exception) {
                                Toast.makeText(context, app.absentmotion.i18n.failureText("yt-dlp could not be installed", e), Toast.LENGTH_LONG).show()
                            } finally { installing = false }
                        }
                    }, Alignment.Center) { Text(if (installing) "Installing…" else "Download yt-dlp", color = Am.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    search, { search = it }, Modifier.weight(1f).height(38.dp),
                    hint = if (shelf == AudioShelf.YtDlp) "Search YouTube or paste a link" else stringResource(R.string.audio_library_search, ""),
                )
                if (shelf == AudioShelf.YtDlp) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(Am.Accent)
                            .clickable(enabled = search.isNotBlank() && ytdlpReady) { loadYtdlp() }.padding(horizontal = 14.dp),
                        Alignment.Center,
                    ) { Text("Search", color = Am.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(stringResource(R.string.audio_library_load), color = Am.OnDarkDim, fontSize = 14.sp)
                }
                failed && items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(
                        if (shelf == AudioShelf.Custom) stringResource(R.string.audio_library_custom_hint) else stringResource(R.string.audio_library_error),
                        color = Am.OnDarkDim, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(stringResource(R.string.audio_library_empty), color = Am.OnDarkDim, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(shown, key = { _, sound -> sound.key }) { _, sound ->
                        val playing = preview.uri == sound.url
                        Row(Modifier.fillMaxWidth().height(62.dp).clickable { preview.toggle(sound.url) }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(CircleShape).background(if (playing) Am.Accent else Am.Well), Alignment.Center) {
                                Icon(if (playing) ChromeIcons.Pause else ChromeIcons.Play, null, Modifier.size(17.dp), tint = if (playing) Am.OnAccent else Am.OnDark)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(sound.title, color = if (playing) Am.Accent else Am.OnDark, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(sound.artist, sound.license).filter { it.isNotBlank() }.joinToString(" · "),
                                    color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(Modifier.size(52.dp).clickable(enabled = adding == null) { add(sound) }, Alignment.Center) {
                                if (adding == sound.key) CircularProgressIndicator(Modifier.size(18.dp), color = Am.Accent, strokeWidth = 2.dp)
                                else Icon(ChromeIcons.Plus, stringResource(R.string.audiotab_add), Modifier.size(20.dp), tint = Am.OnDark)
                            }
                        }
                    }
                    if (hasMore && !loading) item(key = "load-more") {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp).height(44.dp)
                                .clip(RoundedCornerShape(22.dp)).background(Am.Well)
                                .clickable(enabled = !moreLoading) { loadMore() },
                            Alignment.Center,
                        ) {
                            if (moreLoading) CircularProgressIndicator(Modifier.size(18.dp), color = Am.Accent, strokeWidth = 2.dp)
                            else Text("Load more", color = Am.OnDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().fillMaxHeight().clickable(onClick = onClick), Alignment.Center) {
        Text(label, color = if (active) Am.Accent else Am.OnDarkDim, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(2.dp).background(if (active) Am.Accent else Color.Transparent))
    }
}

