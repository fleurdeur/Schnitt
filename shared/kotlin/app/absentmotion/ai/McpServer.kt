package app.absentmotion.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addCamera
import app.absentmotion.editor.addLight
import app.absentmotion.editor.addNull
import app.absentmotion.editor.addText
import app.absentmotion.editor.deleteSelected
import app.absentmotion.library.AppSettings
import app.absentmotion.model.Keyframe
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframes
import app.absentmotion.model.withKeyframes
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A small, local MCP server exposed by the editor.  It speaks JSON-RPC 2.0 over
 * streamable HTTP so an external AI agent can inspect and drive a project while
 * the editor is open.  It is off by default, binds to the LAN only when enabled,
 * and always requires the generated bearer token.
 */
object McpServer {
    private const val MAX_BODY = 8 * 1024 * 1024
    @Volatile private var server: ServerSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var token: String = ""
    @Volatile private var port: Int = 8765

    val running: Boolean get() = server?.isClosed == false
    val currentPort: Int get() = port
    val currentToken: String get() = token

    fun sync(context: Context, settings: AppSettings) {
        if (settings.mcpEnabled && settings.mcpToken.isBlank()) settings.mcpToken = newToken()
        if (!settings.mcpEnabled) { stop(); return }
        if (running && port == settings.mcpPort && token == settings.mcpToken) return
        stop()
        port = settings.mcpPort.coerceIn(1024, 65535)
        token = settings.mcpToken
        start(port, token, context.applicationContext)
    }

    fun address(): String {
        val ip = lanAddress() ?: "127.0.0.1"
        return "http://$ip:$port/mcp"
    }

    private fun lanAddress(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun newToken(): String {
        val bytes = ByteArray(18)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun start(port: Int, token: String, context: Context) {
        val socket = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull() ?: return
        server = socket
        worker = thread(isDaemon = true, name = "Schnitt MCP") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "Schnitt MCP client") { serve(client) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        worker = null
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val request = lines.firstOrNull()?.split(' ') ?: return
            val method = request.getOrNull(0) ?: return
            val path = request.getOrNull(1)?.substringBefore('?') ?: "/"
            if (method == "OPTIONS") return reply(socket, 204, null)
            if (method == "GET" && path == "/health") return reply(socket, 200, JSONObject().put("ok", true).put("name", "schnitt-mcp"))
            if (method != "POST" || (path != "/mcp" && path != "/")) return reply(socket, 404, JSONObject().put("error", "not found"))
            val auth = lines.firstOrNull { it.startsWith("Authorization:", true) }?.substringAfter(':')?.trim()
            if (token.isNotBlank() && auth != "Bearer $token") return reply(socket, 401, JSONObject().put("error", "unauthorized"))
            val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY) return reply(socket, 413, JSONObject().put("error", "body too large"))
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val text = String(body, 0, read, Charsets.UTF_8)
            val response = runCatching { handle(text) }.getOrElse { error ->
                JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL).put("error", JSONObject().put("code", -32603).put("message", error.message ?: "internal error"))
            }
            if (response == null) reply(socket, 202, null) else reply(socket, 200, response)
        }
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

    private fun reply(socket: Socket, code: Int, body: JSONObject?) {
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ").append(if (code == 200) "OK" else if (code == 202) "Accepted" else if (code == 401) "Unauthorized" else "OK").append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
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
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "schnitt-editor").put("version", "0.1.0")))
                "ping" -> ok(JSONObject())
                "tools/list" -> ok(JSONObject().put("tools", tools()))
                "tools/call" -> {
                    val name = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val result = McpTools.call(name, args)
                    ok(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result))).put("isError", false))
                }
                else -> fail(-32601, "unknown method $method")
            }
        } catch (t: Throwable) {
            fail(-32603, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun tools(): JSONArray = JSONArray().apply {
        put(tool("scene.info", "Project size, frame rate, duration, selection and layer count.", "{}"))
        put(tool("scene.xml", "The complete project XML. Read this before applying broad edits.", "{}"))
        put(tool("scene.apply_xml", "Replace the project with XML produced by scene.xml. Undoable.", """{"xml":{"type":"string"}}""", listOf("xml")))
        put(tool("layer.list", "Every layer id, label, kind and time range.", "{}"))
        put(tool("layer.select", "Select a layer by id.", """{"id":{"type":"integer"}}""", listOf("id")))
        put(tool("layer.add", "Add a layer. Kind is null, camera, light or text.", """{"kind":{"type":"string"}}""", listOf("kind")))
        put(tool("layer.delete", "Delete the selected layer.", "{}"))
        put(tool("layer.set", "Set a property value. Values are floats separated by commas.", """{"id":{"type":"integer"},"property":{"type":"string"},"value":{"type":"string"}}""", listOf("id", "property", "value")))
        put(tool("keyframe.add", "Add a keyframe in milliseconds. Omit value to reuse the current property value.", """{"id":{"type":"integer"},"property":{"type":"string"},"timeMs":{"type":"number"},"value":{"type":"string"}}""", listOf("id", "property", "timeMs")))
        put(tool("time.set", "Move the playhead to a time in milliseconds.", """{"timeMs":{"type":"number"},"playing":{"type":"boolean"}}""", listOf("timeMs")))
        put(tool("edit.undo", "Undo the last edit.", "{}"))
        put(tool("edit.redo", "Redo the last undone edit.", "{}"))
    }

    private fun tool(name: String, description: String, properties: String, required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", description)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject(properties)).put("required", JSONArray(required)))
}

object McpBridge {
    @Volatile private var current: EditorState? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(state: EditorState) { current = state }
    fun detach(state: EditorState) { if (current === state) current = null }

    fun <T> onMain(block: (EditorState) -> T): T {
        val state = current ?: error("Open a project first: the editor is not attached")
        if (Looper.myLooper() == Looper.getMainLooper()) return block(state)
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        main.post { result = runCatching { block(state) }; latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)) { "editor did not answer" }
        return result!!.getOrThrow()
    }
}

private object McpTools {
    fun call(name: String, args: JSONObject): String = when (name) {
        "scene.info" -> McpBridge.onMain { state ->
            val s = state.scene
            JSONObject().put("width", s.width).put("height", s.height).put("fps", s.fps)
                .put("durationMs", s.totalTime).put("layers", s.layers.size)
                .put("selectedId", state.selectedId ?: JSONObject.NULL)
                .put("timeMs", state.timeMs).put("playing", state.playing).toString(2)
        }
        "scene.xml" -> McpBridge.onMain { it.scene.toXml() }
        "scene.apply_xml" -> McpBridge.onMain { state ->
            val scene = Scene.parse(args.getString("xml"))
            state.edit("mcp-xml") { scene }
            "applied ${scene.layers.size} layers"
        }
        "layer.list" -> McpBridge.onMain { state ->
            JSONArray().apply {
                for (l in state.scene.layers) put(JSONObject().put("id", l.id).put("label", l.label).put("kind", l.kind)
                    .put("start", l.startTime).put("end", l.endTime).put("selected", l.id == state.selectedId))
            }.toString(2)
        }
        "layer.select" -> McpBridge.onMain { state ->
            val id = args.getLong("id")
            require(state.scene.layer(id) != null) { "no layer $id" }
            state.selectedId = id
            "selected $id"
        }
        "layer.add" -> McpBridge.onMain { state ->
            when (args.optString("kind")) {
                "camera" -> state.addCamera()
                "light" -> state.addLight()
                "text" -> state.addText()
                else -> state.addNull()
            }
            "added ${args.optString("kind", "null")}"
        }
        "layer.delete" -> McpBridge.onMain { state -> state.deleteSelected(); "deleted" }
        "layer.set" -> McpBridge.onMain { state ->
            val id = args.getLong("id")
            val property = args.getString("property")
            val values = floats(args.getString("value"))
            val layer = state.scene.layer(id) ?: error("no layer $id")
            val ref = if (layer.element(PropRef(PropRef.Kind.Transform, property)) != null) PropRef(PropRef.Kind.Transform, property) else PropRef(PropRef.Kind.Child, property)
            state.edit("mcp-set") { s -> s.update(id) { it.withValue(ref, formatFloats(values)) } }
            "set $property"
        }
        "keyframe.add" -> McpBridge.onMain { state ->
            val id = args.getLong("id")
            val property = args.getString("property")
            val layer = state.scene.layer(id) ?: error("no layer $id")
            val ref = if (layer.element(PropRef(PropRef.Kind.Transform, property)) != null) PropRef(PropRef.Kind.Transform, property) else PropRef(PropRef.Kind.Child, property)
            val element = layer.element(ref) ?: error("no property $property")
            val span = (layer.endTime - layer.startTime).coerceAtLeast(1)
            val t = ((args.getDouble("timeMs") - layer.startTime) / span).toFloat().coerceIn(0f, 1f)
            val value = args.optString("value").ifBlank { element["value"] ?: "0" }
            val keys = element.keyframes.filter { kotlin.math.abs(it.t - t) > 1e-5f } + Keyframe(t, value, "linear")
            state.edit("mcp-key") { s -> s.update(id) { it.withElement(ref, element.withKeyframes(keys.sortedBy { k -> k.t })) } }
            "keyframe $property at $t"
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

    private fun floats(text: String): FloatArray =
        text.split(',', ' ', '\t').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray().takeIf { it.isNotEmpty() } ?: floatArrayOf(0f)
}
