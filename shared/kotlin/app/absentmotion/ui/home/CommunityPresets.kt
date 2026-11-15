package app.absentmotion.ui.home

import app.absentmotion.model.Scene
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class CommunityPreset(
    val id: String,
    val title: String,
    val author: String,
    val type: String,
    val url: String,
    val status: String = "approved",
)

internal object CommunityPresets {
    private const val ROOT = "https://raw.githubusercontent.com/fleurdeur/Schnitt/main/docs/community/data/"
    private const val INDEX = ROOT + "index.json"
    private const val LIVE = "https://schnitt-community.darkest.workers.dev/api/index"
    private val absolute = Regex("https?://.+")

    suspend fun load(): List<CommunityPreset> = withContext(Dispatchers.IO) {
        val live = runCatching { parse(JSONArray(fetchJson(LIVE, "entries"))) }.getOrDefault(emptyList())
        val archive = runCatching { parse(JSONArray(fetch(INDEX) ?: "[]")) }.getOrDefault(emptyList())
        (live + archive)
            .filter { it.status == "approved" }
            .distinctBy { it.title.lowercase() + "|" + it.author.lowercase() }
    }

    private fun parse(array: JSONArray): List<CommunityPreset> = (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CommunityPreset(
            id = item.optString("id").ifBlank { url },
            title = item.optString("title").ifBlank { "Community preset" },
            author = item.optString("author").ifBlank { "Schnitt community" },
            type = item.optString("type", "preset").lowercase(),
            url = when {
                absolute.matches(url) -> url
                url.startsWith("/api/") -> "https://schnitt-community.darkest.workers.dev" + url
                else -> ROOT + url.removePrefix("/")
            },
            status = item.optString("status", "approved").lowercase(),
        )
    }

    private fun fetchJson(url: String, arrayKey: String): String {
        val text = fetch(url) ?: return "[]"
        return runCatching { JSONObject(text).optJSONArray(arrayKey)?.toString() }.getOrNull() ?: "[]"
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
