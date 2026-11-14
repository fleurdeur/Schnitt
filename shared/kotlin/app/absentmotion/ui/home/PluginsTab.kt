package app.absentmotion.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.plugins.Plugin
import app.absentmotion.plugins.PluginKind
import app.absentmotion.plugins.PluginStore
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

@Composable
internal fun PluginsTab(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PluginStore(context) }
    var plugins by remember { mutableStateOf(store.list()) }
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Plugin?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = store.import(uri, uri.lastPathSegment)
            importing = false
            plugins = store.list()
            notice = if (result.plugin == null) context.getString(R.string.plugins_import_failed) else null
            if (result.plugin != null) open = result.plugin
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.plugins_title), color = Am.Navy, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("ZXP · AEX · JSX · JSXBIN", Modifier.padding(top = 2.dp), color = Am.HomeDim, fontSize = 12.sp)
            }
            Row(
                Modifier.height(42.dp).clip(RoundedCornerShape(21.dp)).background(Am.Navy).clickable(enabled = !importing) { picker.launch(arrayOf("*/*")) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HomeIcons.Upload, null, Modifier.size(17.dp), tint = Am.OnNavy)
                Spacer(Modifier.width(7.dp))
                Text(if (importing) stringResource(R.string.plugins_importing) else stringResource(R.string.plugins_import), color = Am.OnNavy, fontSize = 13.sp, maxLines = 1)
            }
        }
        notice?.let { Text(it, Modifier.padding(top = 10.dp), color = Am.HomeAccent, fontSize = 13.sp) }
        if (plugins.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(HomeIcons.Plugins, null, Modifier.size(44.dp), tint = Am.HomeMid)
                    Text(stringResource(R.string.plugins_empty), Modifier.padding(top = 14.dp), color = Am.HomeMid, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(plugins, key = { it.id }) { plugin ->
                val chosen = open?.id == plugin.id
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card).clickable { open = if (chosen) null else plugin }.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Am.HomeBg), Alignment.Center) {
                            Icon(HomeIcons.Plugins, null, Modifier.size(22.dp), tint = if (plugin.enabled) Am.HomeAccent else Am.HomeDim)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(plugin.name, color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(kindName(plugin.kind) + " · " + plugin.version, Modifier.padding(top = 1.dp), color = Am.HomeDim, fontSize = 12.sp, maxLines = 1)
                        }
                        Text(
                            if (plugin.enabled) stringResource(R.string.plugins_enabled) else stringResource(R.string.plugins_disabled),
                            color = if (plugin.enabled) Am.HomeAccent else Am.HomeDim, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    if (chosen) {
                        plugin.summary.takeIf { it.isNotBlank() }?.let {
                            Text(it, Modifier.padding(top = 10.dp), color = Am.HomeInk, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                        if (plugin.kind == PluginKind.Native) Text(
                            "Converted into an Android effect with its own generated shader.", Modifier.padding(top = 6.dp), color = Am.HomeDim, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                        Text(
                            "Own generated shader · editable properties", Modifier.padding(top = 6.dp), color = Am.HomeAccent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.plugins_remove), color = Am.HomeAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { store.remove(plugin.id); plugins = store.list(); open = null })
                            Text(if (plugin.enabled) stringResource(R.string.plugins_disabled) else stringResource(R.string.plugins_enabled), color = Am.Navy, fontSize = 13.sp,
                                modifier = Modifier.clickable { store.setEnabled(plugin.id, !plugin.enabled); plugins = store.list() })
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

private fun kindName(kind: PluginKind): String = when (kind) {
    PluginKind.Cep -> "CEP panel"
    PluginKind.Script -> "Script panel"
    PluginKind.Native -> "Native plugin"
    PluginKind.Effect -> "Effect plugin"
    PluginKind.Shader -> "Shader plugin"
}
