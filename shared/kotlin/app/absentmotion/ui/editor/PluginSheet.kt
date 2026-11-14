package app.absentmotion.ui.editor

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addEffectToChosen
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.plugins.Jsx
import app.absentmotion.plugins.JsxInterpreter
import app.absentmotion.plugins.Plugin
import app.absentmotion.plugins.PluginKind
import app.absentmotion.plugins.PluginStore
import app.absentmotion.plugins.PluginShader
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.home.HomeIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun PluginSheet(state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PluginStore(context) }
    var catalogRevision by remember { mutableIntStateOf(0) }
    val catalog by produceState<EffectCatalog?>(null, catalogRevision) {
        value = withContext(Dispatchers.IO) { runCatching { EffectCatalog.read(context) }.getOrNull() }
    }
    var plugins by remember { mutableStateOf(store.list()) }
    var selected by remember { mutableStateOf<Plugin?>(null) }
    var importing by remember { mutableStateOf(false) }
    var minimized by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = store.import(uri, uri.lastPathSegment)
            importing = false
            plugins = store.list()
            result.plugin?.let { selected = it }
            if (result.plugin != null) {
                catalogRevision++
                state.engine.relabelEffects(context)
            }
            if (result.plugin == null) notice = context.getString(R.string.plugins_import_failed)
        }
    }
    if (minimized) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            Row(
                Modifier.padding(12.dp).width(238.dp).height(42.dp).clip(RoundedCornerShape(10.dp))
                    .background(Am.Panel).border(1.dp, Am.Line, RoundedCornerShape(10.dp))
                    .clickable { minimized = false }.padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HomeIcons.Plugins, null, Modifier.size(16.dp), tint = Am.Accent)
                Text(
                    selected?.name ?: "Plugins",
                    Modifier.weight(1f).padding(start = 8.dp), color = Am.OnDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.size(30.dp).clickable { minimized = false }, Alignment.Center) {
                    Text("□", color = Am.OnDarkDim, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.size(30.dp).clickable { minimized = false; onClose() }, Alignment.Center) {
                    Text("×", color = Am.OnDarkDim, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        FloatingCard("plugin-window", onMinimize = { minimized = true }) {
        Column(Modifier.fillMaxSize().background(Am.Panel).systemBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable { if (selected != null) selected = null else onClose() }, Alignment.Center) {
                    Icon(ExportIcons.Back, stringResource(R.string.action_close), Modifier.size(24.dp), tint = Am.OnDark)
                }
                Text(
                    if (selected == null) stringResource(R.string.plugins_title) else selected!!.name,
                    Modifier.weight(1f), color = Am.OnDark, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (selected == null) Box(
                    Modifier.padding(end = 10.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).background(Am.Accent).clickable(enabled = !importing) { picker.launch(arrayOf("*/*")) }
                        .padding(horizontal = 14.dp),
                    Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(HomeIcons.Upload, null, Modifier.size(16.dp), tint = Am.OnAccent)
                        Spacer(Modifier.width(6.dp))
                        Text(if (importing) stringResource(R.string.plugins_importing) else stringResource(R.string.plugins_import), color = Am.OnAccent, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
            notice?.let {
                Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color = Am.Warning, fontSize = 13.sp)
            }
            val chosen = selected
            if (chosen == null) {
                if (plugins.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(HomeIcons.Plugins, null, Modifier.size(42.dp), tint = Am.HomeMid)
                        Text(stringResource(R.string.plugins_empty), Modifier.padding(top = 14.dp), color = Am.OnDarkDim, fontSize = 14.sp, lineHeight = 20.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                } else LazyColumn(Modifier.fillMaxSize()) {
                    items(plugins, key = { it.id }) { plugin ->
                        PluginRow(plugin) { selected = plugin }
                    }
                }
            } else PluginDetail(
                state = state, plugin = chosen, catalog = catalog,
                onRemove = { store.remove(chosen.id); plugins = store.list(); selected = null },
                onToggle = { enabled -> store.setEnabled(chosen.id, enabled); plugins = store.list(); selected = chosen.copy(enabled = enabled) },
                onNotice = { notice = it },
            )
        }
        }
    }
}

@Composable
private fun PluginRow(plugin: Plugin, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(66.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Am.Well), Alignment.Center) {
            Icon(HomeIcons.Plugins, null, Modifier.size(22.dp), tint = if (plugin.enabled) Am.Accent else Am.OnDarkDim)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(plugin.name, color = Am.OnDark, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kindLabel(plugin.kind) + " · " + plugin.version, color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1)
        }
        Text(if (plugin.enabled) "ON" else "OFF", color = if (plugin.enabled) Am.Accent else Am.OnDarkDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun kindLabel(kind: PluginKind): String = when (kind) {
    PluginKind.Cep -> "CEP"
    PluginKind.Script -> "Script"
    PluginKind.Native -> "Native"
    PluginKind.Effect -> "Effect"
    PluginKind.Shader -> "Shader"
}

@Composable
private fun PluginDetail(state: EditorState, plugin: Plugin, catalog: EffectCatalog?, onRemove: () -> Unit, onToggle: (Boolean) -> Unit, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val detected by produceState(emptyList<String>(), plugin.id) {
        value = withContext(Dispatchers.IO) { discoverProperties(plugin) }
    }
    val layers = state.scene.layers
    var targetId by remember(plugin.id, state.scene) { mutableStateOf(state.selectedId ?: layers.firstOrNull()?.id ?: 0L) }
    var targetOpen by remember { mutableStateOf(false) }
    val generated = catalog?.definitions?.firstOrNull { it.id == PluginShader.effectId(plugin) }
    val values = remember(plugin.id) { mutableStateMapOf<String, String>() }
    androidx.compose.runtime.LaunchedEffect(generated?.key) {
        values.clear()
        generated?.params?.forEach { values[it.id] = it.default }
    }
    fun apply(id: String) {
        val layer = state.scene.layer(targetId) ?: state.selected
        if (layer == null) { onNotice(context.getString(R.string.plugins_no_layer)); return }
        val definition = generated ?: catalog?.definitions?.firstOrNull { it.id == PluginShader.effectId(plugin) }
        if (definition == null) {
            onNotice("The plugin shader is still being prepared. Reopen the plugin in a moment.")
            return
        }
        val configured = definition.copy(params = definition.params.map { p -> values[p.id]?.let { p.copy(default = it) } ?: p })
        state.addEffectToChosen(setOf(layer.id)) { configured.instance(null, layer.endTime - layer.startTime) }
        onNotice("Applied ${definition.name}")
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Layer", color = Am.OnDarkDim, fontSize = 11.sp)
            Box(Modifier.padding(start = 8.dp)) {
                Text(
                    state.scene.layer(targetId)?.label?.ifBlank { state.scene.layer(targetId)?.kind.orEmpty() } ?: "Select",
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Am.Well).clickable { targetOpen = true }.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Am.OnDark, fontSize = 11.sp, maxLines = 1,
                )
                DropdownMenu(targetOpen, { targetOpen = false }) {
                    layers.forEach { layer ->
                        DropdownMenuItem({ Text(layer.label.ifBlank { layer.kind } + " · " + layer.kind, color = Am.OnDark, fontSize = 12.sp) }, onClick = {
                            targetId = layer.id; targetOpen = false
                        })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(plugin.summary.ifBlank { kindLabel(plugin.kind) }, color = Am.OnDarkDim, fontSize = 13.sp, lineHeight = 18.sp)
                if (plugin.kind == PluginKind.Native) Text("Native plug-in converted into an Android effect with its own generated shader.", Modifier.padding(top = 6.dp), color = Am.Accent, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Box(
                Modifier.padding(start = 8.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (plugin.enabled) Am.Accent else Am.Well)
                    .clickable { onToggle(!plugin.enabled) }.padding(horizontal = 12.dp),
                Alignment.Center,
            ) { Text(if (plugin.enabled) stringResource(R.string.plugins_enabled) else stringResource(R.string.plugins_disabled), color = if (plugin.enabled) Am.OnAccent else Am.OnDark, fontSize = 12.sp) }
        }
        if (detected.isNotEmpty()) {
            Text(stringResource(R.string.plugins_properties), Modifier.padding(start = 16.dp, top = 6.dp), color = Am.OnDarkDim, fontSize = 11.sp)
            Text(detected.joinToString(" · "), Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp), color = Am.OnDark, fontSize = 11.sp,
                lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (plugin.kind == PluginKind.Script) {
            ScriptPluginPanel(state, plugin, catalog, targetId, onNotice, Modifier.fillMaxWidth().weight(1f))
        } else if (plugin.panel) {
            val tall = Modifier.fillMaxWidth().weight(1f)
            PluginWebPanel(state, plugin, catalog, targetId, tall, ::apply, onNotice)
        } else {
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(38.dp).clip(RoundedCornerShape(19.dp)).background(Am.Accent)
                .clickable(enabled = generated != null) { apply(generated?.id.orEmpty()) },
            Alignment.Center,
        ) { Text(if (generated == null) "Preparing shader…" else stringResource(R.string.plugins_apply), color = Am.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Text("Generated Android effect", Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp), color = Am.OnDarkDim, fontSize = 12.sp)
        LazyColumn(Modifier.then(if (plugin.panel) Modifier.height(180.dp) else Modifier.weight(1f)).fillMaxWidth()) {
            if (generated != null) {
                item(key = generated.id) {
                    Row(Modifier.fillMaxWidth().height(48.dp).clickable { apply(generated.id) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(HomeIcons.Plugins, null, Modifier.size(18.dp), tint = Am.Accent)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(generated.name, color = Am.OnDark, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (generated.available) "Android controls ready" else "Shader needs attention", color = Am.OnDarkDim, fontSize = 11.sp, maxLines = 1)
                        }
                        Text(stringResource(R.string.plugins_apply), color = Am.Accent, fontSize = 12.sp)
                    }
                    PluginParameterList(generated, values)
                }
            }
            if (plugin.effects.isNotEmpty()) {
                items(plugin.effects) { id ->
                    Row(Modifier.fillMaxWidth().height(42.dp).clickable(enabled = generated != null) { apply(id) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(HomeIcons.Plugins, null, Modifier.size(16.dp), tint = Am.OnDarkDim)
                        Text("Plugin preset · ${id.replace('-', ' ').replace('_', ' ')}", Modifier.weight(1f).padding(start = 12.dp), color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onRemove), Alignment.Center) {
            Text(stringResource(R.string.plugins_remove), color = Am.Warning, fontSize = 14.sp)
        }
    }
}

@Composable
private fun PluginParameterList(definition: app.absentmotion.effects.EffectDefinition, values: MutableMap<String, String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("Properties", Modifier.padding(top = 4.dp, bottom = 4.dp), color = Am.OnDarkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        definition.params.take(40).forEach { param ->
            when (param.kind) {
                "switch" -> {
                    val on = (values[param.id] ?: param.default) == "true"
                    Row(Modifier.fillMaxWidth().height(38.dp).clickable { values[param.id] = if (on) "false" else "true" }, verticalAlignment = Alignment.CenterVertically) {
                        Text(param.label, Modifier.weight(1f), color = Am.OnDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (on) "On" else "Off", color = if (on) Am.Accent else Am.OnDarkDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                "choice" -> {
                    val selected = (values[param.id] ?: param.default).toIntOrNull() ?: 0
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(param.label, color = Am.OnDark, fontSize = 12.sp, maxLines = 1)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            param.options.forEachIndexed { index, option ->
                                val on = index == selected
                                Box(
                                    Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Am.Accent else Am.Well)
                                        .clickable { values[param.id] = index.toString() }.padding(horizontal = 9.dp), Alignment.Center,
                                ) { Text(option, color = if (on) Am.OnAccent else Am.OnDark, fontSize = 10.sp, maxLines = 1) }
                            }
                        }
                    }
                }
                "color", "text" -> {
                    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(param.label, Modifier.width(110.dp), color = Am.OnDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicTextField(
                            value = values[param.id] ?: param.default,
                            onValueChange = { values[param.id] = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Am.OnDark, fontSize = 12.sp),
                            cursorBrush = SolidColor(Am.Accent),
                            modifier = Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(7.dp)).background(Am.Well).padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
                else -> {
                    val min = if (param.min.isFinite()) param.min else 0f
                    val max = if (param.max.isFinite()) param.max else maxOf(min + 1f, 1f)
                    val current = (values[param.id] ?: param.default).toFloatOrNull()?.coerceIn(min, max) ?: min
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(param.label, Modifier.weight(1f), color = Am.OnDark, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(String.format(java.util.Locale.US, "%.2f", current), color = Am.OnDarkDim, fontSize = 10.sp)
                        }
                        Slider(
                            value = current, onValueChange = { values[param.id] = it.toString() },
                            valueRange = min..max.coerceAtLeast(min + 0.001f),
                            colors = SliderDefaults.colors(thumbColor = Am.Accent, activeTrackColor = Am.Accent, inactiveTrackColor = Am.Well),
                            modifier = Modifier.fillMaxWidth().height(26.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptPluginPanel(state: EditorState, plugin: Plugin, catalog: EffectCatalog?, targetId: Long, onNotice: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember(plugin.id) { Jsx.decode(File(plugin.folder, plugin.entry.orEmpty())) }
    var running by remember { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 16.dp)) {
        Text(plugin.summary.ifBlank { stringResource(R.string.plugins_script_note) }, color = Am.OnDarkDim, fontSize = 11.sp, lineHeight = 15.sp)
        if (source == null) {
            Text("Compiled JSXBIN loaded. Use the Android controls below to change its values.", Modifier.padding(top = 10.dp), color = Am.OnDarkDim, fontSize = 12.sp, lineHeight = 17.sp)
        } else {
            Text(source.take(1200), Modifier.weight(1f).padding(top = 8.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                color = Am.OnDark, fontSize = 10.sp, lineHeight = 14.sp)
            Box(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp)).background(Am.Accent)
                    .clickable(enabled = !running) {
                        running = true
                        scope.launch {
                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                JsxInterpreter.execute(state, targetId, source, catalog)
                            }
                            onNotice(result)
                            running = false
                        }
                    },
                Alignment.Center,
            ) { Text(if (running) stringResource(R.string.beat_detecting) else "Run script", color = Am.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
@Composable
private fun PluginWebPanel(state: EditorState, plugin: Plugin, catalog: EffectCatalog?, targetId: Long, modifier: Modifier, applyEffect: (String) -> Unit, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val web = remember(plugin.id) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            settings.cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    runCatching { view.destroy() }
                    onNotice("Plugin renderer stopped; reopen the plugin")
                    return true
                }
            }
        }
    }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val bridge = remember(plugin.id, state, catalog) {
        object {
            @JavascriptInterface fun applyEffect(id: String) = handler.post { applyEffect(id) }
            @JavascriptInterface fun evalScriptCode(code: String): String {
                handler.post {
                    val result = JsxInterpreter.execute(state, targetId, code, catalog)
                    onNotice(result)
                }
                return "queued"
            }
            @JavascriptInterface fun applyEffectByName(name: String) = handler.post {
                val generated = catalog?.definitions?.firstOrNull { it.id == PluginShader.effectId(plugin) }
                if (generated != null) applyEffect(generated.id)
                else onNotice("The generated shader for ${plugin.name} is not ready yet")
            }
            @JavascriptInterface fun setOpacity(value: Float) = handler.post {
                state.editSelected { it.withTransform("opacity", value.coerceIn(0f, 1f)) }
            }
            @JavascriptInterface fun setTransform(rotation: Float, scale: Float, opacity: Float) = handler.post {
                state.editSelected {
                    it.withTransform("rotation", -rotation).withTransform("scale", scale.coerceAtLeast(0.01f), scale.coerceAtLeast(0.01f))
                        .withTransform("opacity", opacity.coerceIn(0f, 1f))
                }
            }
            @JavascriptInterface fun log(text: String) = handler.post { if (text.isNotBlank()) onNotice(text.take(240)) }
        }
    }
    DisposableEffect(plugin.id) {
        web.addJavascriptInterface(bridge, "Schnitt")
        val script = runCatching { Jsx.decode(File(plugin.folder, plugin.entry.orEmpty())) }.getOrNull()
        val base = File(plugin.folder).toURI().toString()
        val shim = cepShim(plugin)
        if (plugin.kind == PluginKind.Script) {
            if (script == null) {
                web.loadDataWithBaseURL(base, "<html><body style='background:#1b1b1b;color:#eee;font:14px sans-serif;padding:24px'>" +
                    "This script is locked and cannot be decoded on this device.</body></html>", "text/html", "utf-8", null)
            } else {
                val file = File(context.cacheDir, "plugin-${plugin.id}.js")
                file.writeText(script)
                val html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<style>html,body{margin:0;background:#1b1b1b;color:#eee;font:14px sans-serif}pre{white-space:pre-wrap;padding:14px}</style>" +
                    "<script>$shim</script></head><body><pre id='out'>Running ${escape(plugin.name)}…</pre>" +
                    "<script src='${file.toURI()}'></script><script>try{if(typeof main==='function')main();document.getElementById('out').textContent=" +
                    "'Script finished.';}catch(e){document.getElementById('out').textContent='Script error: '+e;Schnitt.log(String(e));}</script></body></html>"
                web.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
            }
        } else {
            val entry = File(plugin.folder, plugin.entry.orEmpty())
            val raw = runCatching { entry.readText() }.getOrNull()
            val html = if (raw == null) "<html><body style='background:#1b1b1b;color:#eee;padding:24px'>Panel is not readable.</body></html>"
            else injectShim(raw, shim)
            web.loadDataWithBaseURL(entry.parentFile?.let { it.toURI().toString() } ?: base, html, "text/html", "utf-8", null)
        }
        onDispose {
            runCatching { web.loadUrl("about:blank") }
            runCatching { web.destroy() }
        }
    }
    AndroidView(factory = { web }, modifier = modifier.background(Color(0xFF1B1B1B)))
}

private fun injectShim(html: String, shim: String): String = when {
    html.contains("</head>", ignoreCase = true) -> html.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "<script>$shim</script></head>")
    html.contains("<body", ignoreCase = true) -> html.replaceFirst(Regex("<body", RegexOption.IGNORE_CASE), "<script>$shim</script><body")
    else -> "<script>$shim</script>" + html
}

private fun cepShim(plugin: Plugin): String = """
window.__adobe_cep__ = {
  getSystemPath: function(p){ return p === 'extension' ? ${js(plugin.folder)} : ''; },
  getHostEnvironment: function(){ return JSON.stringify({appName:'Schnitt',appVersion:'1.0',appLocale:'en_US',appSkin:'dark'}); },
  evalScript: function(s){
    try {
      var text = String(s);
      var m = text.match(/(?:addProperty|applyEffect|addEffect)\s*\(\s*["']([^"']+)["']/i);
      if (m) { Schnitt.applyEffectByName(m[1]); return 'ok'; }
      var n = text.match(/(?:effect|preset|filter)\s*(?:name)?\s*[:=]\s*["']([^"']+)["']/i);
      if (n) { Schnitt.applyEffectByName(n[1]); return 'ok'; }
      Schnitt.evalScriptCode(String(s));
      return 'queued';
    } catch(e) { Schnitt.log('EvalScript: ' + e); return 'error'; }
  },
  addEventListener: function(){}, removeEventListener: function(){},
  getExtensions: function(ids){ return '[]'; }, requestOpenExtension: function(){},
  closeExtension: function(){}, getActiveSequence: function(){ return null; },
  getCurrentApiVersion: function(){ return {major:11,minor:0}; }
};
window.cep = window.__adobe_cep__;
window.CSInterface = window.__adobe_cep__;
window.app = window.app || {
  activeDocument: { width: 1080, height: 1920, name: 'Schnitt project', layers: [], selectedLayers: [] },
  activeView: { zoom: 1 },
  project: { activeItem: { name: 'Schnitt project', selectedLayers: [], effects: { addProperty: function(n){ Schnitt.applyEffectByName(String(n)); return {}; } } } },
  alert: function(t){ Schnitt.log(String(t)); },
  beep: function(){},
  version: '1.0'
};
window.app.project.activeItem.selectedLayers = [{ effects: { addProperty: function(n){ Schnitt.applyEffectByName(String(n)); return {}; } } }];
window.Schnitt = window.Schnitt || {};
window.__schnittApply = function(id){ Schnitt.applyEffect(String(id)); };
""".trimIndent()

private val propertyCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

private fun discoverProperties(plugin: Plugin): List<String> {
    propertyCache[plugin.id]?.let { return it }
    val folder = File(plugin.folder)
    val text = folder.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in setOf("html", "htm", "js", "jsx", "json", "xml", "css") }
        .take(10)
        .joinToString("\n") { runCatching { it.readText().take(180_000) }.getOrDefault("") }
    if (text.isBlank()) return emptyList()
    val names = Regex("(?:name|id|data-param|aria-label)=\"([^\"]{2,42})\"").findAll(text).map { it.groupValues[1].trim() }
    val labels = Regex("<label[^>]*>([^<]{2,42})</label>", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1].trim() }
    return (labels + names).filter { it.isNotBlank() && it.none { c -> c == '<' || c == '>' } }.distinct().take(12).toList().also { propertyCache[plugin.id] = it }
}

private fun js(text: String): String = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
