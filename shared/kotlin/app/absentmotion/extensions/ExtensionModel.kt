package app.absentmotion.extensions

import org.json.JSONArray
import org.json.JSONObject

/** The six public extension categories. A bundle picks exactly one category. */
enum class ExtensionCategory(val id: String, val label: String) {
    Behavior("behavior", "Behavior"),
    Theme("theme", "Theme"),
    Icons("icons", "Icons"),
    Effects("effects", "Effects"),
    Automation("automation", "Automation"),
    Layout("layout", "Layout");

    companion object {
        fun of(value: String?): ExtensionCategory? = entries.firstOrNull { it.id == value?.trim()?.lowercase() }
    }
}

enum class ExtensionPropertyKind {
    Toggle, Slider, Choice, Text, Color;

    companion object {
        fun of(value: String?): ExtensionPropertyKind? = when (value?.trim()?.lowercase()) {
            "toggle", "switch", "bool", "boolean" -> Toggle
            "slider", "number", "range" -> Slider
            "choice", "select" -> Choice
            "text", "string" -> Text
            "color", "colour" -> Color
            else -> null
        }
    }
}

/**
 * A property declared by an extension. The app only knows these five safe
 * control types; a bundle cannot declare a custom control or code callback.
 */
data class ExtensionProperty(
    val id: String,
    val label: String,
    val kind: ExtensionPropertyKind,
    val default: String,
    val min: Float = 0f,
    val max: Float = 1f,
    val step: Float = 0.01f,
    val options: List<String> = emptyList(),
    val optionValues: List<String> = emptyList(),
) {
    fun value(raw: String?): String = raw?.takeIf { it.length <= MAX_VALUE } ?: default

    fun number(raw: String?): Float = value(raw).toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(min, max) ?: default.toFloatOrNull() ?: 0f

    fun flag(raw: String?): Boolean = value(raw).lowercase() == "true" || number(raw) > 0.5f

    fun choice(raw: String?): String {
        val current = value(raw)
        return if (current in optionValues) current else optionValues.firstOrNull() ?: default
    }

    fun choiceIndex(raw: String?): Int = optionValues.indexOf(choice(raw)).coerceAtLeast(0)

    companion object {
        private const val MAX_VALUE = 512

        fun parse(json: JSONObject): ExtensionProperty {
            val id = json.optString("id").trim()
            require(id.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,47}"))) { "bad property id: $id" }
            val kind = ExtensionPropertyKind.of(json.optString("type")) ?: error("$id: unknown property type")
            val label = json.optString("label").trim().ifBlank { id.replace('_', ' ') }.take(64)
            val options = json.optJSONArray("options").strings()
            val values = json.optJSONArray("values").strings().ifEmpty { options }
            if (kind == ExtensionPropertyKind.Choice) {
                require(options.isNotEmpty()) { "$id: choice needs options" }
                require(options.size == values.size) { "$id: options and values differ in length" }
                require(options.size <= 24) { "$id: too many choices" }
            }
            val rawDefault = when (kind) {
                ExtensionPropertyKind.Toggle -> json.optBoolean("default", true).toString()
                ExtensionPropertyKind.Slider -> json.optDouble("default", 0.0).toFloat().toString()
                ExtensionPropertyKind.Choice -> json.optString("default").takeIf { it in values } ?: values.first()
                ExtensionPropertyKind.Text, ExtensionPropertyKind.Color -> json.optString("default")
            }.take(MAX_VALUE)
            return ExtensionProperty(
                id, label, kind, rawDefault,
                json.optDouble("min", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f,
                json.optDouble("max", 1.0).toFloat().takeIf { it.isFinite() } ?: 1f,
                json.optDouble("step", 0.01).toFloat().takeIf { it.isFinite() && it > 0f } ?: 0.01f,
                options, values,
            ).let { if (it.kind == ExtensionPropertyKind.Slider && it.max <= it.min) it.copy(max = it.min + 1f) else it }
        }
    }
}

data class ExtensionManifest(
    val schema: Int,
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val category: ExtensionCategory,
    val capabilities: List<String>,
    val properties: List<ExtensionProperty>,
) {
    fun property(id: String): ExtensionProperty? = properties.firstOrNull { it.id == id }

    companion object {
        const val SCHEMA = 1
        private val KnownCapabilities = setOf(
            "timeline.follow", "theme.css", "icons.home", "effects.custom", "layout.tools", "automation.safe",
        )

        fun parse(json: JSONObject): ExtensionManifest {
            val schema = json.optInt("schema", SCHEMA)
            require(schema == SCHEMA) { "unsupported extension schema $schema" }
            val id = json.optString("id").trim()
            require(id.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}"))) { "bad extension id: $id" }
            val name = json.optString("name").trim().take(64)
            require(name.isNotBlank()) { "extension name is empty" }
            val category = ExtensionCategory.of(json.optString("category")) ?: error("unknown extension category")
            val capabilities = json.optJSONArray("capabilities").strings()
            require(capabilities.size <= 8) { "too many capabilities" }
            require(capabilities.all { it in KnownCapabilities }) { "unknown extension capability" }
            val props = json.optJSONArray("properties") ?: JSONArray()
            require(props.length() <= 24) { "too many extension properties" }
            val parsed = List(props.length()) { ExtensionProperty.parse(props.getJSONObject(it)) }
            require(parsed.map { it.id }.toSet().size == parsed.size) { "duplicate extension property" }
            return ExtensionManifest(
                schema, id, name.take(64), json.optString("version").trim().take(24).ifBlank { "1.0.0" },
                json.optString("author").trim().take(64), json.optString("description").trim().take(400),
                category, capabilities, parsed,
            )
        }
    }
}

data class InstalledExtension(
    val manifest: ExtensionManifest,
    val directory: java.io.File,
    val enabled: Boolean,
    val bundled: Boolean = false,
) {
    val id: String get() = manifest.id
    val category: ExtensionCategory get() = manifest.category
    fun property(id: String): ExtensionProperty? = manifest.property(id)
}

data class ExtensionIconSpec(
    val path: String,
    val stroke: Boolean = true,
    val width: Float = 1.8f,
    val mirror: Boolean = false,
)

data class ExtensionImport(
    val extension: InstalledExtension? = null,
    val error: String? = null,
)

internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val value = optString(i).trim()
            if (value.length in 1..128) add(value)
        }
    }
}

/** A conservative path check for every zip entry before anything is written. */
internal object ExtensionPaths {
    fun safeEntry(name: String): String? {
        val clean = name.replace('\\', '/').trim()
        if (clean.isEmpty() || clean.length > 120) return null
        if (clean.startsWith('/') || clean.startsWith("../") || clean.contains("/../") || clean.endsWith("/..")) return null
        if (clean == "." || clean == ".." || clean.contains(":") || clean.contains('\u0000')) return null
        val parts = clean.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return clean
    }

    fun allowed(name: String): Boolean {
        if (name == "manifest.json" || name == "README.md" || name == "LICENSE" || name == "LICENSE.txt" || name == "theme.css") return true
        if (name == "icons/icons.json") return true
        if (name.startsWith("icons/") && (name.endsWith(".svg") || name.endsWith(".png") || name.endsWith(".webp"))) return true
        if (name.startsWith("effects/") && name.endsWith(".json")) return true
        if (name.startsWith("layout/") && name.endsWith(".json")) return true
        return false
    }
}
