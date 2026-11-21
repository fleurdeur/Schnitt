package app.absentmotion.effects

import app.absentmotion.platform.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Additional Alight-compatible shader effects that are not part of the core
 * bundled pack. They live in a separate asset folder so the core pack keeps
 * its strict schema, help pages and translations.
 */
internal object TrueEffectLibrary {
    fun append(context: Context, catalog: EffectCatalog): EffectCatalog = runCatching {
        val assets = context.assets
        val keys = JSONArray(assets.open("true-effects/index.json").bufferedReader().use { it.readText() })
        val extra = List(keys.length()) { keys.getString(it) }.mapNotNull { key ->
            runCatching {
                val json = JSONObject(assets.open("true-effects/$key.json").bufferedReader().use { it.readText() })
                EffectDefinition.parse(json).copy(alsoIn = listOf("external"))
            }.getOrNull()
        }
        if (extra.isEmpty()) catalog else catalog.copy(definitions = catalog.definitions + extra)
    }.getOrDefault(catalog)
}
