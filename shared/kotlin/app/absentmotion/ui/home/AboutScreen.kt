package app.absentmotion.ui.home

import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.window.Dialog
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.appVersion
import app.absentmotion.platform.DialogStatusBarIcons
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.editor.PhoneScreen
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.Tier

internal const val HOME_PAGE = "https://github.com/Comett1225/AbsentMotion"
internal const val DEVELOPER_PAGE = "https://github.com/fleurdeur"
internal const val WEBSITE = "https://fleurdeur.github.io/Schnitt/"
internal const val COMMUNITY_PAGE = "https://fleurdeur.github.io/Schnitt/community/"

internal const val LICENCES_ASSET = "licenses.txt"

@Composable
fun AboutScreen(settings: app.absentmotion.library.AppSettings, onClose: () -> Unit) {
    var licences by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf(false) }
    Dialog({ if (licences) licences = false else onClose() }, sheetDialogProperties()) {
        PhoneScreen {
            DialogStatusBarIcons(darkIcons = Am.Card.luminance() > 0.5f)
            Box(Modifier.fillMaxSize().background(Am.SheetBg)) {
                if (licences) LicencesPage { licences = false }
                else AboutPage(onClose, onLicences = { licences = true }, onWhatsNew = { whatsNew = true })
            }
        }
    }
    if (whatsNew) CurrentWhatsNew(settings) { whatsNew = false }
}

@Composable
private fun AboutPage(onClose: () -> Unit, onLicences: () -> Unit, onWhatsNew: () -> Unit) {
    val version = appVersion()
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val notes = remember { WhatsNewNotes.currentName(context) }
    val avatar by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("about/fleurdeur.jpg").use { android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    fun open(url: String) = runCatching { links.openUri(url) }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TitleBar(stringResource(R.string.settings_about), HomeIcons.Close, stringResource(R.string.action_close), onClose)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Am.Card, Am.Well)))) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Am.Accent), Alignment.Center) {
                            Text("S", color = Am.OnAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.padding(start = 14.dp)) {
                            Text("Schnitt", color = Am.Navy, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text("Motion, made on Android.", Modifier.padding(top = 2.dp), color = Am.HomeMid, fontSize = 14.sp)
                        }
                    }
                    Text(
                        "A complete mobile motion studio for keyframes, effects, 3D, AI tools, Alight Motion XML and community presets. Built to stay fast on a phone and honest about what each effect can do.",
                        Modifier.padding(top = 16.dp), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 21.sp,
                    )
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AboutPill(if (version.isEmpty()) "Schnitt" else "v$version")
                        AboutPill(if (Tier.compatible) "Compatible build" else "Latest build")
                        AboutPill("GPLv3")
                    }
                    if (notes != null) Box(
                        Modifier.padding(top = 16.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(Am.Navy)
                            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onWhatsNew).padding(horizontal = 20.dp, vertical = 10.dp),
                        Alignment.Center,
                    ) {
                        Text(stringResource(R.string.whats_new_see, WhatsNewNotes.key(version) ?: notes), color = Am.OnNavy, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            AboutHeading("Developer")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Am.Card).clickable { open(DEVELOPER_PAGE) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(62.dp).clip(CircleShape).background(Am.Well), Alignment.Center) {
                    avatar?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("wall", color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("@fleurdeur", color = Am.HomeAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Designer and developer of Schnitt.", Modifier.padding(top = 3.dp), color = Am.HomeMid, fontSize = 12.sp)
                }
                Icon(HomeIcons.ChevronRight, null, Modifier.size(24.dp), tint = Am.HomeMid)
            }
            Spacer(Modifier.height(18.dp))
            AboutHeading("What Schnitt does")
            val features = listOf(
                "Professional timeline" to "Keyframes, easing, speed curves, bookmarks, beat marks, trimming, grouping and multi-select on a phone or tablet.",
                "Effects and plug-ins" to "Alight Motion effects, AEX and CEP plug-ins, generated Android shaders, presets and a shape library.",
                "Import and export" to "Alight Motion XML, .amproj packages, Adobe Animate files, project preview before the timeline and bundle export.",
                "AI on the device" to "Captions, background removal, noise reduction, smoothing and enhance run offline. No account and no upload.",
                "Community workflow" to "Templates, assets and presets can be shared, reviewed and published from Schnitt's community site.",
            )
            features.forEach { (title, body) -> AboutFeature(title, body) }
            Spacer(Modifier.height(18.dp))
            AboutHeading("At a glance")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AboutStat("Offline", "AI tools")
                AboutStat("Android", "13 and newer")
                AboutStat("No ads", "no tracking")
            }
            Spacer(Modifier.height(18.dp))
            AboutHeading("Project")
            AboutLink("Source code", "github.com/fleurdeur/Schnitt") { open(DEVELOPER_PAGE + "/Schnitt") }
            AboutLink("Report an issue", "Bugs and feature requests") { open(DEVELOPER_PAGE + "/Schnitt/issues") }
            AboutLink("Community presets", "Upload or browse approved presets and XML") { open(COMMUNITY_PAGE) }
            AboutLink(stringResource(R.string.open_source_licences), "Libraries and their licenses", onLicences)
            Spacer(Modifier.height(20.dp))
            Text("Made with care by wall (@fleurdeur). Thank you for using Schnitt.", Modifier.padding(bottom = 28.dp), color = Am.HomeMid, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun AboutHeading(text: String) {
    Text(text.uppercase(), Modifier.padding(bottom = 8.dp), color = Am.HomeMid, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun AboutPill(text: String) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Am.Navy.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, color = Am.Navy, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun AboutFeature(title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(Am.Card).padding(12.dp)) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(Am.Accent))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(body, Modifier.padding(top = 2.dp), color = Am.HomeMid, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun RowScope.AboutStat(value: String, label: String) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Am.Card).padding(horizontal = 10.dp, vertical = 12.dp)) {
        Text(value, color = Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, Modifier.padding(top = 2.dp), color = Am.HomeMid, fontSize = 10.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun AboutLink(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(Am.Card).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Am.HomeInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, Modifier.padding(top = 2.dp), color = Am.HomeMid, fontSize = 11.sp, maxLines = 1)
        }
        Icon(HomeIcons.ChevronRight, null, Modifier.size(22.dp), tint = Am.HomeMid)
    }
}

private val RowShape = RoundedCornerShape(7.dp)

@Composable
private fun Body(text: String) = Text(text, color = Am.HomeInk, fontSize = 14.sp, lineHeight = 20.sp)

@Composable
internal fun TitleBar(
    title: String, icon: ImageVector, description: String, onClick: () -> Unit,
    action: ImageVector? = null, actionDescription: String = "", onAction: () -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().background(Am.Card).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.captionBar)).height(49.dp)) {
        Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(24.dp), tint = Am.Navy)
        }
        Text(title, Modifier.align(Alignment.Center), color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (action != null) Box(Modifier.align(Alignment.TopEnd).size(44.dp).clickable(onClick = onAction), contentAlignment = Alignment.Center) {
            Icon(action, actionDescription, Modifier.size(24.dp), tint = Am.Navy)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.7.dp).background(Am.HomeLine))
    }
}

@Composable
private fun LicencesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val parts by produceState<List<LicencePart>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { licenceParts(context.assets.open(LICENCES_ASSET).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())
        }
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TitleBar(stringResource(R.string.open_source_licences), ExportIcons.Back, stringResource(R.string.home_back), onBack)
        val shown = parts
        if (shown == null || shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(if (shown == null) R.string.about_loading else R.string.about_licence_unreadable), color = Am.HomeMid, fontSize = 14.sp)
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            items(shown) { part ->
                val gap = if (part.last) 12.dp else 0.dp
                when (part.heading) {
                    0 -> Text(part.text, Modifier.padding(bottom = gap), color = Am.HomeInk, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
                    else -> Text(
                        part.text, Modifier.padding(top = if (part.heading == 2) 16.dp else 6.dp, bottom = gap), color = Am.Navy,
                        fontSize = if (part.heading == 2) 17.sp else 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

internal class LicencePart(val text: String, val heading: Int, val last: Boolean)

private const val CHUNK = 24

internal fun licenceParts(text: String): List<LicencePart> {
    val parts = ArrayList<LicencePart>()
    val block = ArrayList<String>()
    fun end() {
        if (block.isEmpty()) return
        val underline = block.getOrNull(1)?.trim().orEmpty()
        if (block.size == 2 && underline.length >= 3 && underline.all { it == '-' || it == '=' }) {
            val title = block[0].trim()
            parts += LicencePart(title, if (underline[0] == '=' && title == title.uppercase()) 2 else 1, true)
        } else {
            val margin = block.minOf { indent(it) }
            val lines = reflow(if (margin > 8) block.map { it.drop(margin - 4) } else block)
            lines.chunked(CHUNK).forEachIndexed { i, chunk -> parts += LicencePart(chunk.joinToString("\n"), 0, (i + 1) * CHUNK >= lines.size) }
        }
        block.clear()
    }
    for (line in text.lines()) if (line.isBlank()) end() else block += line.trimEnd().replace("\t", "    ")
    end()
    return parts
}

private val Marker = Regex("""^\s*([-*•]|o|\d+[.)]|\(\w{1,3}\)|\w[.)])\s+\S""")

private fun indent(line: String) = line.length - line.trimStart().length

internal fun reflow(lines: List<String>): List<String> {
    val width = lines.maxOf { it.length }
    val reaches = { line: String -> line.length >= width - 12 }
    if (lines.size < 2 || width !in 50..100 || lines.dropLast(1).count(reaches) * 5 < (lines.size - 1) * 3) return lines
    val out = ArrayList<String>()
    var before = ""
    for (line in lines) {
        val joins = out.isNotEmpty() && reaches(before) && !Marker.containsMatchIn(line) &&
            (indent(line) == indent(before) || (indent(line) > indent(before) && Marker.containsMatchIn(before)))
        if (joins) out[out.lastIndex] = out.last() + " " + line.trim() else out += line
        before = line
    }
    return out
}
