package app.absentmotion.effects

import app.absentmotion.TestData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AmEffectLibraryTest {
    private val folder = TestData.project.resolve("shared/assets/am-effects")

    private fun strings(): Map<String, String> {
        val json = JSONObject(File(folder, "strings.json").readText())
        return json.keys().asSequence().associateWith { json.optString(it) }
    }

    @Test fun everyBundledDefinitionParsesWithItsRealName() {
        val words = strings()
        val files = folder.listFiles { f -> f.extension == "xml" }.orEmpty()
        assertEquals(315, files.size)
        val effects = files.map { file ->
            val parsed = AmEffectImporter.parse(file.readText(), words)
            assertNotNull(file.name, parsed)
            parsed!!
        }
        assertEquals("duplicate ids", effects.size, effects.map { it.id }.toSet().size)
        assertEquals("duplicate keys", effects.size, effects.map { it.key }.toSet().size)
        assertTrue(effects.all { it.name.isNotBlank() && !it.name.startsWith("@") })
        assertTrue(effects.all { it.description.isNotBlank() && !it.description.startsWith("@") })
        assertTrue(effects.all { it.alsoIn.contains("external") })
        assertTrue(effects.all { it.available })
        assertTrue(effects.any { it.name == "Bend" })
        assertTrue(effects.any { it.name == "Glass" })
        assertTrue(effects.flatMap { it.params }.isNotEmpty())
        val art = TestData.project.resolve("shared/assets/effects")
        assertTrue(effects.all { File(art, it.thumbnail).isFile })
    }


    @Test fun bundledCopiesOfNativeEffectsAreNotListedTwice() {
        val words = strings()
        val catalog = EffectCatalog.parse { File(TestData.project.resolve("shared/assets/effects"), it).inputStream() }
        val effects = folder.listFiles { f -> f.extension == "xml" }.orEmpty().mapNotNull { AmEffectImporter.parse(it.readText(), words) }
        for (name in listOf("Directional Blur", "Motion Blur", "Rays", "Cube", "Grid", "Page Curl")) {
            val imported = effects.first { it.name == name }
            assertTrue("$name should prefer the built-in effect", AmEffectImporter.duplicateOfNative(imported, catalog))
        }
        val unique = effects.first { def -> catalog.definitions.none { AmEffectImporter.effectName(it.name) == AmEffectImporter.effectName(def.name) } }
        assertFalse(AmEffectImporter.duplicateOfNative(unique, catalog))
    }

    @Test fun collidingParameterNamesStayDistinctFromImageSizeAndTime() {
        val words = strings()
        val pixelate = AmEffectImporter.parse(File(folder, "pixelate.xml").readText(), words)!!
        assertTrue(pixelate.params.any { it.id == "size" })
        assertTrue(pixelate.sksl!!.contains("uniform float size;"))
        assertTrue(pixelate.sksl!!.contains("uniform float2 amSize;"))
        assertTrue(!pixelate.sksl!!.contains("uniform float2 size;"))
    }

    @Test fun alightEffectsKeepTheirOwnControlsAndShader() {
        val words = strings()
        val files = folder.listFiles { f -> f.extension == "xml" }.orEmpty()
        val effects = files.mapNotNull { AmEffectImporter.parse(it.readText(), words) }
        val byName = effects.groupBy { it.name }.mapValues { it.value.first() }
        val tiles = byName.getValue("Tiles")
        assertEquals(listOf("scale", "phase", "mirror", "vertoffs", "angle"), tiles.params.map { it.id })
        assertTrue(tiles.sksl!!.contains("AcLayerNorm"))
        assertTrue(tiles.sksl!!.contains("acLayerToScreen"))
        assertTrue("Tiles must map filter pixels through toLayer once", tiles.sksl!!.contains("float3 h = toLayer * float3(p, 1.0);"))
        assertFalse("Tiles must not normalize filter pixels before toLayer", tiles.sksl!!.contains("float2 n = (p - screen.xy)"))
        val blur = byName.getValue("Directional Blur")
        assertEquals(listOf("strength", "angle"), blur.params.map { it.id })
        assertTrue(blur.sksl!!.contains("uniform float strength;"))
        // Alight's inputImg.size is the layer texture size. Mapping it to the
        // project size made blur/edge/outline radii wrong on small layers.
        val directional = AmEffectImporter.parse(File(folder, "dblur.xml").readText(), words)!!
        assertTrue(directional.sksl?.contains("acLayerSize") == true)
        assertFalse(directional.sksl?.contains("getTexSize(inputImg.size)") == true)
        val ripple = byName.getValue("Circular Ripple")
        assertTrue(ripple.params.any { it.id == "centerPoint" })
        assertTrue(ripple.sksl!!.contains("uniform float2 centerPoint;"))
        val oscillate = byName.getValue("Oscillate")
        assertTrue(oscillate.native)
        assertTrue(oscillate.params.any { it.id == "freq" && it.kind == "float" })
        assertTrue(oscillate.params.any { it.id == "mag" && it.kind == "float" })
        assertTrue(oscillate.params.any { it.id == "angle" && it.kind == "angle" })
        val uniforms = Regex("""uniform\s+(?:shader|[A-Za-z0-9]+)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val internal = setOf("amSize", "amTime", "amDuration", "screen", "layer", "fromLayer", "toLayer", "acScreenSize",
            "acLayerSize", "acLayerCenter", "acLayerCenterNorm", "acLayerPivot", "acPreviewSize", "acLayerToScreen", "acScreenToLayer",
            "acLayerSizeNorm", "acVelocity", "acScaleVelocity", "acAngularVelocity", "acProjectSize", "acShowGuides",
            "orientation", "size", "time", "duration", "resolution", "coord")
        for (effect in effects) {
            val shader = effect.sksl ?: continue
            val names = uniforms.findAll(shader).map { it.groupValues[1] }.toList()
            assertEquals("${effect.name} duplicate uniforms", names.size, names.toSet().size)
            for (name in names) {
                if (name in internal || Regex("""uniform\s+shader\s+$name""").containsMatchIn(shader)) continue
                assertTrue("${effect.name} has uniform $name with no control", effect.params.any { it.id == name } || name.endsWith("Size"))
            }
        }
    }

    @Test fun everyDefinitionHasSaneControlsAndAFallbackShader() {
        val words = strings()
        val effects = folder.listFiles { f -> f.extension == "xml" }.orEmpty().mapNotNull { AmEffectImporter.parse(it.readText(), words) }
        for (effect in effects) {
            assertNotNull(effect.name, effect.fallback)
            assertTrue("${effect.name} is native and needs no shader", effect.native || effect.sksl?.isNotBlank() == true)
            val shader = effect.sksl ?: ""
            assertTrue("${effect.name} still has an unbounded float loop", !Regex("""for\s*\(\s*float""").containsMatchIn(shader))
            assertTrue("${effect.name} still has GLSL texture2D", !shader.contains("texture2D"))
            assertTrue("${effect.name} still has sampler2D", !shader.contains("sampler2D"))
            for (loop in Regex("""for\s*\(\s*int\s+\w+\s*=\s*[^;]+;\s*\w+\s*(?:<=|>=|<|>)\s*([^;]+);""").findAll(shader)) {
                assertTrue("${effect.name} still has an unbounded int loop", loop.groupValues[1].trim().matches(Regex("""[0-9.]+""")))
            }
            for (param in effect.params) {
                assertTrue("${effect.name}.${param.id}", param.id.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                assertTrue("${effect.name}.${param.id} label", param.label.isNotBlank() && !param.label.startsWith("@"))
                if (param.kind in setOf("float", "angle", "choice")) {
                    assertTrue("${effect.name}.${param.id} min", param.min.isFinite())
                    assertTrue("${effect.name}.${param.id} max", param.max.isFinite())
                    assertTrue("${effect.name}.${param.id} range", param.max > param.min)
                    assertTrue("${effect.name}.${param.id} step", param.step > 0f)
                }
            }
        }
    }
}
