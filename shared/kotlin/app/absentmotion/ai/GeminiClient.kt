package app.absentmotion.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A deliberately small Gemini REST client.  The key is only ever sent in the
 *  x-goog-api-key header to Google's Generative Language host, never to a
 *  redirect target, a media URL, a scene file or the local MCP server. */
object GeminiClient {
    const val DEFAULT_MODEL = "gemini-2.5-flash"
    private const val HOST = "generativelanguage.googleapis.com"
    private const val BASE = "https://$HOST/v1beta/models"
    private const val MAX_RESPONSE = 16 shl 20
    private const val TIMEOUT = 120_000

    class Failure(message: String) : Exception(message)

    data class Reply(val content: JSONObject?, val text: String, val calls: List<Call>, val thoughts: String = "")

    data class Call(val name: String, val args: JSONObject)

    /**
     * Streams the answer instead of waiting for one huge response.  Besides
     * showing progress immediately, each chunk resets the socket read timeout,
     * so a long tool plan can no longer look like a dead connection.
     */
    suspend fun generate(
        key: String,
        model: String,
        contents: JSONArray,
        tools: JSONArray?,
        system: String?,
        onUpdate: ((text: String, thought: Boolean) -> Unit)? = null,
    ): Reply {
        if (key.isBlank()) throw Failure("Add a Gemini API key first.")
        val body = JSONObject().put("contents", contents)
        if (tools != null && tools.length() > 0) {
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
            body.put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")))
        }
        if (!system.isNullOrBlank()) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        body.put("generationConfig", JSONObject()
            .put("temperature", 0.55)
            .put("maxOutputTokens", 8192)
            .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
        return stream("$BASE/${safeModel(model)}:streamGenerateContent?alt=sse", key, body.toString().toByteArray(Charsets.UTF_8), onUpdate)
    }

    private fun parseCandidate(candidate: JSONObject, text: StringBuilder, thoughts: StringBuilder, calls: MutableList<Call>, onUpdate: ((String, Boolean) -> Unit)?): Pair<Long, Long> {
        var pushed = 0L
        var thoughtPushed = 0L
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return 0L to 0L
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val chunk = part.optString("text")
            if (chunk.isNotEmpty()) {
                if (part.optBoolean("thought")) {
                    thoughts.append(chunk); thoughtPushed += chunk.length
                } else {
                    text.append(chunk); pushed += chunk.length
                }
            }
            part.optJSONObject("functionCall")?.let { call ->
                val name = call.optString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                if (name.isNotBlank() && calls.none { it.name == name && it.args.toString() == args.toString() }) calls += Call(name, args)
            }
        }
        if (pushed > 0L || thoughtPushed > 0L) {
            val latest = if (pushed > 0L) text.toString().takeLast(180) else thoughts.toString().takeLast(180)
            onUpdate?.invoke(latest, pushed == 0L)
        }
        return pushed to thoughtPushed
    }

    private fun stream(url: String, key: String, bytes: ByteArray, onUpdate: ((String, Boolean) -> Unit)?): Reply {
        val connection = open(url, key)
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.doOutput = true
        connection.outputStream.use { it.write(bytes) }
        val code = connection.responseCode
        if (code !in 200..299) throw failure(connection, code)
        val text = StringBuilder()
        val thoughts = StringBuilder()
        val calls = ArrayList<Call>()
        if (connection.contentType?.contains("text/event-stream", ignoreCase = true) != true) {
            val root = JSONObject(read(connection))
            val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw Failure(root.optJSONObject("promptFeedback")?.optString("blockReason")?.let { "Gemini blocked the request: $it" } ?: "Gemini returned no answer.")
            parseCandidate(candidate, text, thoughts, calls, onUpdate)
            return Reply(assemble(text.toString(), calls), text.toString().trim(), calls, thoughts.toString().trim())
        }
        var saw = false
        var lastPush = 0L
        connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val root = runCatching { JSONObject(data) }.getOrNull() ?: continue
                saw = true
                val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                val now = System.currentTimeMillis()
                val callback = if (now - lastPush >= 90L) onUpdate else null
                val (pushed, thought) = parseCandidate(candidate, text, thoughts, calls, callback)
                if (pushed > 0L || thought > 0L) lastPush = now
            }
        }
        if (!saw) throw Failure("Gemini closed the response before sending an answer.")
        return Reply(assemble(text.toString(), calls), text.toString().trim(), calls, thoughts.toString().trim())
    }

    private fun assemble(text: String, calls: List<Call>): JSONObject? {
        if (text.isBlank() && calls.isEmpty()) return null
        val parts = JSONArray()
        if (text.isNotBlank()) parts.put(JSONObject().put("text", text))
        for (call in calls) parts.put(JSONObject().put("functionCall", JSONObject().put("name", call.name).put("args", call.args)))
        return JSONObject().put("role", "model").put("parts", parts)
    }

    /** Uploads a user-selected reference video to Gemini's Files API and waits
     *  until it is ready for analysis.  The file is uploaded to Google only
     *  because the user explicitly asked the agent to study it. */
    suspend fun uploadVideo(context: android.content.Context, key: String, uri: android.net.Uri, displayName: String): Uploaded {
        if (key.isBlank()) throw Failure("Add a Gemini API key first.")
        return withContext(Dispatchers.IO) {
            val temp = java.io.File.createTempFile("schnitt-ref-", ".video", context.cacheDir)
            try {
                var size = 0L
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            size += read
                            if (size > 200L shl 20) throw Failure("The reference video is larger than 200 MB.")
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw Failure("The reference video could not be read.")
                if (size <= 0L) throw Failure("The reference video is empty.")
                val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
                val start = startUpload(key, size, mime, displayName)
                val uploaded = finishUpload(key, start, temp, size)
                waitActive(key, uploaded)
                uploaded
            } finally {
                runCatching { temp.delete() }
            }
        }
    }

    data class Uploaded(val uri: String, val mime: String, val name: String)

    private data class UploadSession(val url: String)

    private fun startUpload(key: String, size: Long, mime: String, name: String): UploadSession {
        val connection = open("https://$HOST/upload/v1beta/files", key)
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
        connection.setRequestProperty("X-Goog-Upload-Command", "start")
        connection.setRequestProperty("X-Goog-Upload-Header-Content-Length", "$size")
        connection.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
        val body = JSONObject().put("file", JSONObject().put("display_name", name.take(120))).toString()
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val response = connection.responseCode
        if (response !in 200..299) throw failure(connection, response)
        val url = connection.getHeaderField("X-Goog-Upload-URL") ?: throw Failure("Gemini did not return an upload session.")
        if (!url.startsWith("https://$HOST/")) throw Failure("Gemini returned an unexpected upload location.")
        return UploadSession(url)
    }

    private fun finishUpload(key: String, session: UploadSession, file: java.io.File, size: Long): Uploaded {
        val connection = open(session.url, key)
        connection.requestMethod = "PUT"
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setRequestProperty("X-Goog-Upload-Offset", "0")
        connection.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        connection.setFixedLengthStreamingMode(size)
        connection.doOutput = true
        connection.outputStream.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
        val response = connection.responseCode
        if (response !in 200..299) throw failure(connection, response)
        val root = JSONObject(read(connection))
        val uploaded = root.optJSONObject("file") ?: throw Failure("Gemini did not accept the reference video.")
        val uri = uploaded.optString("uri")
        if (uri.isBlank()) throw Failure("Gemini did not return a reference video id.")
        return Uploaded(uri, uploaded.optString("mimeType", "video/mp4"), uploaded.optString("name", file.name))
    }

    private suspend fun waitActive(key: String, file: Uploaded) {
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            val connection = open(file.uri, key)
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code !in 200..299) throw failure(connection, code)
            val root = JSONObject(read(connection))
            val state = root.optString("state")
            if (state == "ACTIVE" || state.isEmpty()) return
            if (state == "FAILED") throw Failure("Gemini could not process the reference video.")
            delay(1500)
        }
        throw Failure("Gemini is still processing the reference video. Try again in a moment.")
    }

    private fun open(url: String, key: String): HttpURLConnection {
        val parsed = URL(url)
        if (parsed.protocol != "https" || !parsed.host.equals(HOST, ignoreCase = true)) throw Failure("Refusing an unexpected Gemini endpoint.")
        val connection = parsed.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        connection.setRequestProperty("x-goog-api-key", key)
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    private fun failure(connection: HttpURLConnection, code: Int): Failure {
        val raw = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText().take(4000) } }.getOrNull()
        val message = runCatching { raw?.let { JSONObject(it).optJSONObject("error")?.optString("message") } }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "Gemini request failed (HTTP $code)."
        return Failure(message)
    }

    private fun read(connection: HttpURLConnection): String = connection.inputStream.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_RESPONSE) throw Failure("Gemini returned an oversized response.")
            out.write(buffer, 0, n)
        }
        out.toString(Charsets.UTF_8.name())
    }

    private fun safeModel(model: String): String {
        val clean = model.trim().removePrefix("models/")
        return clean.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) } ?: DEFAULT_MODEL
    }
}
