package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.Expressions
import app.absentmotion.editor.addAudio
import app.absentmotion.editor.addCamera
import app.absentmotion.editor.addEffectToChosen
import app.absentmotion.editor.addImage
import app.absentmotion.editor.addLight
import app.absentmotion.editor.addNull
import app.absentmotion.editor.addText
import app.absentmotion.editor.addVideo
import app.absentmotion.editor.bakeExpression
import app.absentmotion.editor.changeSettings
import app.absentmotion.editor.deleteSelected
import app.absentmotion.editor.expressionText
import app.absentmotion.editor.keepCopy
import app.absentmotion.library.AppSettings
import app.absentmotion.editor.chosenLayers
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.audio.audioDeclaration
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.model.Keyframe
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effects
import app.absentmotion.model.effectProperty
import app.absentmotion.model.removeEffect
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframes
import app.absentmotion.model.mediaUris
import app.absentmotion.model.replaceMedia
import app.absentmotion.model.withContent
import app.absentmotion.model.withEffect
import app.absentmotion.model.withKeyframes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A small, local MCP server exposed by the editor. It speaks JSON-RPC 2.0 over
 * streamable HTTP so an external AI agent can inspect and drive a project while
 * the editor is open. It is off by default, binds to the LAN only when enabled,
 * and always requires the generated bearer token.
 *
 * The same tool catalogue powers the in-app Gemini agent. That agent never
 * needs the MCP socket or the bearer token; it calls [McpTools] directly.
 */
object McpServer {
    private const val MAX_BODY = 8 * 1024 * 1024
    @Volatile private var server: ServerSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var token: String = ""
    @Volatile private var port: Int = 8765
    @Volatile private var appContext: Context? = null
    @Volatile private var secure = true
    private val epoch = AtomicLong()

    val running: Boolean get() = server?.isClosed == false
    val currentPort: Int get() = port
    val currentToken: String get() = token

    fun sync(context: Context, settings: AppSettings) {
        appContext = context.applicationContext
        secure = true
        if (settings.mcpEnabled && settings.mcpToken.isBlank()) settings.mcpToken = newToken()
        if (!settings.mcpEnabled) { stop(); return }
        if (running && port == settings.mcpPort && token == settings.mcpToken) return
        stop()
        port = settings.mcpPort.coerceIn(1024, 65535)
        token = settings.mcpToken
        start(port, token)
    }

    fun address(): String {
        val ip = lanAddress() ?: "127.0.0.1"
        return "${if (secure) "https" else "http"}://$ip:$port/mcp"
    }

    fun certificate(): String? = appContext?.let { LocalTls.certificatePem(it) }?.takeIf { secure }

    private fun lanAddress(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { it is InetAddress && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun newToken(): String {
        val bytes = ByteArray(18)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun startLocal(port: Int, token: String) {
        this.port = port
        this.token = token
        secure = false
        runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", port)) } }
            .getOrNull()?.let { socket ->
                server = socket
                thread(isDaemon = true, name = "Schnitt MCP test") { acceptLoop(socket) }
            }
    }

    private fun start(port: Int, token: String) {
        val context = appContext
        val generation = epoch.incrementAndGet()
        worker = thread(isDaemon = true, name = "Schnitt MCP") {
            val socket = if (secure && context != null) LocalTls.serverSocket(context, port)
            else runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull()
            if (socket == null) return@thread
            if (generation != epoch.get()) { runCatching { socket.close() }; return@thread }
            server = socket
            acceptLoop(socket)
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            thread(isDaemon = true, name = "Schnitt MCP client") { serve(client) }
        }
    }

    fun stop() {
        epoch.incrementAndGet()
        runCatching { server?.close() }
        server = null
        worker = null
    }

    private data class OAuthClient(val id: String, val secret: String, val redirects: Set<String>)
    private data class OAuthCode(val clientId: String, val redirect: String, val challenge: String, val expires: Long)
    private val oauthClients = java.util.concurrent.ConcurrentHashMap<String, OAuthClient>()
    private val oauthCodes = java.util.concurrent.ConcurrentHashMap<String, OAuthCode>()

    private fun serve(client: Socket) {
        // A client that connects and stalls must never take the editor down.
        runCatching { serveRequest(client) }.onFailure { runCatching { client.close() } }
    }

    private fun serveRequest(client: Socket) {
        client.use { socket ->
            if (socket is SSLSocket) {
                socket.soTimeout = 15_000
                runCatching { socket.startHandshake() }.getOrElse { return }
            }
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val request = lines.firstOrNull()?.split(' ') ?: return
            val method = request.getOrNull(0) ?: return
            val target = request.getOrNull(1) ?: return
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val base = baseUrl(lines)
            if (method == "OPTIONS") return reply(socket, 204, null)
            if (method == "GET" && path == "/health") return reply(socket, 200, JSONObject().put("ok", true).put("name", "schnitt-mcp"))
            if (method == "GET" && path == "/certificate") {
                val pem = certificate() ?: return reply(socket, 404, JSONObject().put("error", "no certificate"))
                return replyText(socket, 200, "application/x-pem-file", pem)
            }
            if (method == "GET" && path.startsWith("/.well-known/oauth-protected-resource")) {
                return reply(socket, 200, JSONObject()
                    .put("resource", "$base/mcp")
                    .put("authorization_servers", JSONArray().put(base))
                    .put("bearer_methods_supported", JSONArray().put("header")))
            }
            if (method == "GET" && (path == "/.well-known/oauth-authorization-server" || path == "/.well-known/openid-configuration")) {
                return reply(socket, 200, JSONObject()
                    .put("issuer", base)
                    .put("authorization_endpoint", "$base/authorize")
                    .put("token_endpoint", "$base/token")
                    .put("registration_endpoint", "$base/register")
                    .put("response_types_supported", JSONArray().put("code"))
                    .put("grant_types_supported", JSONArray().put("authorization_code").put("refresh_token"))
                    .put("code_challenge_methods_supported", JSONArray().put("S256"))
                    .put("token_endpoint_auth_methods_supported", JSONArray().put("none").put("client_secret_post")))
            }
            if (method == "POST" && path == "/register") {
                val body = readBody(input, lines) ?: return reply(socket, 411, JSONObject().put("error", "content length required"))
                val json = runCatching { JSONObject(body) }.getOrElse { return reply(socket, 400, JSONObject().put("error", "invalid client metadata")) }
                val redirects = LinkedHashSet<String>()
                json.optJSONArray("redirect_uris")?.let { array ->
                    for (i in 0 until array.length()) array.optString(i).takeIf { it.startsWith("https://") || it.startsWith("http://127.0.0.1") || it.startsWith("http://localhost") }?.let(redirects::add)
                }
                if (redirects.isEmpty()) return reply(socket, 400, JSONObject().put("error", "redirect_uri required"))
                val client = OAuthClient(newToken().take(24), newToken(), redirects)
                oauthClients[client.id] = client
                return reply(socket, 201, JSONObject()
                    .put("client_id", client.id).put("client_secret", client.secret)
                    .put("client_id_issued_at", System.currentTimeMillis() / 1000)
                    .put("redirect_uris", JSONArray(client.redirects.toList()))
                    .put("token_endpoint_auth_method", "client_secret_post")
                    .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                    .put("response_types", JSONArray().put("code")))
            }
            if (method == "GET" && path == "/authorize") return authorizePage(socket, query, null, base)
            if (method == "POST" && path == "/authorize") {
                val body = readBody(input, lines) ?: return reply(socket, 411, JSONObject().put("error", "content length required"))
                val values = form(body)
                val client = oauthClients[values["client_id"]]
                val redirectTo = values["redirect_uri"].orEmpty()
                if (client == null || redirectTo !in client.redirects) return replyText(socket, 400, "text/plain; charset=utf-8", "Unknown client or redirect URI.")
                if (values["access_token"] != token) return authorizePage(socket, encodeForm(values), "That token does not match this editor.", base)
                val code = newToken()
                oauthCodes[code] = OAuthCode(client.id, redirectTo, values["code_challenge"].orEmpty(), System.currentTimeMillis() + 300_000)
                val sep = if ('?' in redirectTo) '&' else '?'
                val state = values["state"].orEmpty().let { if (it.isBlank()) "" else "&state=" + java.net.URLEncoder.encode(it, "UTF-8") }
                return redirect(socket, "$redirectTo${sep}code=$code$state")
            }
            if (method == "POST" && path == "/token") {
                val body = readBody(input, lines) ?: return reply(socket, 411, JSONObject().put("error", "content length required"))
                val values = form(body)
                if (values["grant_type"] == "refresh_token") return tokenReply(socket)
                val code = values["code"].orEmpty()
                val saved = oauthCodes.remove(code) ?: return reply(socket, 400, JSONObject().put("error", "invalid_grant"))
                if (saved.expires < System.currentTimeMillis()) return reply(socket, 400, JSONObject().put("error", "invalid_grant"))
                val client = oauthClients[values["client_id"]] ?: return reply(socket, 400, JSONObject().put("error", "invalid_client"))
                if (saved.clientId != client.id) return reply(socket, 400, JSONObject().put("error", "invalid_client"))
                if (saved.challenge.isNotBlank() && challenge(values["code_verifier"].orEmpty()) != saved.challenge) {
                    return reply(socket, 400, JSONObject().put("error", "invalid_grant").put("error_description", "PKCE verification failed"))
                }
                return tokenReply(socket)
            }
            if (method != "POST" || (path != "/mcp" && path != "/")) return reply(socket, 404, JSONObject().put("error", "not found"))
            val auth = lines.firstOrNull { it.startsWith("Authorization:", true) }?.substringAfter(':')?.trim()
            if (token.isNotBlank() && auth != "Bearer $token") {
                return reply(socket, 401, JSONObject().put("error", "unauthorized"),
                    "WWW-Authenticate: Bearer resource_metadata=\"$base/.well-known/oauth-protected-resource\"\r\n")
            }
            val text = readBody(input, lines) ?: return reply(socket, 411, JSONObject().put("error", "content length required"))
            val response = runCatching { handle(text) }.getOrElse { error ->
                JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL).put("error", JSONObject().put("code", -32603).put("message", error.message ?: "internal error"))
            }
            if (response == null) reply(socket, 202, null) else reply(socket, 200, response)
        }
    }

    private fun baseUrl(lines: List<String>): String {
        fun header(name: String) = lines.firstOrNull { it.startsWith("$name:", true) }?.substringAfter(':')?.trim()?.ifBlank { null }
        val host = header("Host") ?: "127.0.0.1:$port"
        val proto = header("X-Forwarded-Proto") ?: header("X-Forwarded-Scheme") ?: if (secure) "https" else "http"
        return "$proto://$host"
    }

    private fun readBody(input: InputStream, lines: List<String>): String? {
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: return null
        if (length < 0 || length > MAX_BODY) return ""
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(body, 0, read, Charsets.UTF_8)
    }

    private fun form(body: String): Map<String, String> = body.split('&').mapNotNull { part ->
        if (part.isBlank()) null else {
            val key = java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8")
            val value = java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            key to value
        }
    }.toMap()

    private fun encodeForm(values: Map<String, String>): String = values.entries.joinToString("&") { (k, v) ->
        java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
    }

    private fun challenge(verifier: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    private fun tokenReply(socket: Socket) = reply(socket, 200, JSONObject()
        .put("access_token", token).put("token_type", "Bearer").put("expires_in", 31_536_000)
        .put("refresh_token", token).put("scope", "mcp"))

    private fun authorizePage(socket: Socket, query: String, error: String?, base: String) {
        val values = form(query)
        val client = oauthClients[values["client_id"]]
        val redirectTo = values["redirect_uri"].orEmpty()
        if (client == null || redirectTo !in client.redirects) return replyText(socket, 400, "text/plain; charset=utf-8", "Unknown OAuth client. Add the connector again.")
        fun hidden(field: String) = "<input type=\"hidden\" name=\"$field\" value=\"" + values[field].orEmpty().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;") + "\">"
        val message = if (error != null) "<div class=\"err\">" + error.replace("<", "&lt;") + "</div>" else ""
        val html = "<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>Connect Schnitt</title>" +
            "<style>body{background:#101418;color:#e8eef5;font:16px system-ui;margin:0;padding:28px}main{max-width:420px;margin:auto}h1{font-size:22px}label{display:block;margin:18px 0 6px;color:#9fb0c2}input[type=password]{width:100%;box-sizing:border-box;background:#1b2229;border:1px solid #33414e;color:#fff;border-radius:10px;padding:12px;font:15px monospace}button{margin-top:18px;width:100%;background:#5fe3a1;color:#08120c;border:0;border-radius:10px;padding:13px;font-weight:700}small{color:#9fb0c2;line-height:1.45;display:block;margin-top:14px}.err{color:#ff9c9c;margin-top:12px}</style>" +
            "<main><h1>Connect Schnitt to Claude</h1>" +
            "<small>Paste the bearer token shown in Schnitt's server panel. Claude will only be able to read and edit the project while this editor is open.</small>" +
            message +
            "<form method=\"post\" action=\"/authorize\">" +
            hidden("client_id") + hidden("redirect_uri") + hidden("state") + hidden("code_challenge") + hidden("code_challenge_method") + hidden("response_type") +
            "<label for=\"t\">Bearer token</label>" +
            "<input id=\"t\" type=\"password\" name=\"access_token\" autocomplete=\"off\" required autofocus>" +
            "<button type=\"submit\">Connect</button></form></main>"
        replyText(socket, 200, "text/html; charset=utf-8", html)
    }

    private fun redirect(socket: Socket, location: String) {
        val head = "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        BufferedOutputStream(socket.getOutputStream()).use { it.write(head.toByteArray(Charsets.US_ASCII)); it.flush() }
    }

    private fun readHead(input: InputStream): String? {
        val out = StringBuilder()
        while (out.length < 64 * 1024) {
            val c = input.read()
            if (c < 0) return null
            out.append(c.toChar())
            if (out.endsWith("\r\n\r\n")) return out.toString()
        }
        return null
    }

    private fun replyText(socket: Socket, code: Int, type: String, body: String, extra: String = "") {
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 411 -> "Length Required"; 413 -> "Payload Too Large"; else -> "Response" }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $type\r\n")
            append(extra)
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Strict-Transport-Security: max-age=86400\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        BufferedOutputStream(socket.getOutputStream()).use { out -> out.write(head); out.write(bytes); out.flush() }
    }

    private fun reply(socket: Socket, code: Int, body: JSONObject?, extra: String = "") {
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ").append(when (code) { 200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"; 302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 411 -> "Length Required"; 413 -> "Payload Too Large"; else -> "OK" }).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append(extra)
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: authorization, content-type, mcp-session-id\r\n")
            append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
            append("Connection: close\r\n")
            if (bytes != null) append("Content-Length: ").append(bytes.size).append("\r\n")
            else append("Content-Length: 0\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        BufferedOutputStream(socket.getOutputStream()).use { out ->
            out.write(head)
            if (bytes != null) out.write(bytes)
            out.flush()
        }
    }

    internal fun handle(text: String): JSONObject? {
        val request = JSONObject(text)
        val id = request.opt("id") ?: return null // notification
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()
        fun ok(result: Any) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        fun fail(code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message))
        return try {
            when (method) {
                "initialize" -> ok(JSONObject()
                    .put("protocolVersion", "2025-06-18")
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "schnitt-editor").put("version", "1.0.0")))
                "ping" -> ok(JSONObject())
                "tools/list" -> ok(JSONObject().put("tools", toolDefinitions()))
                "tools/call" -> {
                    val name = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val result = McpTools.call(name, args, appContext)
                    ok(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result))).put("isError", false))
                }
                else -> fail(-32601, "unknown method $method")
            }
        } catch (t: Throwable) {
            fail(-32603, t.message ?: t.javaClass.simpleName)
        }
    }

    internal fun toolDefinitions(): JSONArray = JSONArray().apply {
        put(tool("scene.info", "Current project size, frame rate, duration, title, selection, layer and media counts. Call this first.", "{}"))
        put(tool("scene.xml", "The complete project XML. Read this before broad edits or when replicating a reference. Large projects may be shortened; use layer/property tools for details.", "{}"))
        put(tool("scene.apply_xml", "Replace the whole project with XML produced by scene.xml. Undoable. The XML must be a complete <scene> document.", """{"xml":{"type":"string"}}""", listOf("xml")))
        put(tool("scene.change", "Change canvas width, height, frame rate or project title. Omitted fields stay unchanged.", """{"width":{"type":"integer"},"height":{"type":"integer"},"fps":{"type":"integer"},"title":{"type":"string"}}"""))
        put(tool("layer.list", "Every layer id, label, kind, time range, parent, visibility and effect count.", "{}"))
        put(tool("layer.select", "Select a layer by id.", """{"id":{"type":"integer"}}""", listOf("id")))
        put(tool("layer.add", "Add a layer. Kind is null, camera, light, text, image, video or audio. For text pass text; for media pass uri (content://, file:// or https://).", """{"kind":{"type":"string"},"text":{"type":"string"},"uri":{"type":"string"},"label":{"type":"string"},"atMs":{"type":"number"},"durationSec":{"type":"number"}}""", listOf("kind")))
        put(tool("layer.delete", "Delete the selected layer.", "{}"))
        put(tool("layer.set", "Set a property or attribute. Properties: location, scale, rotation, opacity, tilt, size, or any element property. Attributes: label, startTime, endTime, parent, hidden, clippingMask, fillType, mediaFillMode, font, align, wrapWidth. Special: content sets text body. Values are comma-separated floats unless the attribute is text.", """{"id":{"type":"integer"},"property":{"type":"string"},"value":{"type":"string"}}""", listOf("id", "property", "value")))
        put(tool("layer.attrs", "Read all attributes of a layer.", """{"id":{"type":"integer"}}""", listOf("id")))
        put(tool("layer.reorder", "Move a layer to a new stacking index. 0 is the bottom layer.", """{"id":{"type":"integer"},"index":{"type":"integer"}}""", listOf("id", "index")))
        put(tool("keyframe.add", "Add a keyframe in milliseconds. Omit value to reuse the current property value. Easing is linear, ease, ease-in, ease-out or a cubic name.", """{"id":{"type":"integer"},"property":{"type":"string"},"timeMs":{"type":"number"},"value":{"type":"string"},"easing":{"type":"string"}}""", listOf("id", "property", "timeMs")))
        put(tool("property.list", "List a layer's transform and property values, keyframes and expressions.", """{"id":{"type":"integer"}}""", listOf("id")))
        put(tool("keyframe.list", "List all keyframes for one property.", """{"id":{"type":"integer"},"property":{"type":"string"}}""", listOf("id", "property")))
        put(tool("keyframe.remove", "Remove the keyframe nearest a time in milliseconds.", """{"id":{"type":"integer"},"property":{"type":"string"},"timeMs":{"type":"number"}}""", listOf("id", "property", "timeMs")))
        put(tool("expression.get", "Read the stored expression text for a property.", """{"id":{"type":"integer"},"property":{"type":"string"}}""", listOf("id", "property")))
        put(tool("expression.set", "Bake an expression into keyframes using the expression editor language.", """{"id":{"type":"integer"},"property":{"type":"string"},"expression":{"type":"string"}}""", listOf("id", "property", "expression")))
        put(tool("expression.eval", "Evaluate an expression at a time without editing.", """{"id":{"type":"integer"},"expression":{"type":"string"},"timeMs":{"type":"number"}}""", listOf("id", "expression")))
        put(tool("media.list", "List every media item declared by the project, including unused imports, with type, size, duration and usage count.", "{}"))
        put(tool("media.add", "Import and place an image, video or audio layer. Accepts an existing project URI, a content:// picker URI, a file:// URI or an https:// media URL. Media is copied into the project when copy is true. atMs places it at that project time; durationSec sets the layer length. kind may be auto, image, video or audio.", """{"uri":{"type":"string"},"kind":{"type":"string"},"atMs":{"type":"number"},"durationSec":{"type":"number"},"label":{"type":"string"},"copy":{"type":"boolean"}}""", listOf("uri")))
        put(tool("media.replace", "Replace every use of one project media URI with another imported image, video or audio file, keeping layers and keyframes intact.", """{"old":{"type":"string"},"uri":{"type":"string"},"kind":{"type":"string"},"copy":{"type":"boolean"}}""", listOf("old", "uri")))
        put(tool("effect.list", "Search the effect catalogue. Returns editable parameter ids, types, defaults and ranges, plus preset names. Use effect.add to apply one.", """{"query":{"type":"string"},"category":{"type":"string"},"limit":{"type":"integer"}}"""))
        put(tool("effect.add", "Add an effect to the selected layer(s) or the given layerIds. Resolve the effect by id, key or name. properties may be a flat object of parameter values; use effect.set for animated values.", """{"effectId":{"type":"string"},"layerIds":{"type":"array","items":{"type":"integer"}},"preset":{"type":"string"},"properties":{"type":"object"}}""", listOf("effectId")))
        put(tool("effect.list_on_layer", "List the effects on a layer, their indexes and current parameter values.", """{"id":{"type":"integer"}}""", listOf("id")))
        put(tool("effect.set", "Set an effect parameter on a layer. Use t 0..1 for an effect keyframe or timeMs for project time. Omit keyframes to change the static value.", """{"id":{"type":"integer"},"index":{"type":"integer"},"name":{"type":"string"},"value":{"type":"string"},"timeMs":{"type":"number"}}""", listOf("id", "index", "name", "value")))
        put(tool("effect.remove", "Remove an effect from a layer by index.", """{"id":{"type":"integer"},"index":{"type":"integer"}}""", listOf("id", "index")))
        put(tool("time.set", "Move the playhead to a time in milliseconds.", """{"timeMs":{"type":"number"},"playing":{"type":"boolean"}}""", listOf("timeMs")))
        put(tool("edit.undo", "Undo the last edit.", "{}"))
        put(tool("edit.redo", "Redo the last undone edit.", "{}"))
    }

    private fun tool(name: String, description: String, properties: String, required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", description)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject(properties)).put("required", JSONArray(required)))

    fun catalog(): List<Pair<String, String>> = toolDefinitions().let { list ->
        (0 until list.length()).map { i -> list.getJSONObject(i).let { it.getString("name") to it.getString("description") } }
    }

    internal fun context(): Context? = appContext
}

object McpBridge {
    @Volatile private var current: EditorState? = null
    @Volatile private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(state: EditorState, context: Context? = null) {
        current = state
        if (context != null) appContext = context.applicationContext
    }

    fun detach(state: EditorState) {
        if (current === state) {
            current = null
            appContext = null
        }
    }

    internal fun context(): Context? = appContext

    fun <T> onMain(block: (EditorState) -> T): T {
        val state = current ?: error("Open a project first: the editor is not attached")
        if (Looper.myLooper() == Looper.getMainLooper()) return block(state)
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        main.post { result = runCatching { block(state) }; latch.countDown() }
        check(latch.await(120, TimeUnit.SECONDS)) { "editor did not answer" }
        return result!!.getOrThrow()
    }
}

internal object McpTools {
    fun call(name: String, args: JSONObject, context: Context? = null): String = when (name) {
        "scene.info" -> McpBridge.onMain { state ->
            val s = state.scene
            JSONObject().put("projectId", state.projectId).put("title", s.title)
                .put("width", s.width).put("height", s.height).put("fps", s.fps)
                .put("durationMs", s.totalTime).put("layers", s.layers.size).put("media", s.media.size)
                .put("effects", s.layers.sumOf { it.effects.size })
                .put("selectedId", state.selectedId ?: JSONObject.NULL)
                .put("timeMs", state.timeMs).put("playing", state.playing)
                .put("inGroup", state.inGroup).put("groupPath", JSONArray(state.groupPath)).toString(2)
        }
        "scene.xml" -> McpBridge.onMain { state ->
            val xml = state.scene.toXml()
            if (xml.length <= 1_500_000) xml else
                "PROJECT XML IS TOO LARGE TO SEND IN ONE CALL. Use layer.list, layer.attrs, property.list and effect.list_on_layer for targeted inspection.\n" + xml.take(1_500_000)
        }
        "scene.apply_xml" -> McpBridge.onMain { state ->
            val scene = Scene.parse(args.getString("xml"))
            state.edit("mcp-xml") { scene }
            "applied ${scene.layers.size} layers and ${scene.media.size} media items"
        }
        "scene.change" -> McpBridge.onMain { state ->
            var scene = state.scene
            val width = args.optInt("width", scene.width)
            val height = args.optInt("height", scene.height)
            val fps = args.optInt("fps", scene.fps)
            if (width != scene.width || height != scene.height || fps != scene.fps) {
                state.changeSettings(width.coerceIn(16, 16384), height.coerceIn(16, 16384), fps.coerceIn(1, 240), scene.bgColor)
            }
            if (args.has("title")) {
                val title = args.getString("title").take(120)
                state.edit("mcp-title") { it.withTitle(title) }
            }
            "changed project"
        }
        "layer.list" -> McpBridge.onMain { state ->
            JSONArray().apply {
                for (l in state.scene.layers) put(JSONObject().put("id", l.id).put("label", l.label).put("kind", l.kind)
                    .put("start", l.startTime).put("end", l.endTime).put("selected", l.id == state.selectedId)
                    .put("parent", l.node["parent"]?.toLongOrNull() ?: JSONObject.NULL).put("hidden", l.hidden)
                    .put("effects", l.effects.size))
            }.toString(2)
        }
        "layer.select" -> McpBridge.onMain { state ->
            val id = args.getLong("id")
            require(state.scene.layer(id) != null) { "no layer $id" }
            state.selectedId = id
            "selected $id"
        }
        "layer.add" -> McpBridge.onMain { state ->
            val kind = args.optString("kind", "null")
            when (kind) {
                "camera" -> state.addCamera()
                "light" -> state.addLight()
                "text" -> {
                    state.addText()
                    val id = state.selectedId
                    if (id != null) {
                        val text = args.optString("text", "")
                        state.edit("mcp-text") { s ->
                            var next = s.update(id) { it.withContent(text) }
                            val label = args.optString("label", "")
                            if (label.isNotBlank()) next = next.update(id) { it.copy(node = it.node.with("label", label.take(120))) }
                            next
                        }
                    }
                }
                "image", "video", "audio" -> return@onMain mediaAdd(state, args, context, kind)
                else -> state.addNull()
            }
            "added $kind"
        }
        "layer.delete" -> McpBridge.onMain { state -> state.deleteSelected(); "deleted" }
        "layer.set" -> McpBridge.onMain { state ->
            val id = args.getLong("id")
            val property = args.getString("property")
            val value = args.getString("value")
            val layer = state.scene.layer(id) ?: error("no layer $id")
            when (property) {
                "content" -> state.edit("mcp-content") { s -> s.update(id) { it.withContent(value) } }
                "label", "startTime", "endTime", "parent", "hidden", "clippingMask", "fillType", "mediaFillMode", "font", "align", "wrapWidth" -> {
                    state.edit("mcp-attr") { s -> s.update(id) { it.copy(node = it.node.with(property, value.takeIf { property != "parent" } ?: value)) } }
                }
                "size" -> if (layer.kind == "text") state.edit("mcp-attr") { s -> s.update(id) { it.copy(node = it.node.with("size", value)) } }
                else -> {
                    val ref = refOf(layer, property)
                    require(layer.element(ref) != null) { "no property $property" }
                    val values = floats(value)
                    state.edit("mcp-set") { s -> s.update(id) { it.withValue(ref, formatFloats(values)) } }
                }
            }
            "set $property"
        }
        "layer.attrs" -> McpBridge.onMain { state ->
            val layer = state.scene.layer(args.getLong("id")) ?: error("no layer")
            JSONObject(layer.node.attrs).toString(2)
        }
        "layer.reorder" -> McpBridge.onMain { state ->
            val id = args.getLong("id")
            require(state.scene.layer(id) != null) { "no layer $id" }
            val index = args.getInt("index")
            state.edit("mcp-reorder") { it.reorder(id, index.coerceAtLeast(0)) }
            "reordered $id to $index"
        }
        "property.list" -> McpBridge.onMain { state ->
            val layer = state.scene.layer(args.getLong("id")) ?: error("no layer")
            JSONArray().apply {
                for (ref in listOf(PropRef.Location, PropRef.Scale, PropRef.Rotation, PropRef.Opacity, PropRef.Tilt)) {
                    val element = layer.element(ref) ?: continue
                    put(JSONObject().put("property", ref.name).put("value", element["value"]).put("keyframes", element.keyframes.size)
                        .put("expression", element.children.firstOrNull { it.name == "expression" }?.get("value") ?: JSONObject.NULL))
                }
                for (node in layer.node.children) if (node.name == "property") put(JSONObject().put("property", node["name"]).put("value", node["value"])
                    .put("keyframes", node.keyframes.size).put("expression", node.children.firstOrNull { it.name == "expression" }?.get("value") ?: JSONObject.NULL))
            }.toString(2)
        }
        "keyframe.add" -> McpBridge.onMain { state ->
            val id = args.getLong("id")
            val property = args.getString("property")
            val layer = state.scene.layer(id) ?: error("no layer $id")
            val ref = refOf(layer, property)
            val element = layer.element(ref) ?: error("no property $property")
            val span = (layer.endTime - layer.startTime).coerceAtLeast(1)
            val t = ((args.getDouble("timeMs") - layer.startTime) / span).toFloat().coerceIn(0f, 1f)
            val value = args.optString("value").ifBlank { element["value"] ?: "0" }
            val keys = element.keyframes.filter { kotlin.math.abs(it.t - t) > 1e-5f } + Keyframe(t, value, args.optString("easing", "linear"))
            state.edit("mcp-key") { s -> s.update(id) { it.withElement(ref, element.withKeyframes(keys.sortedBy { k -> k.t })) } }
            "keyframe $property at $t"
        }
        "keyframe.list" -> McpBridge.onMain { state ->
            val layer = state.scene.layer(args.getLong("id")) ?: error("no layer")
            val ref = refOf(layer, args.getString("property"))
            JSONArray().apply { layer.element(ref)?.keyframes?.forEach { put(JSONObject().put("t", it.t).put("value", it.v).put("easing", it.e ?: "linear")) } }.toString(2)
        }
        "keyframe.remove" -> McpBridge.onMain { state ->
            val layer = state.scene.layer(args.getLong("id")) ?: error("no layer")
            val property = args.getString("property")
            val ref = refOf(layer, property)
            val element = layer.element(ref) ?: error("no property $property")
            val span = (layer.endTime - layer.startTime).coerceAtLeast(1)
            val t = ((args.getDouble("timeMs") - layer.startTime) / span).toFloat()
            val nearest = element.keyframes.minByOrNull { kotlin.math.abs(it.t - t) } ?: error("no keyframes")
            val keys = element.keyframes.filter { it !== nearest }
            state.edit("mcp-key-remove") { sc -> sc.update(layer.id) { it.withElement(ref, element.withKeyframes(keys)) } }
            "removed keyframe at ${nearest.t}"
        }
        "expression.get" -> McpBridge.onMain { state ->
            val layer = state.scene.layer(args.getLong("id")) ?: error("no layer")
            layer.expressionText(refOf(layer, args.getString("property"))) ?: ""
        }
        "expression.set" -> McpBridge.onMain { state ->
            val layer = state.scene.layer(args.getLong("id")) ?: error("no layer")
            val property = args.getString("property")
            state.edit("mcp-expression") { it.bakeExpression(layer.id, refOf(layer, property), args.getString("expression"), property) }
            "baked $property"
        }
        "expression.eval" -> McpBridge.onMain { state ->
            val layer = state.scene.layer(args.getLong("id")) ?: error("no layer")
            val ms = args.optDouble("timeMs", state.timeMs.toDouble())
            val span = (layer.endTime - layer.startTime).coerceAtLeast(1)
            val t = ((ms - layer.startTime) / span).toFloat().coerceIn(0f, 1f)
            Expressions.parts(args.getString("expression")).mapIndexed { i, part ->
                Expressions.evaluate(part, (ms - layer.startTime).toFloat() / 1000f, t, state.scene.frameAt(ms.toInt()), state.scene.fps,
                    state.scene.width, state.scene.height, i, 0f)
            }.joinToString(",")
        }
        "media.list" -> McpBridge.onMain { state ->
            val uses = state.scene.media.associate { media -> media.uri to state.scene.layers.count { media.uri in it.node.mediaUris() } }
            JSONArray().apply {
                for (media in state.scene.media) put(JSONObject().put("uri", media.uri).put("title", media.title).put("type", media.type)
                    .put("size", media.size).put("duration", media.duration).put("width", media.width).put("height", media.height)
                    .put("uses", uses[media.uri] ?: 0).put("missing", state.scene.isMissing(media.uri)))
            }.toString(2)
        }
        "media.add" -> mediaAdd(McpBridge.onMain { it }, args, context, args.optString("kind", "auto"))
        "media.replace" -> mediaReplace(McpBridge.onMain { it }, args, context)
        "effect.list" -> effectList(args, context)
        "effect.add" -> effectAdd(McpBridge.onMain { it }, args, context)
        "effect.list_on_layer" -> McpBridge.onMain { state ->
            val layer = state.scene.layer(args.getLong("id")) ?: error("no layer")
            JSONArray().apply {
                layer.effects.forEachIndexed { index, effect ->
                    put(JSONObject().put("index", index).put("id", effect["id"]).put("properties", JSONObject().apply {
                        for (p in effect.children.filter { it.name == "property" }) put(p["name"].orEmpty(), p["value"])
                    }))
                }
            }.toString(2)
        }
        "effect.set" -> effectSet(McpBridge.onMain { it }, args)
        "effect.remove" -> McpBridge.onMain { state ->
            val layer = state.scene.layer(args.getLong("id")) ?: error("no layer")
            val index = args.getInt("index")
            require(index in layer.effects.indices) { "no effect $index" }
            state.edit("mcp-effect-remove") { s -> s.update(layer.id) { it.removeEffect(index) } }
            "removed effect $index"
        }
        "time.set" -> McpBridge.onMain { state ->
            val ms = args.getDouble("timeMs").toInt()
            state.frame = state.scene.frameAt(ms).coerceIn(0, state.scene.lastFrame)
            if (args.has("playing")) state.playing = args.getBoolean("playing")
            "time ${state.timeMs}"
        }
        "edit.undo" -> McpBridge.onMain { state -> state.undo(); "undone" }
        "edit.redo" -> McpBridge.onMain { state -> state.redo(); "redone" }
        else -> error("unknown tool $name")
    }

    private fun refOf(layer: app.absentmotion.model.Layer, property: String): PropRef =
        if (layer.element(PropRef(PropRef.Kind.Transform, property)) != null) PropRef(PropRef.Kind.Transform, property) else PropRef(PropRef.Kind.Child, property)

    private fun floats(text: String): FloatArray =
        text.split(',', ' ', '\t').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray().takeIf { it.isNotEmpty() } ?: floatArrayOf(0f)

    private fun mediaAdd(state: EditorState, args: JSONObject, context: Context?, requested: String): String {
        val app = context ?: McpBridge.context() ?: error("No editor context available")
        val source = args.getString("uri")
        val (uri, temp) = materialize(app, source)
        try {
            val kind = requested.takeIf { it != "auto" } ?: guessKind(app, uri)
            val declaration = runBlocking(Dispatchers.IO) {
                when (kind) {
                    "audio" -> audioDeclaration(app, uri)
                    "image" -> mediaDeclaration(app, uri, "image/*")
                    else -> mediaDeclaration(app, uri)
                }
            } ?: error("$source is not a supported $kind file")
            val kept = if (args.optBoolean("copy", true)) runBlocking(Dispatchers.IO) {
                keepCopy(app, state.settings, state.store, state.projectId, declaration, state.scene)
            } else declaration
            val label = args.optString("label", "")
            val atMs = args.optDouble("atMs", state.timeMs.toDouble())
            val duration = args.optDouble("durationSec", -1.0)
            return McpBridge.onMain { current ->
                current.frame = current.scene.frameAt(atMs.toInt()).coerceIn(0, current.scene.lastFrame)
                when {
                    kept.type.startsWith("audio/") -> current.addAudio(kept)
                    kept.type.startsWith("video/") -> current.addVideo(kept)
                    else -> current.addImage(kept)
                }
                val id = current.selectedId ?: error("layer was not created")
                if (label.isNotBlank() || duration > 0) current.edit("mcp-media") { scene ->
                    var next = scene.update(id) { layer ->
                        var node = layer.node
                        if (label.isNotBlank()) node = node.with("label", label.take(120))
                        if (duration > 0) node = node.with("endTime", "${scene.endTimeFor(scene.frameAt(current.timeMs), (duration * 1000).toInt())}")
                        layer.copy(node = node)
                    }
                    next
                }
                "added ${kept.type} ${kept.title} as layer $id"
            }
        } finally {
            if (temp != null && args.optBoolean("copy", true)) temp.delete()
        }
    }

    private fun mediaReplace(state: EditorState, args: JSONObject, context: Context?): String {
        val app = context ?: McpBridge.context() ?: error("No editor context available")
        val old = args.getString("old")
        val source = args.getString("uri")
        val (uri, temp) = materialize(app, source)
        try {
            val kind = args.optString("kind", "auto").takeIf { it != "auto" } ?: guessKind(app, uri)
            val declaration = runBlocking(Dispatchers.IO) {
                when (kind) {
                    "audio" -> audioDeclaration(app, uri)
                    "image" -> mediaDeclaration(app, uri, "image/*")
                    else -> mediaDeclaration(app, uri)
                }
            } ?: error("$source is not a supported $kind file")
            val kept = if (args.optBoolean("copy", true)) runBlocking(Dispatchers.IO) {
                keepCopy(app, state.settings, state.store, state.projectId, declaration, state.scene)
            } else declaration
            McpBridge.onMain { current -> current.edit("mcp-media-replace") { it.replaceMedia(old, kept) } }
            return "replaced $old with ${kept.uri}"
        } finally {
            if (temp != null && args.optBoolean("copy", true)) temp.delete()
        }
    }

    private fun effectList(args: JSONObject, context: Context?): String {
        val app = context ?: McpBridge.context() ?: error("No editor context available")
        val catalog = EffectCatalog.read(app)
        val query = args.optString("query", "").trim().lowercase()
        val category = args.optString("category", "").trim().lowercase()
        val limit = args.optInt("limit", 40).coerceIn(1, 120)
        val matches = catalog.listed().filter { effect ->
            (category.isEmpty() || effect.category == category) &&
                (query.isEmpty() || effect.name.lowercase().contains(query) || effect.id.lowercase().contains(query) || effect.key.lowercase().contains(query))
        }.take(limit)
        return JSONArray().apply {
            for (effect in matches) put(JSONObject().put("id", effect.id).put("key", effect.key).put("name", effect.name)
                .put("category", effect.category).put("description", effect.description)
                .put("presets", JSONArray(effect.presets.map { it.name }))
                .put("params", JSONArray().apply {
                    for (p in effect.params) put(JSONObject().put("id", p.id).put("label", p.label).put("type", p.kind)
                        .put("default", p.default).put("min", p.min).put("max", p.max).put("step", p.step)
                        .put("options", JSONArray(p.options)))
                }))
        }.toString(2)
    }

    private fun effectAdd(state: EditorState, args: JSONObject, context: Context?): String {
        val app = context ?: McpBridge.context() ?: error("No editor context available")
        val catalog = EffectCatalog.read(app)
        val wanted = args.optString("effectId", "").trim()
        val effect = catalog.definitions.firstOrNull { it.id == wanted }
            ?: catalog.definitions.firstOrNull { it.key.equals(wanted, true) || it.name.equals(wanted, true) }
            ?: catalog.definitions.firstOrNull { it.name.contains(wanted, true) || it.key.contains(wanted, true) }
            ?: error("no effect '$wanted'")
        val preset = args.optString("preset", "").takeIf { it.isNotBlank() }?.let { name ->
            effect.presets.firstOrNull { it.slug.equals(name, true) || it.name.equals(name, true) }
        }
        val ids = if (args.has("layerIds")) {
            val a = args.getJSONArray("layerIds")
            (0 until a.length()).map { a.getLong(it) }.toSet()
        } else state.chosenLayers.map { it.id }.toSet()
        if (ids.isEmpty()) error("select a layer first or pass layerIds")
        val properties = args.optJSONObject("properties")
        val added = state.addEffectToChosen(ids) { layer ->
            val node = effect.instance(preset, layer.endTime - layer.startTime)
            properties?.let { applyEffectProperties(node, it, layer.endTime - layer.startTime) } ?: node
        }
        if (added == 0) error("none of those layers can take effects")
        state.engine.loadEffectAsync(effect)
        return "added ${effect.name} to $added layer(s)"
    }

    private fun effectSet(state: EditorState, args: JSONObject): String {
        val layer = state.scene.layer(args.getLong("id")) ?: error("no layer")
        val index = args.getInt("index")
        require(index in layer.effects.indices) { "no effect $index" }
        val name = args.getString("name")
        val value = args.getString("value")
        val t = args.optDouble("timeMs", Double.NaN)
        state.edit("mcp-effect-set") { scene ->
            scene.update(layer.id) { current ->
                current.withEffect(index) { effect ->
                    val property = effect.effectProperty(name) ?: XmlNode("property", mapOf("name" to name, "type" to "float"))
                    val updated = when {
                        t.isNaN() -> property.with("value", value)
                        else -> {
                            val span = (layer.endTime - layer.startTime).coerceAtLeast(1)
                            val local = ((t - layer.startTime) / span).toFloat().coerceIn(0f, 1f)
                            val keys = property.keyframes.toMutableList()
                            keys.removeAll { kotlin.math.abs(it.t - local) < 1e-5f }
                            keys += Keyframe(local, value, args.optString("easing", "linear"))
                            property.withKeyframes(keys.sortedBy { it.t })
                        }
                    }
                    effect.copy(children = if (effect.effectProperty(name) == null) effect.children + updated else effect.children.map { if (it === effect.effectProperty(name)) updated else it })
                }
            }
        }
        return "set $name"
    }

    private fun applyEffectProperties(effect: XmlNode, values: JSONObject, lengthMs: Int): XmlNode {
        var node = effect
        for (key in values.keys()) {
            val existing = node.effectProperty(key)
            val property = existing ?: XmlNode("property", mapOf("name" to key, "type" to "float"))
            val updated = when (val raw = values.opt(key)) {
                is JSONArray -> {
                    val array = raw
                    if (array.length() > 0 && array.opt(0) is JSONObject) {
                        val keys = (0 until array.length()).mapNotNull { i ->
                            val item = array.optJSONObject(i) ?: return@mapNotNull null
                            val at = if (item.has("timeMs")) (item.optDouble("timeMs") / lengthMs).toFloat() else item.optDouble("t", 0.0).toFloat()
                            val v = item.opt("value")?.toString() ?: item.optString("v", "0")
                            Keyframe(at.coerceIn(0f, 1f), v, item.optString("easing", "linear"))
                        }
                        property.withKeyframes(keys.sortedBy { it.t })
                    } else property.with("value", (0 until array.length()).joinToString(",") { array.opt(it).toString() })
                }
                null -> property.with("value", "0")
                else -> property.with("value", raw.toString())
            }
            node = if (existing == null) node.copy(children = node.children + updated)
            else node.copy(children = node.children.map { if (it === existing) updated else it })
        }
        return node
    }

    private fun guessKind(context: Context, uri: Uri): String {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        if (type.startsWith("audio/")) return "audio"
        if (type.startsWith("image/")) return "image"
        if (type.startsWith("video/")) return "video"
        val name = uri.lastPathSegment.orEmpty().lowercase()
        return when {
            name.matches(Regex(".*\\.(mp3|m4a|wav|aac|ogg|opus|flac)$")) -> "audio"
            name.matches(Regex(".*\\.(png|jpe?g|webp|gif|bmp|heic|avif|svg)$")) -> "image"
            else -> "video"
        }
    }

    private fun materialize(context: Context, source: String): Pair<Uri, File?> {
        val parsed = Uri.parse(source)
        if (parsed.scheme != "http" && parsed.scheme != "https") return parsed to null
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 60_000
        connection.readTimeout = 180_000
        connection.setRequestProperty("User-Agent", "Schnitt/1.0")
        val mime = connection.contentType?.substringBefore(';').orEmpty()
        val ext = when {
            mime.contains("png") -> "png"; mime.contains("jpeg") -> "jpg"; mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"; mime.startsWith("audio/") -> "mp3"; mime.startsWith("video/") -> "mp4"
            else -> source.substringAfterLast('.', "bin").take(5).takeIf { it.all(Char::isLetterOrDigit) } ?: "bin"
        }
        val file = File.createTempFile("agent-media-", ".$ext", context.cacheDir)
        try {
            connection.inputStream.use { input ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > 256L shl 20) error("The media URL is larger than 256 MB")
                        output.write(buffer, 0, n)
                    }
                }
            }
            return Uri.fromFile(file) to file
        } catch (t: Throwable) {
            file.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }
}
