package app.absentmotion.library

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Resolves public Alight Motion share links without requiring the Alight app.
 *
 * A short link redirects to /am/share/u/<uid>/p/<pid>.  The public metadata
 * function gives us the project package information and the download function
 * returns a signed package URL when the package is still available.
 */
object AlightLinks {
    private const val METADATA = "https://us-central1-alight-creative.cloudfunctions.net/getProjectMetadata"
    private const val DOWNLOAD = "https://us-central1-alight-creative.cloudfunctions.net/requestProjectDownload"
    private const val MAX_PACKAGE = 512L shl 20
    private val share = Regex("""/am/share/u/([^/?#]+)/p/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val hosts = setOf(
        "alight.link", "alight.page.link", "alight.to", "alightcreative.com", "www.alightcreative.com",
        "alight.creative.firebase.com", "alightmotion.com", "www.alightmotion.com", "alight-creative.firebaseapp.com",
    )

    fun isShareLink(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host in hosts || host.endsWith(".alight.link") || host.endsWith(".page.link")
    }

    suspend fun download(context: Context, uri: Uri): File? {
        val target = File(context.cacheDir, "alight-${System.nanoTime()}.zip")
        return try {
            val resolved = resolve(uri)
            val metadata = metadata(resolved.first, resolved.second) ?: return null
            val download = metadata.optString("download").takeIf { it.startsWith("http") }
                ?: requestDownload(resolved.first, resolved.second)
            if (download.isNullOrBlank()) return null
            fetchTo(download, target, MAX_PACKAGE) ?: return null
            if (!target.isFile || target.length() < 4 || target.readBytes().let { it[0] != 'P'.code.toByte() || it[1] != 'K'.code.toByte() }) {
                target.delete()
                return null
            }
            target
        } catch (_: Throwable) {
            target.delete()
            null
        }
    }

    private fun resolve(uri: Uri): Pair<String, String> {
        val direct = share.find(uri.path.orEmpty())
        if (direct != null) return direct.groupValues[1] to direct.groupValues[2]
        var connection: HttpURLConnection? = null
        try {
            connection = URL(uri.toString()).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 25_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 Schnitt")
            connection.requestMethod = "GET"
            connection.inputStream.use { it.readBytes() }
            val final = connection.url.toString()
            val found = share.find(final) ?: share.find(connection.getHeaderField("Location").orEmpty())
            if (found != null) return found.groupValues[1] to found.groupValues[2]
        } finally {
            connection?.disconnect()
        }
        throw IllegalArgumentException("not an Alight share link")
    }

    private fun metadata(uid: String, pid: String): JSONObject? {
        val body = JSONObject()
            .put("data", JSONObject()
                .put("uid", uid).put("pid", pid).put("platform", "android")
                .put("appBuild", 1028417).put("acctTestMode", "normal"))
        return post(METADATA, body)?.optJSONObject("result")?.also {
            if (!it.optString("result").equals("success", true)) return null
            it.optJSONObject("info")?.let { info -> it.put("download", info.optString("download")) }
        }
    }

    private fun requestDownload(uid: String, pid: String): String? {
        val body = JSONObject().put("data", JSONObject()
            .put("uid", uid).put("pid", pid).put("platform", "android")
            .put("appBuild", 1028417).put("liteVersion", false).put("acctTestMode", "normal"))
        return post(DOWNLOAD, body)?.optJSONObject("result")?.takeIf { it.optString("result").equals("success", true) }
            ?.optString("downloadUri")?.takeIf { it.startsWith("http") }
    }

    private fun post(endpoint: String, body: JSONObject): JSONObject? {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
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

    private fun fetchTo(url: String, target: File, limit: Long): File? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Schnitt")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > limit) return null
                        output.write(buffer, 0, read)
                    }
                }
            }
            target
        } finally {
            connection.disconnect()
        }
    }
}
