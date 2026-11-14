package app.absentmotion.ui.home

import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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

private data class TikTokItem(val id: String, val url: String, val title: String, val author: String, val cover: String? = null)

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"

@Composable
internal fun TikTokScreen(onClose: () -> Unit, onImportXml: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<TikTokItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var moreLoading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<TikTokItem?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun search() {
        if (query.isBlank()) return
        loading = true; failed = false; notice = null; page = 1
        scope.launch {
            val found = runCatching { TikTokWeb.search(query, 1) }.getOrDefault(emptyList())
            items = found
            hasMore = found.isNotEmpty()
            failed = found.isEmpty()
            loading = false
        }
    }
    fun loadMore() {
        if (moreLoading || loading || !hasMore) return
        moreLoading = true
        scope.launch {
            val next = page + 1
            val found = runCatching { TikTokWeb.search(query, next) }.getOrDefault(emptyList())
            val merged = (items + found).distinctBy { it.id }
            hasMore = found.isNotEmpty() && merged.size > items.size
            items = merged
            page = next
            moreLoading = false
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
                val saved = if (VideoDownloader.available && VideoDownloader.installed(context)) {
                    runCatching {
                        val info = VideoDownloader.check(context, item.url)
                        VideoDownloader.download(context, item.url, info, SaveMode.AudioOnly, 0) {}
                    }.getOrElse { emptyList() }
                } else emptyList()
                val result = if (saved.isNotEmpty()) saved else {
                    val direct = TikTokWeb.playUrl(item)
                    if (direct == null) emptyList() else listOfNotNull(VideoDownloader.directAudio(context, direct, item.title))
                }
                notice = if (result.isEmpty()) context.getString(R.string.tiktok_audio_failed) else context.getString(R.string.tiktok_audio_saved)
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(40.dp).weight(1f).clip(RoundedCornerShape(20.dp)).background(Am.Navy).clickable(enabled = !loading) { search() }, Alignment.Center) {
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
                            Box(Modifier.size(56.dp, 72.dp).clip(RoundedCornerShape(10.dp)).background(Am.HomeBg), Alignment.Center) {
                                if (item.cover != null) coil3.compose.AsyncImage(item.cover, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                else Text("▶", color = Am.HomeAccent, fontSize = 18.sp)
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(item.title, color = Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(item.author, color = Am.HomeDim, fontSize = 11.sp, maxLines = 1)
                            }
                            Text("XML", Modifier.padding(horizontal = 6.dp).clickable { importXml(item) }, color = Am.HomeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("♪", Modifier.padding(horizontal = 6.dp).clickable { importAudio(item) }, color = Am.HomeAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (hasMore) item(key = "tiktok-load-more") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Am.Card)
                                .clickable(enabled = !moreLoading) { loadMore() },
                            Alignment.Center,
                        ) {
                            Text(if (moreLoading) "Loading…" else "Load more", color = Am.HomeAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
    var direct by remember(item.id) { mutableStateOf<String?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
    var loading by remember(item.id) { mutableStateOf(true) }
    val fallbackWeb = remember(item.id) { arrayOfNulls<WebView>(1) }
    val videoView = remember(item.id) { arrayOfNulls<android.widget.VideoView>(1) }
    LaunchedEffect(item.id) {
        direct = runCatching { TikTokWeb.playUrl(item) }.getOrNull()
        failed = direct == null
        loading = false
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).background(Am.HomeBg), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onClose), Alignment.Center) { Text("×", color = Am.Navy, fontSize = 22.sp) }
                Text(item.title, Modifier.weight(1f), color = Am.Navy, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                if (item.cover != null) coil3.compose.AsyncImage(item.cover, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                if (direct != null && !failed) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                videoView[0] = this
                                setBackgroundColor(android.graphics.Color.BLACK)
                                val headers = mapOf("User-Agent" to UA, "Referer" to "https://www.tiktok.com/")
                                runCatching { setVideoURI(android.net.Uri.parse(direct.orEmpty()), headers) }
                                setOnPreparedListener { media ->
                                    media.isLooping = true
                                    start()
                                }
                                setOnErrorListener { _, _, _ -> failed = true; true }
                                val controller = android.widget.MediaController(ctx).also { it.setAnchorView(this) }
                                setMediaController(controller)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (direct == null || failed) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                fallbackWeb[0] = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.userAgentString = UA
                                setBackgroundColor(android.graphics.Color.BLACK)
                                webViewClient = object : WebViewClient() {
                                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                                        runCatching { view.destroy() }
                                        return true
                                    }
                                }
                                loadUrl("https://www.tiktok.com/player/v1/${item.id}?autoplay=1&loop=1&music_info=1&description=1")
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (loading) androidx.compose.material3.CircularProgressIndicator(color = Am.Accent)
            }
            Row(Modifier.fillMaxWidth().background(Am.HomeBg).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Am.Navy).clickable(onClick = onXml), Alignment.Center) {
                    Text(stringResource(R.string.tiktok_import_xml), color = Am.OnNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Am.Card).clickable(onClick = onAudio), Alignment.Center) {
                    Text(stringResource(R.string.tiktok_import_audio), color = Am.HomeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    DisposableEffect(item.id) {
        onDispose {
            runCatching { videoView[0]?.stopPlayback() }
            runCatching { fallbackWeb[0]?.destroy() }
            videoView[0] = null
            fallbackWeb[0] = null
        }
    }
}

private object TikTokWeb {
    private val gate = Mutex()
    private var lastRequest = 0L
    private val cache = HashMap<String, Pair<Long, List<TikTokItem>>>()

    suspend fun search(query: String, page: Int = 1): List<TikTokItem> = withContext(Dispatchers.IO) {
        val key = query.trim()
        if (key.isEmpty()) return@withContext emptyList()
        val cacheKey = "$key#$page"
        cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return@withContext it.second }
        val links = LinkedHashSet<String>()
        fun collect(html: String?) {
            if (html == null) return
            val decoded = runCatching { URLDecoder.decode(html, "UTF-8") }.getOrDefault(html).replace("&amp;", "&")
            Regex("""(?:https?:)?//(?:www\.)?tiktok\.com/@[A-Za-z0-9._-]+/video/\d+""").findAll(decoded).forEach { match ->
                val link = match.value
                links += if (link.startsWith("//")) "https:$link" else if (link.startsWith("http")) link else "https://$link"
            }
            Regex("""/@[A-Za-z0-9._-]+/video/\d+""").findAll(decoded).forEach { links += "https://www.tiktok.com" + it.value }
        }
        val quoted = URLEncoder.encode("site:tiktok.com $key", "UTF-8")
        val start = (page - 1) * 120
        for (offset in listOf(start, start + 30, start + 60, start + 90)) collect(fetch("https://html.duckduckgo.com/html/?q=$quoted&s=$offset"))
        val bing = URLEncoder.encode("site:tiktok.com $key", "UTF-8")
        val firstBase = (page - 1) * 40
        for (first in listOf(firstBase + 1, firstBase + 11, firstBase + 21, firstBase + 31)) collect(fetch("https://www.bing.com/search?q=$bing&first=$first"))
        collect(fetch("https://www.tiktok.com/search?q=" + URLEncoder.encode(key, "UTF-8") + "&offset=" + ((page - 1) * 30)))
        val result = links.take(80).mapIndexedNotNull { index, link -> item(link, metadata = index < 24) }
        if (result.isNotEmpty()) cache[cacheKey] = System.currentTimeMillis() to result
        result
    }

    suspend fun playUrl(item: TikTokItem): String? = withContext(Dispatchers.IO) {
        val html = fetch(item.url) ?: return@withContext null
        val decoded = unescape(html)
        val patterns = listOf(
            Regex("""(?:playAddr|downloadAddr|play_addr)"\s*:\s*\{[^}]*?"url_list"\s*:\s*\["(https:[^"]+)"""),
            Regex("""(?:playAddr|downloadAddr|play_addr)"\s*:\s*"(https:[^"]+)"""),
            Regex("""https://[^"']*(?:aweme|video)[^"']*\.mp4[^"']*"""),
        )
        patterns.asSequence().mapNotNull { match -> match.find(decoded)?.let { it.groupValues.getOrNull(1)?.takeIf(String::isNotBlank) ?: it.value } }
            .firstOrNull { it.startsWith("http") }
    }

    private suspend fun item(url: String, metadata: Boolean = true): TikTokItem? {
        val id = Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1) ?: return null
        val author = url.substringAfter("@", "").substringBefore("/")
        var title = "TikTok · @$author".trim()
        var cover: String? = null
        if (metadata) runCatching {
            fetch("https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, "UTF-8"))?.let { JSONObject(it) }.let { json ->
                title = json?.optString("title").orEmpty().ifBlank { title }
                cover = json?.optString("thumbnail_url").orEmpty().takeIf { it.startsWith("http") }
            }
        }
        return TikTokItem(id, url, title, "@$author", cover)
    }

    suspend fun xml(url: String): String? = withContext(Dispatchers.IO) {
        val oembed = fetch("https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, "UTF-8"))
        val page = fetch(url)
        val text = unescape(listOfNotNull(oembed, page).joinToString(" "))
        xmlIn(text)?.let { return@withContext it }
        val candidates = Regex("""https?://[^\s"'<>]+""").findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '}') }
            .filter { candidate ->
                candidate.contains("drive.google.com", true) || candidate.contains("drive.usercontent.google.com", true) ||
                    candidate.contains("dropbox.com", true) || candidate.contains(".xml", true) || candidate.contains("/file/d/", true)
            }.distinct().take(8).toList()
        for (candidate in candidates) {
            val direct = driveDirect(candidate) ?: candidate.replace("?dl=0", "?dl=1")
            val bytes = fetchBytes(direct) ?: continue
            xmlIn(bytes.toString(Charsets.UTF_8))?.let { return@withContext it }
            xmlIn(zipText(bytes))?.let { return@withContext it }
        }
        null
    }

    private fun xmlIn(text: String): String? {
        val clean = unescape(text)
        return Regex("""<scene[\s\S]*?</scene>""").find(clean)?.value
            ?: Regex("""<project[\s\S]*?</project>""").find(clean)?.value
            ?: Regex("""(?:scene|project)"\s*:\s*"(<[a-zA-Z][\s\S]*?</(?:scene|project)>)""").find(clean)?.groupValues?.get(1)?.let(::unescape)
    }

    private fun driveDirect(url: String): String? {
        val id = Regex("""/file/d/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""[?&]id=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: return null
        return "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
    }

    private fun zipText(bytes: ByteArray): String {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return ""
        return runCatching {
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(".xml", true)) continue
                    val data = zip.readNBytes(16 shl 20)
                    val text = data.toString(Charsets.UTF_8)
                    if (text.contains("<scene", true) || text.contains("<project", true)) return@use text
                }
                ""
            }
        }.getOrDefault("")
    }

    private fun fetchBytes(url: String): ByteArray? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/xml,application/zip,text/xml,application/octet-stream,*/*")
            instanceFollowRedirects = true
        }
        return try {
            if (c.responseCode !in 200..299) null else c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (total < (16 shl 20)) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun unescape(value: String): String = value
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\/", "/")
        .replace("\\u003C", "<")
        .replace("\\u003c", "<")
        .replace("\\u003E", ">")
        .replace("\\u003e", ">")
        .replace("\\u0026", "&")
        .replace("\\u0022", "\"")
        .replace("\\u0027", "'")
        .replace("\\n", "\n")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

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
