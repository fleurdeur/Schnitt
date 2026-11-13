package app.absentmotion.ui.home

import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.tools.SaveMode
import app.absentmotion.tools.VideoDownloader
import app.absentmotion.ui.editor.SearchField
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import org.json.JSONObject

private data class TikTokItem(val id: String, val url: String, val title: String, val author: String)

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"

@Composable
internal fun TikTokScreen(onClose: () -> Unit, onImportXml: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var items by remember { mutableStateOf<List<TikTokItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<TikTokItem?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun search() {
        if (query.isBlank()) return
        loading = true; failed = false; notice = null
        scope.launch {
            val q = if (category == "All") query else "$query $category"
            items = runCatching { TikTokWeb.search(q) }.getOrDefault(emptyList())
            failed = items.isEmpty()
            loading = false
        }
    }
    fun importXml(item: TikTokItem) {
        scope.launch {
            val xml = runCatching { TikTokWeb.xml(item.url) }.getOrNull()
            if (xml == null) notice = context.getString(R.string.tiktok_no_xml)
            else {
                val file = File(context.cacheDir, "tiktok-${item.id}.xml")
                file.writeText(xml)
                onImportXml(Uri.fromFile(file))
            }
        }
    }
    fun importAudio(item: TikTokItem) {
        scope.launch {
            try {
                if (!VideoDownloader.installed(context)) { notice = context.getString(R.string.tiktok_need_ytdlp); return@launch }
                val info = VideoDownloader.check(context, item.url)
                val saved = VideoDownloader.download(context, item.url, info, SaveMode.AudioOnly, 0) {}
                notice = if (saved.isEmpty()) context.getString(R.string.tiktok_audio_failed) else context.getString(R.string.tiktok_audio_saved)
            } catch (e: Exception) {
                notice = app.absentmotion.i18n.failureText(context.getString(R.string.tiktok_audio_failed), e)
            }
        }
    }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Am.HomeBg).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onClose), Alignment.Center) {
                    androidx.compose.material3.Icon(ExportIcons.Back, stringResource(R.string.action_close), Modifier.size(24.dp), tint = Am.Navy)
                }
                Text("TikTok · Alight Motion", Modifier.weight(1f), color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            SearchField(query, { query = it }, Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(40.dp),
                hint = stringResource(R.string.tiktok_search), background = Am.Card, content = Am.Navy, faint = Am.HomeDim)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("All", "Effects", "CC", "Transition", "Preset").forEach { item ->
                    val on = item == category
                    Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Am.Navy else Am.Card).clickable { category = item }.padding(horizontal = 12.dp), Alignment.Center) {
                        Text(item, color = if (on) Am.OnNavy else Am.HomeInk, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(38.dp).weight(1f).clip(RoundedCornerShape(19.dp)).background(Am.Navy).clickable(enabled = !loading) { search() }, Alignment.Center) {
                    Text(if (loading) stringResource(R.string.tiktok_searching) else stringResource(R.string.tiktok_search_button), color = Am.OnNavy, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            notice?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Am.HomeAccent, fontSize = 12.sp, lineHeight = 16.sp) }
            when {
                failed -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text(stringResource(R.string.tiktok_empty), color = Am.HomeDim, fontSize = 14.sp) }
                items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text(stringResource(R.string.tiktok_hint), color = Am.HomeDim, fontSize = 14.sp) }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(items, key = { it.id }) { item ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card).clickable { open = item }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Am.HomeBg), Alignment.Center) { Text("▶", color = Am.HomeAccent, fontSize = 18.sp) }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(item.title, color = Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(item.author, color = Am.HomeDim, fontSize = 11.sp, maxLines = 1)
                            }
                            Text("XML", Modifier.padding(horizontal = 6.dp).clickable { importXml(item) }, color = Am.HomeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("♪", Modifier.padding(horizontal = 6.dp).clickable { importAudio(item) }, color = Am.HomeAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    open?.let { item -> TikTokPlayer(item, onClose = { open = null }, onXml = { importXml(item) }, onAudio = { importAudio(item) }) }
}

@Composable
private fun TikTokPlayer(item: TikTokItem, onClose: () -> Unit, onXml: () -> Unit, onAudio: () -> Unit) {
    val context = LocalContext.current
    val web = remember(item.id) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
        }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Am.HomeBg).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onClose), Alignment.Center) { Text("×", color = Am.Navy, fontSize = 22.sp) }
                Text(item.title, Modifier.weight(1f), color = Am.Navy, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp)).background(Am.Navy).clickable(onClick = onXml), Alignment.Center) {
                    Text(stringResource(R.string.tiktok_import_xml), color = Am.OnNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp)).background(Am.Card).clickable(onClick = onAudio), Alignment.Center) {
                    Text(stringResource(R.string.tiktok_import_audio), color = Am.HomeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    LaunchedEffect(item.id) { web.loadUrl("https://www.tiktok.com/player/v1/${item.id}?autoplay=1&loop=1&music_info=1&description=1") }
    DisposableEffect(item.id) { onDispose { web.destroy() } }
}

private object TikTokWeb {
    private val gate = Mutex()
    private var lastRequest = 0L
    private val cache = HashMap<String, Pair<Long, List<TikTokItem>>>()

    suspend fun search(query: String): List<TikTokItem> = withContext(Dispatchers.IO) {
        cache[query]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return@withContext it.second }
        val q = URLEncoder.encode("site:tiktok.com $query (alight motion OR transition OR cc OR preset)", "UTF-8")
        val html = fetch("https://html.duckduckgo.com/html/?q=$q") ?: return@withContext emptyList()
        val decoded = runCatching { URLDecoder.decode(html, "UTF-8") }.getOrDefault(html)
        val links = Regex("""https?://(?:www\.)?tiktok\.com/@[^"&<> ]+/video/\d+""").findAll(decoded).map { it.value }.distinct().take(10).toList()
        val result = links.mapNotNull { link -> item(link) }
        if (result.isNotEmpty()) cache[query] = System.currentTimeMillis() to result
        result
    }

    private suspend fun item(url: String): TikTokItem? {
        val id = Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1) ?: return null
        val json = fetch("https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, "UTF-8"))?.let { runCatching { JSONObject(it) }.getOrNull() }
        val author = json?.optString("author_name").orEmpty().ifBlank { url.substringAfter("@").substringBefore("/") }
        val title = json?.optString("title").orEmpty().ifBlank { "TikTok · @$author" }
        return TikTokItem(id, url, title, "@$author")
    }

    suspend fun xml(url: String): String? = withContext(Dispatchers.IO) {
        val html = fetch(url) ?: return@withContext null
        val decoded = html.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
        Regex("""<scene[\s\S]*?</scene>""").find(decoded)?.value
    }

    private fun fetch(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "en")
            instanceFollowRedirects = true
        }
        return try {
            if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
