package app.absentmotion.ui.home

import android.app.Activity
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.extensions.ExtensionCategory
import app.absentmotion.extensions.ExtensionProperty
import app.absentmotion.extensions.ExtensionPropertyKind
import app.absentmotion.extensions.ExtensionRuntime
import app.absentmotion.extensions.ExtensionStore
import app.absentmotion.extensions.InstalledExtension
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Toast
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.animatedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ExtensionScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { ExtensionStore(context) }
    val docs = LocalUriHandler.current
    var extensions by remember { mutableStateOf(store.list()) }
    var category by remember { mutableStateOf<ExtensionCategory?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.import(uri, displayName(context, uri)) }
            importing = false
            extensions = store.list(true)
            ExtensionRuntime.refresh()
            if (result.extension != null) {
                open = result.extension.id
                notice = null
                changed = true
            } else notice = result.error ?: "This extension could not be installed"
        }
    }
    val filtered = extensions.filter { category == null || it.category == category }
    ToolSheet(
        "Extensions", onClose,
        bottom = {
            if (importing) Text("Reading extension…", Modifier.padding(horizontal = 16.dp), color = Am.HomeDim, fontSize = 13.sp)
            if (changed) {
                Spacer(Modifier.height(8.dp))
                BigButton("Restart Schnitt to apply", Modifier.fillMaxWidth().padding(horizontal = 16.dp), HomeIcons.Download) {
                    restart(context)
                }
            }
            Spacer(Modifier.height(12.dp))
        },
    ) {
        Text(
            "Small .sc bundles for themes, icons, effects, layout, automations and safe timeline behavior. " +
                "Extensions are data only: no code, no network, no access to Schnitt's internal files.",
            Modifier.padding(top = 6.dp), color = Am.HomeDim, fontSize = 14.sp, lineHeight = 20.sp,
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(if (importing) "Installing…" else "Import .sc", Modifier.weight(1f), HomeIcons.Upload, enabled = !importing) {
                picker.launch(arrayOf("application/zip", "application/octet-stream", "text/plain", "*/*"))
            }
            BigButton("Guide", Modifier.width(110.dp), HomeIcons.Notes, primary = false) {
                docs.openUri("https://fleurdeur.github.io/Schnitt/extensions.html")
            }
        }
        notice?.let { Text(it, Modifier.padding(top = 12.dp), color = Am.Warning, fontSize = 13.sp, lineHeight = 18.sp) }
        if (changed) Text(
            "Restart after installing or changing an extension. Schnitt keeps the bundle and your property values.",
            Modifier.padding(top = 10.dp), color = Am.HomeAccent, fontSize = 13.sp, lineHeight = 18.sp,
        )

        SectionTitle("Installed")
        Text(
            "${extensions.size} ${if (extensions.size == 1) "bundle" else "bundles"} · ${app.absentmotion.export.formatBytes(store.storageBytes())} stored",
            color = Am.HomeDim, fontSize = 12.sp,
        )
        Chips(
            listOf("All") + ExtensionCategory.entries.map { it.label },
            category?.let { ExtensionCategory.entries.indexOf(it) + 1 } ?: 0,
            true,
        ) { index -> category = if (index == 0) null else ExtensionCategory.entries[index - 1] }

        if (filtered.isEmpty()) {
            Text(
                if (extensions.isEmpty()) "No extensions yet. Import a .sc file, or open the guide to build one." else "Nothing in this category.",
                Modifier.padding(top = 18.dp, bottom = 18.dp), color = Am.HomeMid, fontSize = 15.sp, lineHeight = 21.sp,
            )
        } else {
            Spacer(Modifier.height(12.dp))
            for (extension in filtered) ExtensionCard(
                extension, store, open == extension.id,
                onOpen = { open = if (open == extension.id) null else extension.id },
                onChanged = { changed = true; extensions = store.list(true); ExtensionRuntime.refresh() },
            )
        }

        Spacer(Modifier.height(22.dp))
        Text("Safe by design", color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "The reader enforces a manifest, a supported-file allowlist, size limits and path checks. " +
                "Themes can only set palette colours. Icons only receive vector path data. Effects are shader JSON. " +
                "Behaviors read declared switches and numbers; they never run JavaScript, DEX, native code or shell commands.",
            Modifier.padding(top = 6.dp, bottom = 30.dp), color = Am.HomeDim, fontSize = 13.sp, lineHeight = 19.sp,
        )
    }
}

@Composable
private fun ExtensionCard(
    extension: InstalledExtension,
    store: ExtensionStore,
    open: Boolean,
    onOpen: () -> Unit,
    onChanged: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(Am.Card).clickable(onClick = onOpen).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                HomeIcons.custom(
                    when (extension.category) {
                        ExtensionCategory.Behavior, ExtensionCategory.Automation -> "home.tools"
                        ExtensionCategory.Theme -> "home.templates"
                        ExtensionCategory.Icons -> "home.projects"
                        ExtensionCategory.Effects -> "home.plugins"
                        ExtensionCategory.Layout -> "home.tools"
                    },
                    HomeIcons.Extensions,
                ),
                null, Modifier.size(24.dp), tint = if (extension.enabled) Am.HomeAccent else Am.HomeDim,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(extension.manifest.name, color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${extension.category.label} · ${extension.manifest.version} · ${extension.manifest.author.ifBlank { "unknown" }}",
                    Modifier.padding(top = 2.dp), color = Am.HomeDim, fontSize = 12.sp, maxLines = 1,
                )
            }
            Switch(
                checked = extension.enabled,
                onCheckedChange = { on -> store.setEnabled(extension.id, on); onChanged() },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Am.OnAccent, checkedTrackColor = Am.Accent,
                    uncheckedThumbColor = Am.Card, uncheckedTrackColor = Am.HomeLine,
                ),
            )
        }
        if (open) {
            extension.manifest.description.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.padding(top = 12.dp), color = Am.HomeInk, fontSize = 13.sp, lineHeight = 18.sp)
            }
            for (property in extension.manifest.properties) {
                ExtensionPropertyRow(extension, property, store, onChanged)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    "Remove", color = Am.HomeAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { store.remove(extension.id); onChanged() },
                )
                if (extension.bundled) Text("Bundled example", color = Am.HomeDim, fontSize = 12.sp)
                Text(
                    extension.manifest.capabilities.joinToString(" · ").ifBlank { "declarative extension" },
                    color = Am.HomeFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ExtensionPropertyRow(extension: InstalledExtension, property: ExtensionProperty, store: ExtensionStore, onChanged: () -> Unit) {
    val current = store.value(extension, property)
    var local by remember(extension.id, property.id, current) { mutableStateOf(current) }
    fun commit(value: String) {
        local = value
        store.setProperty(extension.id, property.id, value)
        ExtensionRuntime.refresh()
        onChanged()
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(property.label, Modifier.weight(1f), color = Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            when (property.kind) {
                ExtensionPropertyKind.Toggle -> Switch(
                    checked = property.flag(local), onCheckedChange = { commit(it.toString()) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Am.OnAccent, checkedTrackColor = Am.Accent,
                        uncheckedThumbColor = Am.Card, uncheckedTrackColor = Am.HomeLine,
                    ),
                )
                ExtensionPropertyKind.Slider -> Text("${property.number(local)}", color = Am.HomeAccent, fontSize = 13.sp)
                ExtensionPropertyKind.Choice -> Text(property.options.getOrElse(property.choiceIndex(local)) { "" }, color = Am.HomeAccent, fontSize = 13.sp)
                ExtensionPropertyKind.Text, ExtensionPropertyKind.Color -> Unit
            }
        }
        when (property.kind) {
            ExtensionPropertyKind.Slider -> {
                val steps = ((property.max - property.min) / property.step).toInt().let { if (it > 0 && it < 1000) it - 1 else 0 }
                Slider(
                    value = property.number(local),
                    onValueChange = { local = it.toString() },
                    onValueChangeFinished = { commit(local) },
                    valueRange = property.min..property.max,
                    steps = steps.coerceAtLeast(0),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Am.Accent, activeTrackColor = Am.Accent, inactiveTrackColor = Am.HomeLine,
                    ),
                )
            }
            ExtensionPropertyKind.Choice -> Chips(
                property.options, property.choiceIndex(local), true,
            ) { index -> commit(property.optionValues.getOrElse(index) { property.default }) }
            ExtensionPropertyKind.Text, ExtensionPropertyKind.Color -> OutlinedTextField(
                value = local,
                onValueChange = { if (it.length <= 512) commit(it) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(if (property.kind == ExtensionPropertyKind.Color) "Colour (#RRGGBB)" else "Value", color = Am.HomeDim) },
            )
            ExtensionPropertyKind.Toggle -> Unit
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

private fun restart(context: android.content.Context) {
    var wrapper: android.content.Context? = context
    while (wrapper is ContextWrapper) {
        if (wrapper is Activity) {
            wrapper.recreate()
            return
        }
        wrapper = wrapper.baseContext
    }
    Toast.makeText(context, "Open Schnitt again to apply extensions", Toast.LENGTH_LONG).show()
}
