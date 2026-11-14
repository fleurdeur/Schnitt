package app.absentmotion.media

import android.content.Context
import android.net.Uri
import app.absentmotion.editor.EditorState
import app.absentmotion.library.MediaKeeper
import app.absentmotion.model.Scene
import app.absentmotion.editor.addImage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

enum class ImageSource(val label: String) { Pinterest("Pinterest"), Cosmos("Cosmos") }

data class LibraryImage(val id: String, val thumb: String, val full: String, val title: String, val source: ImageSource, val alternatives: List<String> = emptyList())

/**
 * Small, cached reader for public Pinterest and Cosmos search pages. Requests
 * are serialized with a delay and a retry/backoff policy so a search cannot
 * flood either site, and both pages and images are cached on disk.
 */
object WebImageLibrary {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    private val gate = Mutex()
    private var lastRequest = 0L
    private val memory = HashMap<String, Pair<Long, List<LibraryImage>>>()
    @Volatile private var client: OkHttpClient? = null

    private fun client(context: Context): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "image-library-http"), 24L shl 20))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build().also { client = it }
    }

    suspend fun search(context: Context, source: ImageSource, query: String, page: Int = 1): List<LibraryImage> = withContext(Dispatchers.IO) {
        AndroidContextHolder.context = context.applicationContext
        val key = "${source.name}:${query.trim().lowercase()}:$page"
        memory[key]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return@withContext it.second }
        val base = when (source) {
            ImageSource.Pinterest -> "https://www.pinterest.com/search/pins/?q=" + enc(query)
            ImageSource.Cosmos -> "https://www.cosmos.so/search?q=" + enc(query)
        }
        val url = if (page <= 1) base else base + (if ('?' in base) "&" else "?") + "page=" + page
        val html = gate.withLock {
            val wait = 900L - (System.currentTimeMillis() - lastRequest)
            if (wait > 0) delay(wait)
            lastRequest = System.currentTimeMillis()
            fetch(url)
        } ?: return@withContext emptyList()
        val found = when (source) {
            ImageSource.Pinterest -> pinterest(html)
            ImageSource.Cosmos -> cosmos(html)
        }
        if (found.isNotEmpty()) memory[key] = System.currentTimeMillis() to found
        found
    }

    private fun pinterest(html: String): List<LibraryImage> {
        val urls = Regex("https://i\\.pinimg\\.com/(?:originals|736x|564x|474x)/[^\" )]+?\\.(?:jpg|jpeg|png|webp)")
            .findAll(html).map { it.value }.toList()
        val seen = LinkedHashMap<String, LibraryImage>()
        for (url in urls) {
            val path = url.substringAfter("i.pinimg.com/").substringAfter('/')
            val full = "https://i.pinimg.com/736x/$path"
            val thumb = "https://i.pinimg.com/474x/$path"
            if (seen.size >= 80) break
            seen.putIfAbsent(path, LibraryImage(path, thumb, full, "Pinterest image", ImageSource.Pinterest,
                listOf("https://i.pinimg.com/originals/$path", "https://i.pinimg.com/564x/$path")))
        }
        return seen.values.toList()
    }

    private fun cosmos(html: String): List<LibraryImage> {
        val urls = Regex("""https://cdn\.cosmos\.so/[^"' )]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"' )]*)?""")
            .findAll(html.replace("""\/""", "/")).map { it.value }.toList()
        val seen = LinkedHashMap<String, LibraryImage>()
        for (url in urls) {
            val id = url.substringAfterLast('/')
            if (seen.size >= 80) break
            seen.putIfAbsent(id, LibraryImage(id, url, url, "Cosmos image", ImageSource.Cosmos))
        }
        return seen.values.toList()
    }

    suspend fun download(context: Context, image: LibraryImage): File? = withContext(Dispatchers.IO) {
        val referer = when (image.source) {
            ImageSource.Pinterest -> "https://www.pinterest.com/"
            ImageSource.Cosmos -> "https://www.cosmos.so/"
        }
        var saved: ByteArray? = null
        var used = image.full
        for (url in (listOf(image.full, image.thumb) + image.alternatives).distinct()) {
            val request = Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer).build()
            val body = gate.withLock {
                try { client(context).newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null } }
                catch (_: Exception) { null }
            } ?: continue
            if (body.size in 1..(48 shl 20) && pictureType(body) != null) {
                saved = body
                used = url
                break
            }
        }
        val body = saved ?: return@withContext null
        val folder = File(context.filesDir, "image-library").apply { mkdirs() }
        val ext = pictureType(body) ?: "jpg"
        val file = File(folder, digest(used).take(16) + ".$ext")
        file.writeBytes(body)
        file
    }

    private fun pictureType(bytes: ByteArray): String? {
        fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
            at(0) == 0x89 && at(1) == 0x50 -> "png"
            at(0) == 0x47 && at(1) == 0x49 -> "gif"
            at(0) == 0x52 && at(1) == 0x49 && at(8) == 0x57 -> "webp"
            else -> null
        }
    }

    private fun fetch(url: String, attempt: Int = 0): String? {
        val appContext = AndroidContextHolder.context ?: return null
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en").build()
        return try {
            client(appContext).newCall(request).execute().use { response ->
                when {
                    response.code == 429 || response.code == 403 -> {
                        if (attempt >= 2) null else {
                            Thread.sleep(1_000L shl attempt)
                            fetch(url, attempt + 1)
                        }
                    }
                    !response.isSuccessful -> null
                    else -> response.body?.string()?.take(3 shl 20)
                }
            }
        } catch (_: Exception) { null }
    }

    private fun enc(text: String) = java.net.URLEncoder.encode(text, "UTF-8")
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Holds the application context only for the shared OkHttp cache inside this reader. */
internal object AndroidContextHolder { @Volatile var context: Context? = null }

suspend fun EditorState.addLibraryImage(context: Context, image: LibraryImage): Boolean {
    val file = WebImageLibrary.download(context, image) ?: return false
    return try {
        val declaration = imageDeclaration(context, Uri.fromFile(file)) ?: return false
        val kept = try {
            MediaKeeper(store, { address -> context.contentResolver.openInputStream(Uri.parse(address)) }).keep(projectId, declaration, scene) { false }
        } catch (_: Exception) { declaration }
        if (kept.uri != declaration.uri) file.delete()
        addImage(kept)
        true
    } catch (_: Exception) {
        false
    }
}
