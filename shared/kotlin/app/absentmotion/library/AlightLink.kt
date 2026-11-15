package app.absentmotion.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/**
 * Resolves public Alight Motion share links without requiring the Alight app.
 *
 * Short links redirect to /am/share/u/<uid>/p/<pid>. Public metadata gives the
 * package information, and the public download function returns a signed
 * package URL when Alight makes it available. We never use Alight credentials,
 * app tokens or authentication material.
 */
class AlightImportException(message: String) : Exception(message)

object AlightLinks {
    private const val METADATA = "https://us-central1-alight-creative.cloudfunctions.net/getProjectMetadata"
    private const val DOWNLOAD = "https://us-central1-alight-creative.cloudfunctions.net/requestProjectDownload"
    private const val STORAGE = "https://firebasestorage.googleapis.com/v0/b/alight-creative.appspot.com/o/"
    private const val MAX_PACKAGE = 3L shl 30
    private val share = Regex("""/am/share/u/([^/?#]+)/p/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val queryShare = Regex("""(?:^|[?&])(?:u|uid)=([^&#]+).*?(?:^|[?&])(?:p|pid|project)=([^&#]+)""", RegexOption.IGNORE_CASE)
    private val hostSuffixes = listOf(
        "alight.link", "alight.page.link", "alight.to", "alightcreative.com",
        "alightmotion.com", "alight.creative.firebase.com", "alight-creative.firebaseapp.com",
    )
    private val downloadKeys = listOf("download", "downloadUri", "downloadURL", "packageUrl", "packageUri", "projectUrl", "projectUri")

    fun isShareLink(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return hostSuffixes.any { host == it || host.endsWith(".$it") }
    }

    suspend fun download(context: Context, uri: Uri, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File {
        val target = File(context.cacheDir, "alight-${System.nanoTime()}.zip")
        try {
            val ids = resolve(uri) ?: throw AlightImportException("not an Alight share link")
            onProgress(0, 0)
            val metadata = metadata(ids.first, ids.second)
            val candidates = LinkedHashSet<String>()
            metadata?.let { firstUrl(it)?.let { url -> candidates += url } }
            requestDownload(ids.first, ids.second)?.let { candidates += it }
            // Public Storage objects are accepted when the owner has made them readable.
            candidates += STORAGE + "cloud%2F${enc(ids.first)}%2F${enc(ids.second)}%2Fprojectfiles.zip?alt=media"
            candidates += STORAGE + "share%2Fu%2F${enc(ids.first)}%2Fp%2F${enc(ids.second)}%2Fprojectfiles.zip?alt=media"
            for (url in candidates) {
                if (!url.startsWith("http", ignoreCase = true)) continue
                if (fetchTo(url, target, MAX_PACKAGE, onProgress) && isPackage(target)) return target
                target.delete()
            }
            throw AlightImportException("Alight did not provide a downloadable package for this link")
        } catch (e: Throwable) {
            target.delete()
            if (e is AlightImportException) throw e
            throw AlightImportException(e.message ?: "Alight download failed")
        }
    }

    private fun resolve(uri: Uri): Pair<String, String>? {
        val text = URLDecoder.decode(uri.toString(), "UTF-8")
        share.find(uri.path.orEmpty())?.let { return it.groupValues[1] to it.groupValues[2] }
        share.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
        queryShare.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
        var connection: HttpURLConnection? = null
        try {
            connection = URL(uri.toString()).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 25_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 Schnitt")
            connection.requestMethod = "GET"
            connection.inputStream.use { it.readBytes() }
            val final = URLDecoder.decode(connection.url.toString(), "UTF-8")
            share.find(final)?.let { return it.groupValues[1] to it.groupValues[2] }
            share.find(connection.getHeaderField("Location").orEmpty())?.let { return it.groupValues[1] to it.groupValues[2] }
            queryShare.find(final)?.let { return it.groupValues[1] to it.groupValues[2] }
        } catch (_: Throwable) {
            // The caller reports the link as unavailable.
        } finally {
            connection?.disconnect()
        }
        return null
    }

    private fun metadata(uid: String, pid: String): JSONObject? {
        val body = JSONObject().put("data", JSONObject()
            .put("uid", uid).put("pid", pid).put("platform", "android")
            .put("appBuild", 1080000).put("acctTestMode", "normal"))
        val response = post(METADATA, body) ?: return null
        val result = response.optJSONObject("result") ?: response
        if (result.has("result") && !result.optString("result").equals("success", true)) return null
        return result.optJSONObject("info") ?: result
    }

    private fun requestDownload(uid: String, pid: String): String? {
        val body = JSONObject().put("data", JSONObject()
            .put("uid", uid).put("pid", pid).put("platform", "android")
            .put("appBuild", 1080000).put("liteVersion", false).put("acctTestMode", "normal"))
        val response = post(DOWNLOAD, body) ?: return null
        val result = response.optJSONObject("result") ?: response
        if (result.has("result") && !result.optString("result").equals("success", true)) return null
        return firstUrl(result)
    }

    private fun firstUrl(value: Any?): String? = when (value) {
        is JSONObject -> downloadKeys.firstNotNullOfOrNull { key ->
            value.optString(key).takeIf { it.startsWith("http") }
                ?: firstUrl(value.opt(key))
        } ?: value.keys().asSequence().firstNotNullOfOrNull { firstUrl(value.opt(it)) }
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { firstUrl(value.opt(it)) }
        is String -> value.takeIf {
            it.startsWith("http") && (it.contains(".zip", true) || it.contains("projectfiles", true) || it.contains("download", true))
        }
        else -> null
    }

    private fun post(endpoint: String, body: JSONObject): JSONObject? {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Schnitt")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return null
            JSONObject(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchTo(url: String, target: File, limit: Long, onProgress: (Long, Long) -> Unit): Boolean {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Schnitt")
            setRequestProperty("Accept", "*/*")
        }
        return try {
            if (connection.responseCode !in 200..299) return false
            val expected = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    var last = -1L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > limit) return false
                        output.write(buffer, 0, read)
                        val step = if (expected > 0) total * 20 / expected else total / (256L * 1024)
                        if (step != last) { last = step; onProgress(total, expected) }
                    }
                    onProgress(total, if (expected > 0) expected else total)
                }
            }
            target.isFile
        } catch (_: Throwable) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun isPackage(file: File): Boolean {
        if (file.length() < 4) return false
        return file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }

    private fun enc(value: String) = Uri.encode(value)
}
