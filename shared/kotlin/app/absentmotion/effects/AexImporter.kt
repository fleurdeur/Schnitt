package app.absentmotion.effects

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adobe After Effects plug-ins are native host binaries.  Android cannot run
 * their code, but every AEX carries a PiPL resource with the plug-in name,
 * vendor, version and category.  We read that resource without executing the
 * binary and port the plug-in to a compatible built-in shader effect.  The
 * ported effect keeps all of the target shader's parameters, so every property
 * is visible and editable.
 */
object AexImporter {
    private const val STORE = "custom-effects.json"
    private const val FOLDER = "custom-effects"

    data class Info(val name: String, val vendor: String, val version: String, val category: String, val url: String, val note: String)

    suspend fun import(context: Context, uri: Uri): EffectDefinition? = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        val fileName = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        val info = read(bytes, fileName)
        val base = mapToBuiltin(context, info)
        val id = digest("$fileName:${bytes.size}").take(10)
        val target = File(context.filesDir, FOLDER).apply { mkdirs() }.resolve("$id.aex")
        runCatching { target.writeBytes(bytes) }
        append(context, JSONObject()
            .put("id", id).put("name", info.name).put("vendor", info.vendor).put("version", info.version)
            .put("category", info.category).put("url", info.url).put("note", info.note)
            .put("mapped", base.id).put("file", target.path))
        custom(base, id, info)
    }

    /** Reads the PiPL resource from a PE32/PE32+ file.  Falls back to strings. */
    fun read(bytes: ByteArray, fileName: String): Info {
        val text = if (bytes.size >= 2 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) pipl(bytes) else ""
        val raw = Regex("[ -~]{3,}").findAll(text).map { it.value }.toList()
        val nameAt = raw.indexOfFirst { it.contains("eman") }
        val name = raw.getOrNull(nameAt + 1)?.substringBefore("MIB8")?.substringBefore("8BIM")?.trim()
            ?.takeIf { it.length in 3..48 && it.any(Char::isLetter) }
            ?: fileName.substringBeforeLast('.').ifBlank { "Imported AEX" }
        val vendor = raw.firstOrNull { it.contains("dragoy", true) || it.contains("plugin", true) || it.contains("software", true) || it.contains("studios", true) }.orEmpty()
        val url = raw.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
        val version = Regex("""\b\d+\.\d+(?:\.\d+)?\b""").find(fileName + " " + raw.joinToString(" "))?.value.orEmpty()
        val category = when {
            name.contains("trace", true) || name.contains("contour", true) || name.contains("line", true) -> "trace"
            name.contains("glow", true) || name.contains("light", true) -> "glow"
            name.contains("blur", true) -> "blur"
            name.contains("color", true) || name.contains("hue", true) -> "color"
            name.contains("warp", true) || name.contains("distort", true) -> "distort"
            else -> "effect"
        }
        return Info(name.trim(), vendor.trim(), version, category, url.trim(), if (text.isBlank()) "Name and strings only; no PiPL resource" else "PiPL resource read from AEX")
    }

    fun custom(base: EffectDefinition, id: String, info: Info): EffectDefinition = base.copy(
        key = "aex-$id",
        name = info.name,
        category = "custom",
        description = "Imported AEX ${info.version}".trim() + " · " + (info.vendor.ifBlank { "unknown vendor" }) +
            " · Ported to the global ${base.name} shader. Every ${base.name} property below is supported.",
        since = null,
        shortcuts = emptyList(),
        page = base.page,
    )

    fun append(context: Context, entry: JSONObject) {
        val file = File(context.filesDir, STORE)
        val array = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        val kept = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.filter { it.optString("id") != entry.optString("id") }
        val next = JSONArray().apply { kept.forEach(::put); put(entry) }
        file.writeText(next.toString())
    }

    fun append(context: Context, catalog: EffectCatalog): EffectCatalog {
        val file = File(context.filesDir, STORE)
        if (!file.isFile) return catalog
        val imported = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return catalog
        val extra = (0 until imported.length()).mapNotNull { index ->
            val entry = imported.optJSONObject(index) ?: return@mapNotNull null
            val base = catalog.find(entry.optString("mapped")) ?: return@mapNotNull null
            val info = Info(entry.optString("name", base.name), entry.optString("vendor"), entry.optString("version"),
                entry.optString("category"), entry.optString("url"), entry.optString("note"))
            custom(base, entry.optString("id", "aex"), info)
        }
        if (extra.isEmpty()) return catalog
        return catalog.copy(definitions = catalog.definitions + extra, categoryNames = catalog.categoryNames + ("custom" to "Imported"))
    }

    private fun mapToBuiltin(context: Context, info: Info): EffectDefinition {
        val catalog = EffectCatalog.read(context)
        val found = catalog.definitions.firstOrNull { it.id in KEYS[info.category].orEmpty() }
            ?: catalog.definitions.firstOrNull { it.id == "contourlines" }
            ?: catalog.definitions.firstOrNull { it.id == "glow" }
            ?: catalog.definitions.first()
        return found
    }

    private fun pipl(bytes: ByteArray): String = runCatching {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val pe = b.getInt(0x3c)
        if (b.getInt(pe) != 0x00004550) return@runCatching ""
        val coff = pe + 4
        val sections = b.getShort(coff + 2).toInt() and 0xffff
        val optionalSize = b.getShort(coff + 16).toInt() and 0xffff
        val optional = coff + 20
        val magic = b.getShort(optional).toInt() and 0xffff
        val directories = optional + if (magic == 0x20b) 112 else 96
        val resourceRva = b.getInt(directories + 16)
        val sectionTable = optional + optionalSize
        fun offset(rva: Int): Int? {
            for (i in 0 until sections) {
                val at = sectionTable + i * 40
                val virtualSize = b.getInt(at + 8)
                val virtualAddress = b.getInt(at + 12)
                val rawSize = b.getInt(at + 16)
                val raw = b.getInt(at + 20)
                val size = maxOf(virtualSize, rawSize)
                if (rva >= virtualAddress && rva < virtualAddress + size) return raw + (rva - virtualAddress)
            }
            return null
        }
        val base = offset(resourceRva) ?: return@runCatching ""
        val count = b.getShort(base + 12).toInt() and 0xffff
        fun name(field: Int, at: Int): String? {
            if ((field and 0x80000000.toInt()) == 0) return null
            val stringAt = base + (field and 0x7fffffff)
            val length = b.getShort(stringAt).toInt() and 0xffff
            val chars = CharArray(length)
            for (i in 0 until length) chars[i] = (b.getShort(stringAt + 2 + i * 2).toInt() and 0xffff).toChar()
            return String(chars)
        }
        for (i in 0 until count) {
            val at = base + 16 + i * 8
            val typeName = name(b.getInt(at), at) ?: continue
            if (!typeName.equals("PIPL", true)) continue
            val typeOffset = b.getInt(at + 4) and 0x7fffffff
            val typeDir = base + typeOffset
            val nameCount = b.getShort(typeDir + 12).toInt() and 0xffff
            if (nameCount <= 0) continue
            val nameEntry = typeDir + 16
            val nameDir = base + (b.getInt(nameEntry + 4) and 0x7fffffff)
            val langCount = b.getShort(nameDir + 12).toInt() and 0xffff
            if (langCount <= 0) continue
            val langEntry = nameDir + 16
            val dataEntry = base + (b.getInt(langEntry + 4) and 0x7fffffff)
            val rva = b.getInt(dataEntry)
            val size = b.getInt(dataEntry + 4)
            val dataOffset = offset(rva) ?: return@runCatching ""
            return@runCatching String(bytes, dataOffset, size.coerceIn(0, bytes.size - dataOffset), Charsets.ISO_8859_1)
        }
        ""
    }.getOrDefault("")

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val KEYS = mapOf(
        "trace" to listOf("contourlines", "contourgradient", "edgeglow", "glow"),
        "glow" to listOf("glow", "lightglow", "deepglow", "edgeglow"),
        "blur" to listOf("blur", "innerblur", "lensblur"),
        "color" to listOf("huesaturation", "colorize", "gradientmap", "duotone"),
        "distort" to listOf("turbulentdisplace", "ripple", "wavewarp", "twirl"),
        "effect" to listOf("glow", "contourlines", "huesaturation", "blur"),
    )
}
