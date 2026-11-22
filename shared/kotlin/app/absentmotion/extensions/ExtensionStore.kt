package app.absentmotion.extensions

import app.absentmotion.platform.Context
import app.absentmotion.platform.Log
import app.absentmotion.platform.Uri
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Installs and stores `.sc` bundles. A bundle is a small zip with a JSON
 * manifest and optional theme, icon or effect resources. Nothing is executed:
 * the reader only accepts the documented files and parses them as data.
 */
class ExtensionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("extensions", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "extensions")
    @Volatile private var cache: List<InstalledExtension>? = null

    fun list(refresh: Boolean = false): List<InstalledExtension> {
        cache?.takeUnless { refresh }?.let { return it }
        root.mkdirs()
        cleanupTemps()
        val found = root.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }.orEmpty().mapNotNull { dir ->
            val manifest = readManifest(dir) ?: return@mapNotNull null
            val enabled = prefs.getBoolean("on.${manifest.id}", false)
            InstalledExtension(manifest, dir, enabled, prefs.getBoolean("bundled.${manifest.id}", false))
        }.sortedWith(compareBy({ it.category.ordinal }, { it.manifest.name.lowercase() }))
        cache = found
        return found
    }

    fun find(id: String): InstalledExtension? = list().firstOrNull { it.id == id }

    fun value(extension: InstalledExtension, property: ExtensionProperty): String =
        property.value(prefs.getString("v.${extension.id}.${property.id}", null))

    fun setProperty(id: String, property: String, value: String): Boolean {
        val ext = find(id) ?: return false
        ext.property(property) ?: return false
        prefs.edit().putString("v.$id.$property", value.take(512)).apply()
        return true
    }

    fun setEnabled(id: String, on: Boolean): Boolean {
        if (find(id) == null) return false
        prefs.edit().putBoolean("on.$id", on).apply()
        cache = null
        return true
    }

    fun remove(id: String): Boolean {
        val ext = find(id) ?: return false
        val edit = prefs.edit()
        for (key in prefs.all.keys.filter { it == "on.$id" || it.startsWith("v.$id.") }) edit.remove(key)
        edit.remove("bundled.$id").apply()
        val ok = runCatching { ext.directory.deleteRecursively() }.getOrDefault(false)
        cache = null
        return ok
    }

    fun import(uri: Uri, displayName: String?): ExtensionImport {
        val name = displayName.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
        if (!name.endsWith(".sc", ignoreCase = true)) return ExtensionImport(error = "Choose a .sc extension file")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return ExtensionImport(error = "The file could not be opened")
            input.use { install(it, name, bundled = false) }
        } catch (e: SecurityException) {
            ExtensionImport(error = "Schnitt cannot read this file")
        } catch (e: Throwable) {
            Log.w("Extensions", "import failed", e)
            ExtensionImport(error = e.message?.take(160) ?: "The extension is not valid")
        }
    }

    /** Installs or upgrades the bundled Auto Scroll, without touching a later user copy. */
    fun ensureBundled() {
        root.mkdirs()
        val dir = File(root, "auto-scroll")
        val current = if (dir.isDirectory) readManifest(dir) else null
        val markedBundled = prefs.getBoolean("bundled.auto-scroll", false)
        if (current != null && (!markedBundled || current.version >= BUNDLED_AUTO_SCROLL_VERSION)) {
            if (!markedBundled) return
            prefs.edit().putBoolean("bundled.auto-scroll", true).apply()
            return
        }
        // Early builds marked the shipped 1.0.0 copy as bundled but kept no
        // version, so upgrade that exact example. A later user copy is left
        // alone unless it was installed by this app as the bundled example.
        if (current != null && !markedBundled && current.version != "1.0.0") return
        val upgradedFrom = current?.version
        runCatching {
            context.assets.open("extensions/auto-scroll.sc").use { install(it, "auto-scroll.sc", bundled = true) }
        }.onSuccess {
            if (upgradedFrom == "1.1.0" && prefs.getString("v.auto-scroll.skipTop", "0") == "0") {
                prefs.edit()
                    .putString("v.auto-scroll.mode", "playing")
                    .putString("v.auto-scroll.skipTop", "1")
                    .apply()
            }
            prefs.edit().putBoolean("bundled.auto-scroll", true).apply()
        }.onFailure { Log.w("Extensions", "bundled extension was not installed", it) }
    }

    fun storageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun install(input: InputStream, displayName: String, bundled: Boolean): ExtensionImport {
        root.mkdirs()
        cleanupTemps()
        val temp = File(root, ".tmp-${UUID.randomUUID()}")
        var total = 0L
        var entries = 0
        val seen = HashSet<String>()
        try {
            temp.mkdirs()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > 64) error("Too many files in this extension")
                    if (entry.isDirectory) continue
                    val path = ExtensionPaths.safeEntry(entry.name) ?: error("Unsafe path in extension")
                    if (!ExtensionPaths.allowed(path)) error("Unsupported file in extension: $path")
                    if (!seen.add(path)) error("Duplicate file in extension: $path")
                    val target = File(temp, path)
                    val canonical = target.canonicalFile
                    if (!canonical.toPath().startsWith(temp.canonicalFile.toPath())) error("Unsafe path in extension")
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out ->
                        val buffer = ByteArray(16 * 1024)
                        var written = 0
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            written += read
                            total += read
                            if (written > MAX_FILE) error("A file in this extension is too large")
                            if (total > MAX_TOTAL) error("This extension is too large")
                            out.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifestFile = File(temp, "manifest.json")
            if (!manifestFile.isFile) error("manifest.json is missing")
            if (manifestFile.length() > MAX_MANIFEST) error("manifest.json is too large")
            val manifest = ExtensionManifest.parse(JSONObject(manifestFile.readText().removePrefix("\uFEFF")))
            validateFiles(temp, manifest)
            val oldEnabled = find(manifest.id)?.enabled ?: bundled
            val destination = File(root, manifest.id)
            if (destination.exists() && !destination.deleteRecursively()) error("The old extension could not be replaced")
            if (!temp.renameTo(destination)) {
                temp.copyRecursively(destination, overwrite = true)
                temp.deleteRecursively()
            }
            prefs.edit().putBoolean("on.${manifest.id}", oldEnabled).putBoolean("bundled.${manifest.id}", bundled).apply()
            cache = null
            return ExtensionImport(InstalledExtension(manifest, destination, oldEnabled, bundled))
        } catch (e: Throwable) {
            runCatching { temp.deleteRecursively() }
            if (e is SecurityException) throw e
            return ExtensionImport(error = e.message?.take(160) ?: "The extension is not valid")
        }
    }

    private fun validateFiles(dir: File, manifest: ExtensionManifest) {
        val caps = manifest.capabilities.toSet()
        if (manifest.category == ExtensionCategory.Theme || "theme.css" in caps) {
            val css = File(dir, "theme.css")
            if (!css.isFile || css.length() > MAX_THEME) error("theme.css is missing or too large")
        }
        if (manifest.category == ExtensionCategory.Icons || "icons.home" in caps) {
            val icons = File(dir, "icons/icons.json")
            if (!icons.isFile || icons.length() > MAX_ICONS) error("icons/icons.json is missing or too large")
        }
        if (manifest.category == ExtensionCategory.Effects || "effects.custom" in caps) {
            val effects = File(dir, "effects").listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
            if (effects.isEmpty() || effects.size > 24) error("An effects extension needs 1-24 effect files")
        }
        if (manifest.category == ExtensionCategory.Layout || "layout.tools" in caps) {
            val layout = File(dir, "layout").listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
            if (layout.isEmpty() || layout.size > 4) error("A layout extension needs a layout file")
        }
        if (manifest.category == ExtensionCategory.Behavior || manifest.category == ExtensionCategory.Automation) {
            if ("timeline.follow" !in caps && "automation.safe" !in caps) error("A behavior extension needs a safe capability")
        }
    }

    private fun readManifest(dir: File): ExtensionManifest? = runCatching {
        val file = File(dir, "manifest.json")
        if (!file.isFile || file.length() > MAX_MANIFEST) return@runCatching null
        ExtensionManifest.parse(JSONObject(file.readText().removePrefix("\uFEFF")))
    }.getOrNull()

    private fun cleanupTemps() {
        root.listFiles { file -> file.name.startsWith(".tmp-") }.orEmpty().forEach { dir ->
            if (System.currentTimeMillis() - dir.lastModified() > 60_000L) runCatching { dir.deleteRecursively() }
        }
    }

    companion object {
        private const val BUNDLED_AUTO_SCROLL_VERSION = "1.2.0"
        private const val MAX_MANIFEST = 64 * 1024L
        private const val MAX_FILE = 2 * 1024 * 1024L
        private const val MAX_TOTAL = 8 * 1024 * 1024L
        private const val MAX_THEME = 64 * 1024L
        private const val MAX_ICONS = 64 * 1024L
    }
}
