package app.absentmotion.audio

import android.content.Context
import app.absentmotion.tools.SaveMode
import app.absentmotion.tools.VideoDownloader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AudioShelf { Sfx, Music, Custom, YtDlp }

data class OnlineSound(
    val id: String,
    val title: String,
    val artist: String,
    val url: String,
    val page: String,
    val shelf: AudioShelf,
    val license: String = "Royalty free",
    val viaDownloader: Boolean = false,
) {
    val key: String get() = url
}

/**
 * Small, dependency-free reader for public royalty-free audio pages.  It reads
 * the same preview streams the page itself plays, so pressing + on a row can
 * insert the original bytes into the project without a separate download step.
 */
object OnlineAudio {
    const val MixkitSfx = "https://mixkit.co/free-sound-effects/"
    const val MixkitMusic = "https://mixkit.co/free-stock-music/"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    suspend fun load(shelf: AudioShelf, customSite: String = "", context: Context? = null): List<OnlineSound> = withContext(Dispatchers.IO) {
        val url = when (shelf) {
            AudioShelf.Sfx -> MixkitSfx
            AudioShelf.Music -> MixkitMusic
            AudioShelf.Custom, AudioShelf.YtDlp -> customSite.trim()
        }
        if (url.isBlank()) return@withContext emptyList()
        if (shelf == AudioShelf.YtDlp) {
            if (context == null || !VideoDownloader.installed(context)) return@withContext emptyList()
            return@withContext runCatching {
                val info = VideoDownloader.check(context, url)
                info.items.map { item -> OnlineSound(item.title, item.title, info.site, url, url, shelf, viaDownloader = true) }
            }.getOrDefault(emptyList())
        }
        if (url.contains("mixkit.co", true)) {
            val html = fetch(url) ?: return@withContext emptyList()
            mixkit(html, shelf, url)
        } else {
            var found = runCatching { generic(fetch(url).orEmpty(), shelf, URL(url)) }.getOrDefault(emptyList())
            if (found.isEmpty()) found = siteSpecific(url, shelf)
            if (found.isEmpty() && context != null && VideoDownloader.available) {
                found = runCatching {
                    val info = VideoDownloader.check(context, url)
                    info.items.map { item -> OnlineSound(item.title, item.title, info.site, url, url, shelf, viaDownloader = true) }
                }.getOrDefault(emptyList())
            }
            found
        }
    }

    private fun siteSpecific(url: String, shelf: AudioShelf): List<OnlineSound> {
        val host = runCatching { URL(url).host.lowercase() }.getOrDefault("")
        return when {
            "deezer.com" in host -> deezer(url, shelf)
            "bandcamp.com" in host -> bandcamp(url, shelf)
            "soundcloud.com" in host -> soundcloud(url, shelf)
            else -> emptyList()
        }
    }

    private fun deezer(url: String, shelf: AudioShelf): List<OnlineSound> {
        val id = Regex("""/(?:track|album)/(\d+)""").find(url)?.groupValues?.get(1)
        val endpoint = if (id != null) "https://api.deezer.com/track/$id" else {
            val query = url.substringAfterLast('/').replace('-', ' ').ifBlank { return emptyList() }
            "https://api.deezer.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        }
        val root = runCatching { org.json.JSONObject(fetch(endpoint).orEmpty()) }.getOrNull() ?: return emptyList()
        if (id != null) {
            val preview = root.optString("preview")
            if (preview.isBlank()) return emptyList()
            return listOf(OnlineSound(root.optString("id", id), root.optString("title", "Deezer preview"), root.optJSONObject("artist")?.optString("name").orEmpty(), preview, url, shelf))
        }
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val track = data.optJSONObject(i) ?: return@mapNotNull null
            val preview = track.optString("preview")
            if (preview.isBlank()) return@mapNotNull null
            OnlineSound(track.optString("id", preview), track.optString("title", "Deezer preview"), track.optJSONObject("artist")?.optString("name").orEmpty(), preview, url, shelf)
        }
    }

    private fun bandcamp(url: String, shelf: AudioShelf): List<OnlineSound> {
        val html = fetch(url) ?: return emptyList()
        val audio = Regex("""(?:mp3-128|file)"?\s*[:=]\s*"(https?:[^"]+?\.mp3[^"]*)""" + "\"").findAll(html).map { it.groupValues[1].replace("""\/""", "/") }.toList()
        if (audio.isEmpty()) return emptyList()
        val titles = Regex("""title\s*:\s*"([^"]+)""" + "\"").findAll(html).map { it.groupValues[1] }.toList()
        return audio.distinct().mapIndexed { i, stream ->
            OnlineSound(stream, titles.getOrElse(i) { "Bandcamp ${i + 1}" }, "", stream, url, shelf)
        }
    }

    private fun soundcloud(url: String, shelf: AudioShelf): List<OnlineSound> {
        val html = fetch(url) ?: return emptyList()
        val streams = Regex("""(?:streamUrl|hlsUrl|progressive)"\s*:\s*"(https:[^"]+)""" + "\"").findAll(html).map { it.groupValues[1].replace("""\/""", "/") }.toList()
        val title = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: "SoundCloud"
        return streams.filter { it.contains(".mp3") || it.contains("m3u8") }.distinct().mapIndexed { i, stream ->
            OnlineSound(stream, if (streams.size > 1) "$title ${i + 1}" else title, "", stream, url, shelf, viaDownloader = stream.contains("m3u8"))
        }
    }

    /** Keeps the network copy untouched: no transcode, no re-encode. */
    suspend fun download(context: Context, sound: OnlineSound): File = withContext(Dispatchers.IO) {
        val folder = File(context.filesDir, "online-audio").apply { mkdirs() }
        if (sound.viaDownloader) {
            val info = VideoDownloader.check(context, sound.page)
            val saved = VideoDownloader.download(context, sound.page, info, SaveMode.AudioOnly, 0) {}
            val first = saved.firstOrNull() ?: error("no audio")
            val target = File(folder, safe(sound.title.ifBlank { "audio" }) + "-" + sound.page.hashCode().toUInt().toString(16) + ".m4a")
            context.contentResolver.openInputStream(first.uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: error("cannot read audio")
            return@withContext target
        }
        val name = safe(sound.title.ifBlank { "audio" }) + "-" + sound.url.hashCode().toUInt().toString(16) + extension(sound.url)
        val target = File(folder, name)
        if (target.isFile && target.length() > 1024) return@withContext target
        val temp = File(folder, name + ".part")
        val connection = open(sound.url)
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val length = connection.contentLengthLong
            if (length > MAX_DOWNLOAD) error("too large")
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { out ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_DOWNLOAD) error("too large")
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (temp.length() < 1024) error("empty")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
            target
        } catch (t: Throwable) {
            temp.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun mixkit(html: String, shelf: AudioShelf, page: String): List<OnlineSound> {
        val pattern = Regex("data-audio-player-preview-url-value=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(html).toList()
        val out = LinkedHashMap<String, OnlineSound>()
        matches.forEachIndexed { index, match ->
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: minOf(html.length, start + 5000)
            val tail = html.substring(start, end)
            val url = match.groupValues[1].replace("&amp;", "&")
            if (!url.startsWith("http")) return@forEachIndexed
            val title = Regex("item-grid-card__title\"\\s*>\\s*([^<]+)", RegexOption.IGNORE_CASE).find(tail)?.groupValues?.get(1)?.trim()
                ?: url.substringAfterLast('/').substringBefore('-').replace('-', ' ').replaceFirstChar { it.uppercase() }
            val artist = Regex("item-grid-music-preview__author\"\\s*>\\s*(?:by\\s*)?([^<]+)", RegexOption.IGNORE_CASE).find(tail)?.groupValues?.get(1)?.trim().orEmpty()
            val id = Regex("data-audio-player-item-id-value=\"([^\"]+)\"").find(tail)?.groupValues?.get(1) ?: url
            out.putIfAbsent(url, OnlineSound(id, title.ifBlank { "Mixkit audio" }, artist, url, page, shelf))
        }
        return out.values.toList()
    }

    private fun generic(html: String, shelf: AudioShelf, base: URL): List<OnlineSound> {
        val out = LinkedHashMap<String, OnlineSound>()
        val attrs = Regex("(?:data-audio-player-preview-url-value|src|data-src|data-url|content)=\"([^\"]+?)\"", RegexOption.IGNORE_CASE)
        val json = Regex("\"(?:preview_url|audio|url|src)\"\\s*:\\s*\"([^\"]+?)\"", RegexOption.IGNORE_CASE)
        val links = (attrs.findAll(html).map { it.groupValues[1] } + json.findAll(html).map { it.groupValues[1] })
            .filter { it.substringBefore('?').lowercase().let { u -> AUDIO.any { u.endsWith(it) } } }
            .toList()
        links.forEach { raw ->
            val resolved = runCatching { URL(base, raw.replace("&amp;", "&").replace("\\/", "/")).toString() }.getOrNull() ?: return@forEach
            if (!resolved.startsWith("http")) return@forEach
            val title = resolved.substringAfterLast('/').substringBefore('?').substringBeforeLast('.').replace('-', ' ').replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
            out.putIfAbsent(resolved, OnlineSound(resolved.substringAfterLast('/'), title.ifBlank { "Audio" }, "", resolved, base.toString(), shelf))
        }
        return out.values.toList()
    }

    private fun fetch(url: String): String? {
        val connection = open(url)
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (total < MAX_HTML) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    out.write(buffer, 0, read)
                }
                out.toByteArray().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", UA)
        setRequestProperty("Accept", "text/html,audio/*,*/*")
        setRequestProperty("Accept-Language", "en")
    }

    private fun extension(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "mp3").lowercase()
        return if (ext in setOf("mp3", "wav", "ogg", "oga", "m4a", "aac", "flac", "opus")) ".$ext" else ".mp3"
    }

    private fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._ -]"), "").trim().replace(' ', '-').take(48).ifBlank { "audio" }
    private const val MAX_HTML = 4 shl 20
    private const val MAX_DOWNLOAD = 48L shl 20
    private val AUDIO = setOf(".mp3", ".wav", ".ogg", ".oga", ".m4a", ".aac", ".flac", ".opus")
}
