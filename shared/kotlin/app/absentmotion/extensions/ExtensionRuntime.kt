package app.absentmotion.extensions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.platform.Context
import app.absentmotion.platform.Log
import app.absentmotion.ui.theme.Palette
import org.json.JSONObject

data class AutoScrollConfig(
    val mode: String,
    val snap: Boolean,
    val speed: Float,
    val smoothness: Int,
    val start: String = "bottom",
    val skipTop: Int = 1,
    val includeHidden: Boolean = false,
)

/**
 * One process-wide, read-only view of installed extensions. Archives are parsed
 * once per revision, never per frame. Preview and export do not touch this
 * cache unless an extension actually contributes an effect or theme.
 */
object ExtensionRuntime {
    private var appContext: Context? = null
    private var store: ExtensionStore? = null

    var revision by mutableIntStateOf(0)
        private set

    private data class Snapshot(
        val installed: List<InstalledExtension>,
        val values: Map<Pair<String, String>, String>,
        val effects: List<EffectDefinition>,
        val icons: Map<String, ExtensionIconSpec>,
        val themeCss: String,
        val columns: Int,
    )

    @Volatile private var snapshot: Snapshot? = null

    fun attach(context: Context) {
        val app = context.applicationContext ?: context
        if (appContext === app) return
        appContext = app
        val created = ExtensionStore(app)
        store = created
        runCatching { created.ensureBundled() }.onFailure { Log.w("Extensions", "bundled install failed", it) }
        snapshot = null
        revision++
    }

    fun refresh() {
        snapshot = null
        revision++
    }

    fun installed(): List<InstalledExtension> = loaded()?.installed.orEmpty()

    fun find(id: String): InstalledExtension? = loaded()?.installed?.firstOrNull { it.id == id }

    fun value(extension: InstalledExtension, property: ExtensionProperty): String =
        loaded()?.values?.get(extension.id to property.id) ?: property.default

    fun property(extension: InstalledExtension, id: String): String? {
        val prop = extension.property(id) ?: return null
        return value(extension, prop)
    }

    fun autoScroll(): AutoScrollConfig? {
        val state = loaded() ?: return null
        val extension = state.installed.firstOrNull {
            it.enabled && "timeline.follow" in it.manifest.capabilities
        } ?: return null
        val values = state.values
        val enabled = extension.property("enabled")?.let { prop -> prop.flag(values[extension.id to prop.id]) } ?: true
        if (!enabled) return null
        val mode = extension.property("mode")?.let { prop -> prop.choice(values[extension.id to prop.id]) } ?: "playing"
        val snap = extension.property("snap")?.let { prop -> prop.flag(values[extension.id to prop.id]) } ?: true
        val speed = extension.property("speed")?.let { prop -> prop.number(values[extension.id to prop.id]) } ?: 1f
        val smoothness = extension.property("smoothness")?.let { prop -> prop.number(values[extension.id to prop.id]).toInt() } ?: 260
        val start = extension.property("start")?.let { prop -> prop.choice(values[extension.id to prop.id]) } ?: "bottom"
        val skipTop = extension.property("skipTop")?.let { prop -> prop.number(values[extension.id to prop.id]) }?.toInt() ?: 1
        val includeHidden = extension.property("includeHidden")?.let { prop -> prop.flag(values[extension.id to prop.id]) } ?: false
        return AutoScrollConfig(mode, snap, speed.coerceIn(0.1f, 4f), smoothness.coerceIn(60, 2000), start, skipTop.coerceIn(0, 100), includeHidden)
    }

    /** Safe playback automation currently supported: loop at the scene end. */
    fun automationLoops(): Boolean {
        val state = loaded() ?: return false
        return state.installed.any { extension ->
            extension.enabled && "automation.safe" in extension.manifest.capabilities &&
                extension.property("loopPlayback")?.let { prop -> prop.flag(state.values[extension.id to prop.id]) } == true
        }
    }

    fun theme(base: Palette): Palette {
        val css = loaded()?.themeCss.orEmpty()
        return if (css.isBlank()) base else ExtensionCss.apply(base, css)
    }

    fun effects(): List<EffectDefinition> = loaded()?.effects.orEmpty()

    fun iconSpec(name: String): ExtensionIconSpec? = loaded()?.icons?.get(name)

    fun layoutColumns(default: Int): Int = loaded()?.columns?.takeIf { it in 1..3 } ?: default

    private fun loaded(): Snapshot? {
        snapshot?.let { return it }
        val context = appContext ?: return null
        val extensionStore = store ?: ExtensionStore(context).also { store = it }
        val installed = runCatching { extensionStore.list() }.getOrDefault(emptyList())
        val enabled = installed.filter { it.enabled }
        val values = HashMap<Pair<String, String>, String>()
        for (ext in installed) for (prop in ext.manifest.properties) values[ext.id to prop.id] = extensionStore.value(ext, prop)
        val effects = ArrayList<EffectDefinition>()
        val icons = HashMap<String, ExtensionIconSpec>()
        val theme = StringBuilder()
        var columns = 0
        for (ext in enabled) {
            if (ext.category == ExtensionCategory.Effects || "effects.custom" in ext.manifest.capabilities) {
                FileList.files(ext.directory, "effects", "json").forEach { file ->
                    runCatching {
                        val definition = EffectDefinition.parse(JSONObject(file.readText().removePrefix("\uFEFF")))
                        val key = "ext-${ext.id}-${definition.key}"
                        definition.copy(
                            id = "ext.${ext.id}.${definition.id}", key = key, page = key, category = "custom",
                            alsoIn = (definition.alsoIn + "external" + "extensions").distinct(),
                            since = definition.since ?: ext.manifest.version,
                        )
                    }.onSuccess { effects += it }.onFailure { Log.w("Extensions", "effect in ${ext.id} skipped", it) }
                }
            }
            if (ext.category == ExtensionCategory.Icons || "icons.home" in ext.manifest.capabilities) {
                val file = ext.directory.resolve("icons/icons.json")
                if (file.isFile) runCatching { parseIcons(file.readText()) }.onSuccess { icons.putAll(it) }
            }
            if (ext.category == ExtensionCategory.Theme || "theme.css" in ext.manifest.capabilities) {
                val file = ext.directory.resolve("theme.css")
                if (file.isFile) runCatching { theme.append(file.readText().take(64 * 1024)) }
            }
            if (ext.category == ExtensionCategory.Layout || "layout.tools" in ext.manifest.capabilities) {
                val fromFile = FileList.files(ext.directory, "layout", "json").firstNotNullOfOrNull { file ->
                    runCatching { JSONObject(file.readText().removePrefix("﻿")).optInt("toolsColumns", 0) }.getOrNull()?.takeIf { it in 1..3 }
                }
                if (fromFile != null) columns = fromFile
                else {
                    val prop = ext.manifest.properties.firstOrNull { it.id == "toolsColumns" || it.id == "columns" } ?: continue
                    columns = prop.number(values[ext.id to prop.id]).toInt()
                }
            }
        }
        return Snapshot(
            installed, values, effects.distinctBy { it.id }, icons, theme.toString(), columns,
        ).also { snapshot = it }
    }

    private fun parseIcons(text: String): Map<String, ExtensionIconSpec> {
        val json = JSONObject(text.removePrefix("\uFEFF"))
        val out = LinkedHashMap<String, ExtensionIconSpec>()
        for (name in json.keys().asSequence().take(32)) {
            if (!name.matches(Regex("(home|tools)\\.[a-z0-9.-]{1,40}"))) continue
            val value = json.opt(name)
            val path = ((value as? String) ?: (value as? JSONObject)?.optString("path") ?: "").trim()
            if (path.length !in 4..16_384 || path.contains('<') || path.contains("url(", true)) continue
            val obj = value as? JSONObject
            out[name] = ExtensionIconSpec(
                path,
                stroke = obj?.optBoolean("stroke", true) ?: true,
                width = (obj?.optDouble("width", 1.8)?.toFloat() ?: 1.8f).coerceIn(0.5f, 6f),
                mirror = obj?.optBoolean("mirror", false) ?: false,
            )
        }
        return out
    }

    private object FileList {
        fun files(dir: java.io.File, child: String, extension: String): List<java.io.File> =
            dir.resolve(child).listFiles { f -> f.isFile && f.extension == extension }.orEmpty().sortedBy { it.name }.take(24)
    }
}
