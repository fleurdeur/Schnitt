package app.absentmotion.extensions

import app.absentmotion.ui.theme.Palette

/**
 * A deliberately small CSS reader for theme extensions. Only custom
 * properties that resolve to a real Palette name and a hex colour are read.
 * Selectors, URLs, imports and arbitrary declarations are ignored.
 */
object ExtensionCss {
    fun apply(base: Palette, css: String): Palette {
        val clean = css.take(64 * 1024).replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        val colours = HashMap<String, androidx.compose.ui.graphics.Color>()
        Regex("""--([A-Za-z][A-Za-z0-9-]{0,47})\s*:\s*([^;}{]+)""").findAll(clean).take(256).forEach { match ->
            val key = paletteName(match.groupValues[1]) ?: return@forEach
            val colour = parseColour(match.groupValues[2].trim()) ?: return@forEach
            if (key in Palette.Names) colours[key] = colour
        }
        return if (colours.isEmpty()) base else Palette.from(colours, base)
    }

    fun paletteName(property: String): String? {
        if (property.contains('-')) return property.split('-').filter { it.isNotEmpty() }.joinToString("") {
            it.replaceFirstChar { c -> c.uppercase() }
        }
        return Palette.Names.firstOrNull { it.equals(property, ignoreCase = true) }
    }

    private fun parseColour(text: String): androidx.compose.ui.graphics.Color? {
        val raw = text.trim().removeSuffix(";").trim()
        if (!raw.startsWith("#")) return null
        val hex = raw.substring(1)
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        val normalized = when (hex.length) {
            3, 4 -> hex.map { "$it$it" }.joinToString("")
            else -> hex
        }
        val expanded = when (normalized.length) {
            6 -> "ff$normalized"
            8 -> normalized
            else -> return null
        }
        return runCatching { androidx.compose.ui.graphics.Color(expanded.toLong(16)) }.getOrNull()
    }
}
