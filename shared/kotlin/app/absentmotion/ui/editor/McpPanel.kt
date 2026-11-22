package app.absentmotion.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.ai.AgentMessage
import app.absentmotion.ai.AiAgent
import app.absentmotion.ai.GeminiClient
import app.absentmotion.ai.McpServer
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.editor.panels.ThinSwitch
import app.absentmotion.ui.theme.Am

/** The AI agent and local MCP control surface opened from the tools tab. */
@Composable
internal fun McpPanel(state: EditorState) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var key by remember { mutableStateOf(state.settings.geminiKey) }
    var model by remember { mutableStateOf(state.settings.geminiModel) }
    var editingKey by remember { mutableStateOf(key.isBlank()) }
    var keyDraft by remember { mutableStateOf("") }
    var modelDraft by remember { mutableStateOf(model) }
    var showKey by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var told by remember { mutableStateOf(false) }
    var on by remember { mutableStateOf(state.settings.mcpEnabled) }
    var token by remember { mutableStateOf(state.settings.mcpToken) }
    val tools = remember { McpServer.catalog() }
    val endpoint = if (on) McpServer.address() else "Disabled"
    val messages = AiAgent.messages
    val list = rememberLazyListState()
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) AiAgent.replicate(context, state, uri)
    }
    LaunchedEffect(messages.size, AiAgent.busy, AiAgent.status) {
        if (messages.isNotEmpty()) list.scrollToItem(messages.lastIndex)
    }

    fun saveKey() {
        val clean = keyDraft.trim()
        if (clean.isEmpty()) return
        state.settings.geminiKey = clean
        state.settings.geminiModel = modelDraft
        key = clean
        model = state.settings.geminiModel
        keyDraft = ""
        editingKey = false
    }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty() || AiAgent.busy || key.isBlank()) return
        draft = ""
        AiAgent.send(context, state, text)
    }

    Dialog({ state.mcpOpen = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable { state.mcpOpen = false }, Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.92f).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(Am.Panel).navigationBarsPadding().imePadding().clickable { },
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Am.Accent.copy(alpha = 0.14f)), Alignment.Center) {
                        Icon(PanelIcons.Bot, null, Modifier.size(20.dp), tint = Am.Accent)
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("AI AGENT", color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Direct Gemini agent · full project control", color = Am.OnDarkDim, fontSize = 10.sp)
                    }
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Am.Button).clickable { state.mcpOpen = false }, Alignment.Center) {
                        Icon(ChromeIcons.CloseThick, null, Modifier.size(16.dp), tint = Am.OnDark)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tab("AGENT", tab == 0) { tab = 0 }
                    Tab("SERVER", tab == 1) { tab = 1 }
                    Spacer(Modifier.weight(1f))
                    if (key.isNotBlank() && tab == 0) {
                        SmallAction("KEY") { keyDraft = ""; modelDraft = model; editingKey = !editingKey }
                        SmallAction("NEW") { AiAgent.reset(); draft = "" }
                    }
                }

                if (tab == 0) {
                    if (key.isBlank() || editingKey) SetupAgent(
                        keyDraft, { keyDraft = it }, showKey, { showKey = !showKey }, modelDraft, { modelDraft = it },
                        saved = key.isNotBlank(), onSave = ::saveKey,
                        onRemove = {
                            state.settings.geminiKey = ""
                            key = ""
                            keyDraft = ""
                            editingKey = true
                            AiAgent.reset()
                        },
                    ) else AgentChat(
                        messages = messages, listState = list, key = key, model = model, draft = draft,
                        onDraft = { draft = it }, onSubmit = ::submit,
                        onReplicate = { videoPicker.launch(arrayOf("video/*")) },
                        onPrompt = { draft = it },
                        modifier = Modifier.weight(1f),
                    )
                } else ServerTab(
                    state, on, token, endpoint, tools,
                    setEnabled = {
                        on = it
                        state.settings.mcpEnabled = it
                        if (it && state.settings.mcpToken.isBlank()) state.settings.mcpToken = randomToken()
                        token = state.settings.mcpToken
                        McpServer.sync(context.applicationContext, state.settings)
                    },
                    onCopy = { text ->
                        copy(context, text)
                        told = true
                    },
                    told = told,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SetupAgent(
    keyDraft: String, onKey: (String) -> Unit, show: Boolean, toggleShow: () -> Unit,
    modelDraft: String, onModel: (String) -> Unit, saved: Boolean, onSave: () -> Unit, onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("CONNECT GEMINI", color = Am.Caption, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(
            "Use your own Google AI Studio key. It is encrypted with Android Keystore and is only sent to Google's Gemini API. It is never written to the project, logs, backups or the local MCP server.",
            Modifier.padding(top = 6.dp, bottom = 14.dp), color = Am.OnDarkDim, fontSize = 11.sp, lineHeight = 15.sp,
        )
        Text("API KEY", color = Am.Caption, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Am.Field).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = keyDraft, onValueChange = onKey, modifier = Modifier.weight(1f), singleLine = true,
                textStyle = TextStyle(color = Am.OnDark, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Am.Accent),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                decorationBox = { inner ->
                    Box { if (keyDraft.isEmpty()) Text("AIza…", color = Am.Caption, fontSize = 11.sp, fontFamily = FontFamily.Monospace); inner() }
                },
            )
            Text(if (show) "HIDE" else "SHOW", Modifier.padding(start = 8.dp).clickable(onClick = toggleShow), color = Am.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Text("MODEL", Modifier.padding(top = 12.dp), color = Am.Caption, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        BasicTextField(
            value = modelDraft, onValueChange = onModel, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Am.Field).padding(horizontal = 10.dp, vertical = 9.dp),
            textStyle = TextStyle(color = Am.OnDark, fontSize = 11.sp, fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(Am.Accent),
            decorationBox = { inner -> Box { if (modelDraft.isEmpty()) Text(GeminiClient.DEFAULT_MODEL, color = Am.Caption, fontSize = 11.sp); inner() } },
        )
        Text("Suggested: ${GeminiClient.DEFAULT_MODEL}. Any Gemini model with function calling works.", Modifier.padding(top = 5.dp), color = Am.Caption, fontSize = 9.sp, lineHeight = 12.sp)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("SAVE KEY", enabled = keyDraft.trim().isNotEmpty(), onClick = onSave)
            if (saved) Action("REMOVE", enabled = true, onClick = onRemove, quiet = true)
        }
        Spacer(Modifier.height(12.dp))
        Text("The agent can read and edit layers, keyframes, expressions, effects, media, audio, text and the full project XML. You can also attach a reference video and ask it to recreate the whole edit.", color = Am.OnDarkDim, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun AgentChat(
    messages: List<AgentMessage>, listState: androidx.compose.foundation.lazy.LazyListState, key: String, model: String,
    draft: String, onDraft: (String) -> Unit, onSubmit: () -> Unit, onReplicate: () -> Unit, onPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (AiAgent.busy) Am.Accent else Color(0xFF4CD07D)))
            Text(if (AiAgent.busy) AiAgent.status.ifBlank { "Working…" } else "Ready · $model", Modifier.padding(start = 7.dp).weight(1f), color = Am.OnDarkDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("KEY OK", color = Am.Caption, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        if (messages.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text("What should we make?", color = Am.OnDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Ask for edits, effects, titles, animation, audio work, a full project rebuild, or attach a reference video to replicate.", Modifier.padding(top = 6.dp, bottom = 18.dp), color = Am.OnDarkDim, fontSize = 11.sp, lineHeight = 15.sp)
                for (prompt in listOf(
                    "Make a punchy 10-second kinetic type intro using this project's title.",
                    "Review the whole project and fix timing, empty gaps and weak transitions.",
                    "Add a cinematic colour grade, grain and a slow push-in to every video layer.",
                    "Build a complete 30-second promo from the media already in this project.",
                )) {
                    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(9.dp)).background(Am.Field).clickable { onPrompt(prompt) }.padding(12.dp)) {
                        Text(prompt, color = Am.OnDark, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(messages) { message -> Bubble(message) }
                item { Spacer(Modifier.height(2.dp)) }
            }
        }
        AiAgent.error?.let { error ->
            Text(error, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(7.dp)).background(Color(0x33FF5A5A)).padding(8.dp), color = Color(0xFFFFB4B4), fontSize = 10.sp, lineHeight = 14.sp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction("REPLICATE VIDEO") { onReplicate() }
            Spacer(Modifier.weight(1f))
            if (AiAgent.busy) SmallAction("STOP") { AiAgent.cancel() }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f).heightIn(min = 40.dp, max = 120.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Am.Field).padding(horizontal = 11.dp, vertical = 10.dp),
                textStyle = TextStyle(color = Am.OnDark, fontSize = 12.sp, lineHeight = 17.sp), cursorBrush = SolidColor(Am.Accent),
                maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                decorationBox = { inner -> Box { if (draft.isEmpty()) Text("Tell the agent what to change…", color = Am.Caption, fontSize = 12.sp); inner() } },
            )
            Spacer(Modifier.size(7.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (draft.isBlank() || AiAgent.busy) Am.Button else Am.Accent)
                    .clickable(enabled = draft.isNotBlank() && !AiAgent.busy) { onSubmit() }, Alignment.Center,
            ) {
                Icon(PanelIcons.Send, null, Modifier.size(18.dp), tint = if (draft.isBlank() || AiAgent.busy) Am.OnDarkDim else Color.White)
            }
        }
    }
}

@Composable
private fun Bubble(message: AgentMessage) {
    val user = message.role == "user"
    val tool = message.role == "tool"
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = if (user) 310.dp else 360.dp).clip(RoundedCornerShape(10.dp))
                .background(if (user) Am.Accent.copy(alpha = 0.18f) else if (tool) Am.Panel else Am.Field)
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            if (tool) Text(message.tool?.uppercase() ?: "TOOL", color = Am.Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(
                message.text, Modifier.padding(top = if (tool) 3.dp else 0.dp), color = if (tool) Am.OnDarkDim else Am.OnDark,
                fontSize = if (tool) 9.sp else 12.sp, lineHeight = if (tool) 12.sp else 17.sp,
                fontFamily = if (tool) FontFamily.Monospace else FontFamily.Default,
                maxLines = if (tool) 10 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ServerTab(
    state: EditorState, on: Boolean, token: String, endpoint: String, tools: List<Pair<String, String>>,
    setEnabled: (Boolean) -> Unit, onCopy: (String) -> Unit, told: Boolean, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (on && McpServer.running) "Server listening" else if (on) "Server starting" else "Server off", color = if (on) Am.Accent else Am.OnDarkDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Local network only · bearer token required", color = Am.OnDarkDim, fontSize = 10.sp)
            }
            ThinSwitch(on, onChange = setEnabled)
        }
        Label("Endpoint")
        Value(endpoint, mono = true) { onCopy(if (on) McpServer.address() else "") }
        Label("Bearer token")
        Value(token.ifBlank { "Enable MCP to create a token" }, mono = true) { onCopy(token) }
        if (on) {
            Label("HTTPS certificate")
            Value("Self-signed - tap to copy PEM", mono = true) { McpServer.certificate()?.let(onCopy) }
        }
        Text(
            "Point an external MCP client at the HTTPS endpoint and send the token as Authorization: Bearer. Trust the copied certificate once in the client. The server is only reachable while this editor is open. The in-app Gemini agent works without this server and never sees this token.",
            Modifier.padding(top = 10.dp, bottom = 8.dp), color = Am.OnDarkDim, fontSize = 10.sp, lineHeight = 14.sp,
        )
        Label("Claude custom connector")
        Text(
            "Claude's mobile and web connectors connect from Anthropic's cloud, not from this phone, so they cannot use the LAN address above. Expose this port through a public HTTPS tunnel on port 443 that can reach this HTTPS origin without certificate verification (for example cloudflared with --no-tls-verify), then add the tunnel's /mcp URL as a custom connector in Claude. This server advertises OAuth discovery, dynamic registration and PKCE; when Claude opens the approval page, paste the bearer token above. Keep the editor open while Claude is connected.",
            Modifier.padding(top = 4.dp, bottom = 8.dp), color = Am.OnDarkDim, fontSize = 10.sp, lineHeight = 14.sp,
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TOOLS", Modifier.weight(1f), color = Am.Caption, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("${tools.size}", color = Am.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        for ((name, description) in tools) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 5.dp).size(5.dp).clip(CircleShape).background(Am.Accent))
                Column(Modifier.padding(start = 8.dp)) {
                    Text(name, color = Am.OnDark, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(description, color = Am.OnDarkDim, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
        Text(
            if (told) "Copied to the clipboard." else "Every tool is also available to the in-app agent. Keep this token private.",
            Modifier.padding(vertical = 10.dp), color = if (told) Am.Accent else Am.Caption, fontSize = 10.sp,
        )
    }
}

@Composable
private fun Tab(text: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(if (active) Am.Accent.copy(alpha = 0.16f) else Am.Field).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp)) {
        Text(text, color = if (active) Am.Accent else Am.OnDarkDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(Am.Field).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 6.dp)) {
        Text(text, color = Am.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Action(text: String, enabled: Boolean, onClick: () -> Unit, quiet: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (quiet) Am.Field else if (enabled) Am.Accent else Am.Button)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, color = if (quiet || !enabled) Am.OnDarkDim else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), Modifier.padding(top = 10.dp, bottom = 3.dp), color = Am.Caption, fontSize = 9.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Value(text: String, mono: Boolean, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Am.Field).clickable(onClick = onCopy)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = Am.OnDark, fontSize = 10.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, lineHeight = 14.sp)
        Text("COPY", Modifier.padding(start = 8.dp), color = Am.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

private fun randomToken(): String {
    val bytes = ByteArray(18)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun copy(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    runCatching {
        val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        manager.setPrimaryClip(android.content.ClipData.newPlainText("Schnitt MCP", text))
    }
}
