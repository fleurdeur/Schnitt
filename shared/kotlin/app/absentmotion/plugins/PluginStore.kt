package app.absentmotion.plugins

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class PluginKind { Cep, Script, Native, Effect, Shader }

/**
 * A plugin is a self-contained folder under filesDir/plugins.  Nothing is
 * executed from an untrusted path directly: panels are hosted by a sandboxed
 * WebView and scripts are evaluated in the same sandbox with a small bridge.
 */
data class Plugin(
    val id: String,
    val name: String,
    val version: String,
    val kind: PluginKind,
    val folder: String,
    val entry: String?,
    val enabled: Boolean,
    val summary: String,
    val effects: List<String>,
    val source: String,
) {
    val panel: Boolean get() = entry != null && (kind == PluginKind.Cep || kind == PluginKind.Script)
    val entryFile: File get() = File(folder, entry.orEmpty())
}

data class PluginImport(val plugin: Plugin?, val error: String? = null)

class PluginStore(private val context: Context) {
    private val root: File get() = File(context.filesDir, "plugins").also { it.mkdirs() }

    fun list(): List<Plugin> = runCatching {
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { readMeta(it) }.sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    fun find(id: String): Plugin? = list().firstOrNull { it.id == id }

    fun setEnabled(id: String, enabled: Boolean) {
        val meta = File(root, id).resolve(META)
        if (!meta.isFile) return
        runCatching {
            val json = JSONObject(meta.readText())
            json.put("enabled", enabled)
            meta.writeText(json.toString())
        }
    }

    fun remove(id: String) {
        val folder = File(root, id)
        if (folder.parentFile?.canonicalFile == root.canonicalFile) folder.deleteRecursively()
    }

    suspend fun import(uri: Uri, displayName: String?): PluginImport = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "schnitt-plugin-${System.nanoTime()}.bin")
        try {
            val name = copyToTemp(uri, temp, displayName) ?: return@withContext PluginImport(null, "empty")
            val id = slug(name.substringBeforeLast('.')) + "-" + digest("$name:${temp.length()}:${System.currentTimeMillis()}").take(6)
            val folder = File(root, id).apply { mkdirs() }
            val zip = temp.isZip()
            if (zip) unpack(temp, folder) else temp.copyTo(File(folder, safeName(name)), overwrite = true)
            val plugin = describe(id, folder, name, if (zip) "zxp" else name.substringAfterLast('.', ""))
            if (plugin == null) {
                folder.deleteRecursively()
                PluginImport(null, "unsupported")
            } else {
                list().firstOrNull { it.name.equals(plugin.name, true) && it.id != plugin.id }?.let { remove(it.id) }
                writeMeta(folder, plugin.copy(effects = plugin.effects.distinct()))
                PluginImport(plugin)
            }
        } catch (t: Throwable) {
            PluginImport(null, t.message ?: "failed")
        } finally {
            temp.delete()
        }
    }

    private fun copyToTemp(uri: Uri, target: File, display: String? = null): String? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        var total = 0L
        var name = display?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        }
        input.use { source ->
            target.outputStream().buffered().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_PLUGIN) return null
                    out.write(buffer, 0, read)
                }
            }
        }
        if (total == 0L) return null
        if (name.isBlank()) name = "plugin"
        return name
    }

    private fun unpack(file: File, folder: File) {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.replace('\\', '/').trimStart('/')
                if (path.isNotBlank() && !path.split('/').any { it == ".." } && !entry.isDirectory) {
                    val target = File(folder, path)
                    if (!target.canonicalFile.startsWith(folder.canonicalFile)) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_UNPACKED) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun describe(id: String, folder: File, fileName: String, source: String): Plugin? {
        val custom = folder.walkTopDown().firstOrNull { it.isFile && it.name.equals("plugin.json", true) }
        if (custom != null) {
            runCatching {
                val json = JSONObject(custom.readText())
                val entry = json.optString("entry").takeIf { it.isNotBlank() }?.let { resolve(folder, custom.parentFile ?: folder, it) }
                val effects = json.optJSONArray("effects")?.let { a -> List(a.length()) { a.optString(it) } }?.filter { it.isNotBlank() }.orEmpty()
                return Plugin(
                    id = id,
                    name = json.optString("name").ifBlank { fileName.substringBeforeLast('.') },
                    version = json.optString("version").ifBlank { "1.0" },
                    kind = kindOf(json.optString("kind"), entry?.path),
                    folder = folder.path,
                    entry = entry?.relativeTo(folder)?.invariantSeparatorsPath,
                    enabled = json.optBoolean("enabled", true),
                    summary = json.optString("summary"),
                    effects = if (effects.isEmpty()) mapEffects(json.toString() + " " + fileName) else effects,
                    source = source,
                )
            }
        }
        val manifest = folder.walkTopDown().firstOrNull { it.isFile && it.name.equals("manifest.xml", true) && it.parentFile?.name.equals("CSXS", true) }
            ?: folder.walkTopDown().firstOrNull { it.isFile && it.name.equals("manifest.xml", true) }
        if (manifest != null) {
            val xml = runCatching { manifest.readText() }.getOrDefault("")
            val main = Regex("<MainPath>([^<]+)</MainPath>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.trim()
            val name = Regex("(?:ExtensionBundleName|Name)\\s*=\\s*\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
                ?: Regex("<ExtensionBundleName>([^<]+)</ExtensionBundleName>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)
                ?: fileName.substringBeforeLast('.')
            val version = Regex("Version\\s*=\\s*\"([^\"]+)\"").find(xml)?.groupValues?.get(1) ?: "1.0"
            val entry = main?.let { resolve(folder, manifest.parentFile?.parentFile ?: folder, it) }
            val details = Regex("<Description>([^<]+)</Description>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.trim()
            val summary = details?.takeIf { it.isNotBlank() } ?: "CEP extension panel · ${entry?.name ?: "script host"} · ${if (xml.contains("AfterEffects", true)) "After Effects" else "Adobe"} compatible"
            return Plugin(id, name.trim(), version, if (entry != null) PluginKind.Cep else PluginKind.Script, folder.path,
                entry?.relativeTo(folder)?.invariantSeparatorsPath, true, summary, mapEffects(xml + " " + fileName), source)
        }
        val files = folder.walkTopDown().filter { it.isFile }.toList()
        val script = files.firstOrNull { it.extension.equals("jsxbin", true) } ?: files.firstOrNull { it.extension.equals("jsx", true) }
        val html = files.firstOrNull { it.extension.equals("html", true) || it.extension.equals("htm", true) }
        val native = files.firstOrNull { it.extension.lowercase() in NATIVE }
        val shader = files.firstOrNull { it.extension.lowercase() in SHADER }
        val chosen = script ?: html ?: native ?: shader ?: files.firstOrNull()
        if (chosen == null) return null
        val kind = when (chosen) {
            script -> PluginKind.Script
            html -> PluginKind.Cep
            native -> PluginKind.Native
            shader -> PluginKind.Shader
            else -> PluginKind.Effect
        }
        val effects = mapEffects(fileName + " " + folder.name)
        val summary = when (kind) {
            PluginKind.Native -> "Native Adobe plug-in binary · $fileName · converted into an Android effect with its own generated shader and editable properties"
            PluginKind.Shader -> "Fragment shader · $fileName · converted into an Android effect with editable properties"
            PluginKind.Script -> "ExtendScript panel · $fileName · runs in the sandboxed panel host with layer and effect commands"
            PluginKind.Cep -> "CEP HTML panel · ${chosen.name} · exposes the plug-in's own controls"
            PluginKind.Effect -> "Imported effect package · $fileName · converted into its own Android effect"
        } + if (effects.isEmpty()) "" else " · ${effects.size} mapped effects"
        return Plugin(
            id = id, name = fileName.substringBeforeLast('.').ifBlank { "Plugin" }, version = "1.0", kind = kind,
            folder = folder.path, entry = chosen.relativeTo(folder).invariantSeparatorsPath, enabled = true,
            summary = summary, effects = effects, source = source,
        )
    }

    private fun kindOf(raw: String, entry: String?): PluginKind = when (raw.lowercase()) {
        "cep", "panel", "html" -> PluginKind.Cep
        "script", "jsx", "jsxbin" -> PluginKind.Script
        "native", "aex" -> PluginKind.Native
        "shader", "glsl", "frag" -> PluginKind.Shader
        else -> if (entry != null) PluginKind.Cep else PluginKind.Effect
    }

    private fun resolve(folder: File, base: File, path: String): File {
        val clean = path.trim().removePrefix("./").removePrefix("/")
        val direct = File(base, clean)
        val fromRoot = File(folder, clean)
        return when {
            direct.isFile -> direct
            fromRoot.isFile -> fromRoot
            else -> direct
        }
    }

    private fun readMeta(folder: File): Plugin? {
        val meta = folder.resolve(META).takeIf { it.isFile } ?: return null
        return runCatching {
            val json = JSONObject(meta.readText())
            Plugin(
                id = json.getString("id"), name = json.optString("name", folder.name), version = json.optString("version", "1.0"),
                kind = PluginKind.entries.firstOrNull { it.name == json.optString("kind") } ?: PluginKind.Effect,
                folder = folder.path, entry = json.optString("entry").takeIf { it.isNotBlank() },
                enabled = json.optBoolean("enabled", true), summary = json.optString("summary"),
                effects = json.optJSONArray("effects")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
                source = json.optString("source", "file"),
            )
        }.getOrNull()
    }

    private fun writeMeta(folder: File, plugin: Plugin) {
        val json = JSONObject()
            .put("id", plugin.id).put("name", plugin.name).put("version", plugin.version).put("kind", plugin.kind.name)
            .put("entry", plugin.entry.orEmpty()).put("enabled", plugin.enabled).put("summary", plugin.summary)
            .put("effects", JSONArray(plugin.effects)).put("source", plugin.source)
        folder.resolve(META).writeText(json.toString())
    }

    private fun File.isZip(): Boolean = runCatching {
        inputStream().use { stream ->
            val head = ByteArray(4)
            val read = stream.read(head)
            read >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "plugin.bin" }
    private fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifBlank { "plugin" }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

    companion object {
        private const val META = "plugin.json"
        private const val MAX_PLUGIN = 192L shl 20
        private const val MAX_UNPACKED = 512L shl 20
        private val NATIVE = setOf("aex", "dll", "dylib", "so", "bundle")
        private val SHADER = setOf("frag", "glsl", "shader", "sksl")

        fun mapEffects(text: String): List<String> {
            val t = text.lowercase()
            val out = LinkedHashSet<String>()
            fun take(id: String, vararg keys: String) { if (keys.any { it in t }) out += id }
            take("blur", "blur", "gaussian")
            take("glow", "glow", "bloom", "neon")
            take("brightnesscontrast", "brightness", "contrast", "levels")
            take("huesaturation", "hue", "saturation", "vibrance")
            take("glitch", "glitch", "datamosh", "corrupt")
            take("chromaticzoomblur", "chromatic", "aberration", "rgb split")
            take("turbulentdisplace", "turbulence", "displace", "warp")
            take("ripple", "ripple", "wave")
            take("posterize", "posterize", "poster")
            take("noisedisplace", "noise", "grain", "film")
            take("vignette", "vignette")
            take("sepia", "sepia")
            take("invert", "invert")
            take("exposure", "exposure")
            take("pixelate", "pixelate", "mosaic")
            take("sharpen", "sharpen", "unsharp")
            take("motionblur", "motion blur", "directional blur")
            take("vhs", "crt", "scanline", "vhs", "retro")
            take("chromaticzoomblur", "chromatic", "aberration", "rgb split")
            take("autoshake", "shake", "handheld", "camera shake")
            take("textshake", "text", "typewriter", "kinetic type")
            take("glitch", "transition", "glitch", "dissolve")
            take("turbulentdisplace", "warp", "distort", "mesh")
            take("colorbalance", "color", "grade", "tint", "lut")
            take("zoomstreaks", "zoom", "streak")
            return out.toList()
        }
    }
}

internal fun InputStream.readCapped(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (total < limit) {
        val read = read(buffer)
        if (read <= 0) break
        total += read
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
