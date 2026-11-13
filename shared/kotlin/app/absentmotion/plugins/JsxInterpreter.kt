package app.absentmotion.plugins

import app.absentmotion.editor.EditorState
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.Layer
import app.absentmotion.model.addEffect
import app.absentmotion.model.effects
import app.absentmotion.model.formatFloats
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import java.util.Locale
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.ScriptableObject

sealed interface JsCommand {
    data class AddEffect(val handle: Int, val matchName: String) : JsCommand
    data class SetEffect(val handle: Int, val property: String, val value: Any?) : JsCommand
    data class SetLayer(val property: String, val value: Any?) : JsCommand
}

class Bridge(private val width: Int, private val height: Int, private val timeMs: Int) {
    val commands = ArrayList<JsCommand>()
    private var next = 1
    fun width(): Int = width
    fun height(): Int = height
    fun time(): Int = timeMs
    fun addEffect(name: String): Int { val id = next++; commands += JsCommand.AddEffect(id, name); return id }
    fun setEffectProperty(handle: Int, name: String, value: Any?) { commands += JsCommand.SetEffect(handle, name, value) }
    fun setLayerProperty(name: String, value: Any?) { commands += JsCommand.SetLayer(name, value) }
    fun log(text: String) {}
}

object JsxInterpreter {
    private val prelude = """
var __layer = {
  property: function(n) {
    if (n === 'ADBE Effect Parade' || n === 'Effects' || n === 'Effects Group') return __parade();
    if (n === 'ADBE Transform Group') return { property: function(p) { return { setValue: function(v) { Schnitt.setLayerProperty(String(p), v); }, setValueAtTime: function(v,t) { Schnitt.setLayerProperty(String(p), v); } }; } };
    return { setValue: function(v) { Schnitt.setLayerProperty(String(n), v); }, setValueAtTime: function(v,t) { Schnitt.setLayerProperty(String(n), v); }, property: function() { return this; } };
  },
  selected: true
};
__layer.effects = __parade();
__layer.masks = { addProperty: function(n) { return { property: function() { return { setValue: function(){} }; } }; } };
function __parade() {
  return {
    addProperty: function(n) { return __effect(Schnitt.addEffect(String(n))); },
    property: function(n) { return __effect(0); },
    numProperties: 0
  };
}
function __effect(id) {
  return {
    property: function(n) { return { setValue: function(v) { if (id) Schnitt.setEffectProperty(id, String(n), v); }, setValueAtTime: function(v,t) { if (id) Schnitt.setEffectProperty(id, String(n), v); }, property: function() { return this; } }; },
    setValue: function(v) { if (id) Schnitt.setEffectProperty(id, 'value', v); },
    addProperty: function(n) { return __effect(Schnitt.addEffect(String(n))); },
    remove: function() {}
  };
}
var app = {
  project: {
    activeItem: { width: Schnitt.width(), height: Schnitt.height(), time: Schnitt.time(), duration: 5000,
      layers: [__layer], selectedLayers: [__layer], numLayers: 1,
      layer: function(i) { return __layer; }, property: __layer.property,
      layers: { length: 1, 0: __layer, addShape: function(){return __layer;}, addText: function(){return __layer;}, addSolid: function(){return __layer;}, addNull: function(){return __layer;}, addCamera: function(){return __layer;}, add: function(){return __layer;} }
    },
    numItems: 1, item: function(i) { return app.project.activeItem; },
    importFile: function() { return null; }, beginUndoGroup: function(){}, endUndoGroup: function(){}
  },
  beginUndoGroup: function(){}, endUndoGroup: function(){}, beginSuppressDialogs: function(){}, endSuppressDialogs: function(){}, executeCommand: function(){},
  alert: function(t) { Schnitt.log(String(t)); },
  beep: function(){}, version: '1.0'
};
function alert(t) { Schnitt.log(String(t)); }
""".trimIndent()

    fun execute(state: EditorState, layerId: Long, source: String, catalog: EffectCatalog?): String {
        val context = Context.enter()
        return try {
            context.optimizationLevel = -1
            val scope = context.initStandardObjects()
            val bridge = Bridge(state.scene.width, state.scene.height, state.timeMs)
            ScriptableObject.putProperty(scope, "Schnitt", Context.javaToJS(bridge, scope))
            context.evaluateString(scope, prelude, "schnitt-prelude", 1, null)
            context.evaluateString(scope, source, "plugin.jsx", 1, null)
            Context.exit()
            val made = applyCommands(state, layerId, bridge.commands, catalog)
            if (made == 0) "Script finished, but no supported effect was added" else "Script finished: $made effects added"
        } catch (e: Exception) {
            Context.exit()
            "JSX error: ${e.message}"
        }
    }

    private fun applyCommands(state: EditorState, layerId: Long, commands: List<JsCommand>, catalog: EffectCatalog?): Int {
        if (commands.isEmpty() || catalog == null) return 0
        var layer = state.scene.layer(layerId) ?: return 0
        val handles = HashMap<Int, Int>()
        var made = 0
        fun definition(match: String): EffectDefinition? {
            val key = AeEffectMap.key(match) ?: return null
            return catalog.find("absent.$key") ?: catalog.definitions.firstOrNull { it.key == key }
        }
        for (cmd in commands) when (cmd) {
            is JsCommand.AddEffect -> {
                val def = definition(cmd.matchName) ?: continue
                val index = layer.effects.size
                layer = layer.addEffect(def.instance(null, (layer.endTime - layer.startTime).coerceAtLeast(1)))
                handles[cmd.handle] = index
                made++
            }
            is JsCommand.SetEffect -> {
                val index = handles[cmd.handle] ?: -1
                if (index < 0 || index >= layer.effects.size) continue
                val def = catalog.find(layer.effects[index]["id"] ?: "") ?: continue
                val normalized = cmd.property.lowercase().replace("adbe", "").replace(" ", "").replace("_", "")
                val param = def.params.firstOrNull { p ->
                    val id = p.id.lowercase().replace("_", "")
                    val label = p.label.lowercase().replace(" ", "").replace("_", "")
                    id == normalized || label == normalized || normalized.contains(id) || id.contains(normalized)
                } ?: continue
                layer = layer.withEffect(index) { it.withEffectProperty(param.id, param.type, value(cmd.value, param.type), layer.t, layer.snap) }
            }
            is JsCommand.SetLayer -> {
                val p = cmd.property.lowercase()
                val v = values(cmd.value)
                layer = when {
                    p == "position" || p == "location" -> layer.withTransform("location", v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
                    p == "scale" -> layer.withTransform("scale", v.getOrElse(0) { 1f }, v.getOrElse(1) { v.getOrElse(0) { 1f } })
                    p == "rotation" -> layer.withTransform("rotation", v.getOrElse(0) { 0f })
                    p == "opacity" -> layer.withTransform("opacity", v.getOrElse(0) { 1f })
                    else -> layer
                }
            }
        }
        if (made == 0 && commands.none { it is JsCommand.SetLayer }) return 0
        state.edit { it.update(layerId) { _ -> layer } }
        state.selectedId = layerId
        return made
    }

    private fun values(v: Any?): List<Float> = when (v) {
        is NativeArray -> (0 until v.length).mapNotNull { (v.get(it) as? Number)?.toFloat() }
        is Number -> listOf(v.toFloat())
        is Array<*> -> v.mapNotNull { (it as? Number)?.toFloat() }
        else -> v?.toString()?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }.orEmpty()
    }

    private fun value(v: Any?, type: String): String {
        val list = values(v)
        return when (type) {
            "vec2" -> formatFloats(floatArrayOf(list.getOrElse(0) { 0f }, list.getOrElse(1) { 0f }))
            "color" -> v?.toString()?.takeIf { it.startsWith("#") } ?: "#ffffffff"
            "bool" -> if (v == true || (v as? Number)?.toInt() == 1) "true" else "false"
            else -> String.format(Locale.US, "%.6f", list.firstOrNull() ?: 0f)
        }
    }
}

object AeEffectMap {
    fun key(match: String): String? {
        val m = match.uppercase(Locale.US)
        return when {
            "DISPLACEMENT MAP" in m -> "displacementmap"
            "WAVE WARP" in m -> "wavewarp"
            "EASY LEVELS" in m || "LEVELS" in m -> "levels"
            "HUE SATURATION" in m -> "huesaturation"
            "BOX BLUR" in m || "GAUSSIAN BLUR" in m || "BLUR" in m -> "blur"
            "UNSHARP" in m || "SHARPEN" in m -> "sharpen"
            "GLO" in m || "GLOW" in m -> "glow"
            "TILE" in m -> "tiles"
            "VIBRANCE" in m -> "huesaturation"
            "COLOR BALANCE" in m -> "colorbalance"
            "EXPOSURE" in m -> "exposure"
            "INVERT" in m -> "invert"
            "TINT" in m -> "tint"
            "POSTERIZE" in m -> "posterize"
            "MOTION BLUR" in m || "DIRECTIONAL BLUR" in m -> "motionblur"
            "GLITCH" in m -> "glitch"
            else -> null
        }
    }
}
