package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.editor.EditorState
import app.absentmotion.model.effects
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class AgentMessage(val role: String, val text: String, val tool: String? = null)

/**
 * The in-app creative agent.  It keeps the conversation in memory (never on
 * disk), calls Gemini directly with the user's own key, and executes project
 * changes through the same tool surface as the local MCP server.
 */
object AiAgent {
    val messages = mutableStateListOf<AgentMessage>()
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var contents = JSONArray()
    private var project: String? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    fun reset() {
        job?.cancel()
        job = null
        contents = JSONArray()
        project = null
        ui { messages.clear(); error = null; status = ""; busy = false }
    }

    fun cancel() {
        job?.cancel()
        job = null
        ui { busy = false; status = "Stopped" }
    }

    fun send(context: Context, state: EditorState, text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || busy) return
        val key = state.settings.geminiKey
        if (key.isBlank()) {
            ui { error = "Add your Gemini API key first." }
            return
        }
        start(context, state, key, prompt, null)
    }

    fun replicate(context: Context, state: EditorState, uri: Uri) {
        if (busy) return
        val key = state.settings.geminiKey
        if (key.isBlank()) {
            ui { error = "Add your Gemini API key first." }
            return
        }
        ui { error = null; busy = true; status = "Uploading the reference videoâ€¦" }
        job = scope.launch {
            runCatching {
                val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "reference.mp4" }
                val uploaded = GeminiClient.uploadVideo(context.applicationContext, key, uri, name)
                ui { status = "Studying the reference videoâ€¦" }
                turn(context.applicationContext, state, key, referencePrompt, uploaded)
            }.onFailure { failure ->
                if (failure !is kotlinx.coroutines.CancellationException) ui { error = failure.message ?: "The reference video could not be analysed." }
            }
            ui {
                busy = false
                if (error == null) status = "Ready"
            }
        }
    }

    private fun start(context: Context, state: EditorState, key: String, text: String, video: GeminiClient.Uploaded?) {
        ui { error = null; busy = true; status = "Thinkingâ€¦" }
        job = scope.launch {
            runCatching { turn(context.applicationContext, state, key, text, video) }
                .onFailure { failure ->
                    if (failure !is kotlinx.coroutines.CancellationException) ui { error = failure.message ?: "The agent could not finish." }
                }
            ui {
                busy = false
                if (error == null) status = "Ready"
            }
        }
    }

    private suspend fun turn(context: Context, state: EditorState, key: String, text: String, video: GeminiClient.Uploaded?) {
        if (project != state.projectId) {
            contents = JSONArray()
            ui { messages.clear() }
            project = state.projectId
        }
        val parts = JSONArray().put(JSONObject().put("text", text))
        if (video != null) parts.put(JSONObject().put("fileData", JSONObject().put("mimeType", video.mime).put("fileUri", video.uri)))
        contents.put(JSONObject().put("role", "user").put("parts", parts))
        val said = if (video == null) text else "$text\n\nReference: ${video.name}"
        ui { messages += AgentMessage("user", said) }

        val system = systemPrompt(state)
        var finalText = false
        for (round in 0 until 14) {
            ui { status = if (round == 0) "Thinkingâ€¦" else "Continuingâ€¦" }
            val reply = GeminiClient.generate(key, state.settings.geminiModel, contents, geminiTools(), system)
            if (reply.text.isNotBlank()) {
                val answer = reply.text
                ui { messages += AgentMessage("assistant", answer) }
                finalText = true
            }
            val content = reply.content
            if (content != null) contents.put(content)
            if (reply.calls.isEmpty()) {
                if (!finalText) {
                    ui { messages += AgentMessage("assistant", "I finished the requested edits.") }
                }
                return
            }
            finalText = false
            val responses = JSONArray()
            for (call in reply.calls) {
                ui { status = "Using ${call.name}â€¦" }
                val result = runCatching { McpTools.call(call.name, call.args, context) }
                    .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                val visible = result.take(24_000) + if (result.length > 24_000) "\nâ€¦ truncated in the transcript" else ""
                ui { messages += AgentMessage("tool", visible, call.name) }
                val replyText = if (result.length > 600_000) result.take(600_000) + "\nâ€¦ result truncated" else result
                responses.put(JSONObject().put("functionResponse", JSONObject().put("name", call.name)
                    .put("response", JSONObject().put("result", replyText))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", responses))
            ui { status = "Reviewing the result…" }
        }
        ui { messages += AgentMessage("assistant", "I stopped after reaching the tool-call limit. Ask me to continue if the project still needs work.") }
    }

    private fun phase(tool: String): String = when (tool.substringBefore('.')) {
        "scene" -> if (tool.endsWith("apply_xml") || tool.endsWith("change")) "Rebuilding the project…" else "Inspecting the project…"
        "layer" -> when {
            tool.endsWith("add") -> "Adding a layer…"
            tool.endsWith("delete") -> "Removing a layer…"
            tool.endsWith("set") || tool.endsWith("attrs") -> "Changing layer properties…"
            else -> "Reading layers…"
        }
        "keyframe" -> "Animating a property…"
        "property" -> "Checking editable properties…"
        "expression" -> "Setting up an expression…"
        "media" -> "Importing and preparing media…"
        "effect" -> when {
            tool.endsWith("add") -> "Applying an effect…"
            tool.endsWith("set") -> "Tuning an effect…"
            tool.endsWith("remove") -> "Removing an effect…"
            else -> "Reading the effects library…"
        }
        "time" -> "Setting the timeline…"
        "edit" -> "Undoing the last change…"
        else -> "Using ${tool.replace('.', ' ')}…"
    }

    private fun systemPrompt(state: EditorState): String {
        val info = runCatching {
            McpBridge.onMain { current ->
                val scene = current.scene
                "Project ${scene.title.ifBlank { current.projectId }} Â· ${scene.width}x${scene.height} Â· ${scene.fps} fps Â· ${scene.totalTime} ms Â· " +
                    "${scene.layers.size} layers Â· ${scene.media.size} media items Â· ${scene.layers.sumOf { it.effects.size }} effects Â· " +
                    "selected ${current.selectedId?.toString() ?: "none"} Â· playhead ${current.timeMs} ms"
            }
        }.getOrDefault("A Schnitt project is open.")
        return """
You are the built-in creative agent inside Schnitt, a professional Android motion-graphics and video editor. You have full creative freedom over the open project. Do not merely describe an edit: use tools to make it. Inspect before rewriting, work in small undoable steps, and verify with scene.info or layer.list when useful.

CURRENT PROJECT
$info

HOW SCHNITT WORKS
- A project is an Alight Motion compatible <scene> XML document. Canvas size, fps and totalTime matter. Layer times are milliseconds, absolute on the project timeline.
- Layer tags: shape (rectangles, ellipses, stars, SVG paths, media fills, text-independent shapes), text, audio, embedScene (groups/compositions), camera, light, nullobj and model.
- Transforms live in <transform>: location, scale, rotation, opacity, pivot, skew and tilt. Angle/tilt units follow the editor's XML conventions. Media layers use fillType=media plus fillImage or fillVideo.
- Text layers use fillType=color, <content> for the string, and attributes size, font, align and wrapWidth. Effects are <effect id="..."> children with <property name type value> children; animation uses <kf t="0..1" v="..." e="linear|ease|...">. Keyframe t is normalized over that layer's duration. Never drop existing keyframes.
- Keyframe and expression tools operate on one property at a time. Use comma-separated floats for vector properties and colors as #aarrggbb.
- scene.apply_xml replaces the project and is undoable, but preserve media declarations and existing keyframes. Prefer targeted tools unless the user explicitly asks for a rebuild.
- Media is declared once in <media uri=...> and referenced by layers. media.add imports from content://, file:// or https://, copies into the project, and creates a layer. Effect ids, parameter names, defaults, ranges and presets are discoverable with effect.list. Use effect.add; use effect.set for animated parameters.
- Audio is a layer with src pointing at the media URI. Use media.add with kind=audio or add an audio layer. Keep music and dialogue in sync with the reference.

SMALL XML REFERENCE (shape with media, text and an effect)
<scene title="Demo" width="1080" height="1920" fps="30" totalTime="5000" bgcolor="#ff000000">
  <media uri="content://..." title="clip.mp4" type="video/mp4" width="1080" height="1920" duration="5000"/>
  <shape id="1" label="clip" startTime="0" endTime="4999" fillType="media" fillVideo="content://..." mediaFillMode="fill" s=".rect">
    <transform><location value="540,960,0"/><scale value="1,1"/><rotation value="0"/></transform>
    <property name="size" type="vec2" value="540,960"/>
    <effect id="com.alightcreative.effects.color.adjust"><property name="brightness" type="float" value="0.1"/></effect>
  </shape>
  <text id="2" label="Title" startTime="0" endTime="2999" fillType="color" size="72" font="googlefonts?name=Roboto&weight=700" align="center">
    <transform><location value="540,960,0"/></transform><fillColor value="#ffffffff"/><content>HELLO</content>
  </text>
</scene>

REFERENCE REPLICATION
When a reference video is attached, study its entire flow before editing: shot boundaries and exact timings, pacing, camera movement, transforms, masks, text, typography, colour grade, transitions, effects, overlays, music, sound effects, silence and beat sync. Then rebuild that structure in Schnitt. Use the reference's real duration and frame rate where possible. Reuse the user's existing media when appropriate; otherwise import the URLs or files the user supplied. If a source asset is unavailable, create a faithful procedural placeholder with shapes, text, colour or effects rather than stopping. Match the creative intent, not just a list of effects.

RULES
- Never reveal, print, store in the project, or send the Gemini API key anywhere. It is handled outside your tools.
- Keep edits deterministic and undoable. Do not delete user media or keyframes without a good reason. Do not claim an edit succeeded unless its tool call succeeded.
- When a tool reports an error, fix the arguments or choose another route. Ask the user only when an essential asset or decision is genuinely unavailable.
- Answer briefly after editing: say what you changed and anything the user should review.
""".trimIndent()
    }

    private fun geminiTools(): JSONArray = JSONArray().apply {
        val tools = McpServer.toolDefinitions()
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            put(JSONObject().put("name", tool.getString("name")).put("description", tool.getString("description"))
                .put("parameters", tool.getJSONObject("inputSchema")))
        }
    }

    private val referencePrompt = """
The user attached a reference video and asked you to make a full replica in the open Schnitt project. Watch and study the whole video: every shot, cut, timing, camera move, animation, text, asset, colour choice, transition, effect, audio cue and beat. Then build the replica in the project using the tools. Start by inspecting scene.info, layer.list, media.list and scene.xml. Build the timeline faithfully, including audio and effects. Work until the project is a complete, coherent recreation, then summarise the structure and timings you used.
""".trimIndent()
}
