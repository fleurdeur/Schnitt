package app.absentmotion.ui.home

import app.absentmotion.model.Scene
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal data class CommunityPreset(
    val id: String,
    val title: String,
    val author: String,
    val type: String,
    val url: String,
)

internal object CommunityPresets {
    private const val ROOT = "https://raw.githubusercontent.com/fleurdeur/Schnitt/main/docs/community/data/"
    private const val INDEX = ROOT + "index.json"
    private val absolute = Regex("https?://.+")

    suspend fun load(): List<CommunityPreset> = withContext(Dispatchers.IO) {
        val text = fetch(INDEX) ?: return@withContext emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return@withContext emptyList()
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CommunityPreset(
                id = item.optString("id").ifBlank { url },
                title = item.optString("title").ifBlank { "Community preset" },
                author = item.optString("author").ifBlank { "Schnitt community" },
                type = item.optString("type", "preset").lowercase(),
                url = if (absolute.matches(url)) url else ROOT + url.removePrefix("/"),
            )
        }
    }

    suspend fun scene(url: String): Scene? = withContext(Dispatchers.IO) {
        val text = fetch(url) ?: return@withContext null
        runCatching { Scene.parse(text.removePrefix("\uFEFF")) }.getOrNull()
    }

    private fun fetch(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "Schnitt")
            instanceFollowRedirects = true
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (total < (4 shl 20)) {
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
}
