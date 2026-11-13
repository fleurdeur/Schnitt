package app.absentmotion.audio

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable audio-effect preset JSON. Accepts a single object, a JSON array, or
 * {"name": "...", "effects": [...]}. Each effect uses the FxKind xml name
 * ("eq", "lowpass", "reverb", "echo", "compressor", "gate", "highpitch"...).
 */
object AudioFxJson {
    fun read(context: Context, uri: Uri): List<AudioFx> {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return emptyList()
        val root = runCatching { org.json.JSONTokener(text).nextValue() }.getOrNull() ?: return emptyList()
        val entries = when (root) {
            is JSONObject -> root.optJSONArray("effects") ?: root.optJSONArray("presets") ?: JSONArray().put(root)
            is JSONArray -> root
            else -> return emptyList()
        }
        val out = ArrayList<AudioFx>(entries.length())
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val type = entry.optString("type").ifBlank { entry.optString("kind") }.ifBlank { entry.optString("id") }
            val kind = FxKind.entries.firstOrNull { it.xml.equals(type, true) || it.name.equals(type, true) } ?: continue
            var fx = AudioFx.of(kind)
            val params = entry.optJSONObject("params") ?: entry
            for (param in kind.params) {
                if (!params.has(param.key)) continue
                val value = params.optDouble(param.key, param.default.toDouble()).toFloat()
                fx = fx.with(param, value)
            }
            if (params.has("mix")) fx = fx.withMix(params.optDouble("mix", 1.0).toFloat())
            if (params.optBoolean("bypass", false)) fx = fx.withBypass(true)
            out += fx
        }
        return out
    }
}
