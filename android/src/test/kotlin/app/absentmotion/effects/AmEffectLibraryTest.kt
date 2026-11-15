package app.absentmotion.effects

import app.absentmotion.TestData
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    @Test fun collidingParameterNamesStayDistinctFromImageSizeAndTime() {
        val words = strings()
        val pixelate = AmEffectImporter.parse(File(folder, "pixelate.xml").readText(), words)!!
        assertTrue(pixelate.params.any { it.id == "size" })
        assertTrue(pixelate.sksl!!.contains("uniform float size;"))
        assertTrue(pixelate.sksl!!.contains("uniform float2 amSize;"))
        assertTrue(!pixelate.sksl!!.contains("uniform float2 size;"))
    }

    @Test fun brokenAlightNamesResolveToNativeSchnittShaders() {
        val words = strings()
        val base = TestData.project.resolve("shared/assets/effects")
        val builtIns = EffectCatalog.parse("en") { File(base, it).inputStream() }.definitions
        assertTrue(builtIns.size > 100)
        val files = folder.listFiles { f -> f.extension == "xml" }.orEmpty()
        val effects = files.mapNotNull { AmEffectImporter.parse(it.readText(), words) }
        val bound = effects.map { EffectAlias.bind(it, builtIns) }
        val aliased = bound.filter { it.description.contains("Schnitt ") }
        assertTrue("only ${aliased.size} effects aliased", aliased.size >= 50)
        val listed = listOf(
            "Bend", "Circular Ripple", "Chromatic Zoom Blur", "Clouds", "Colorize", "Cube", "Curl", "Dark Glow",
            "Directional Blur", "Dots", "Edge Glow", "Electric Edges", "Feather", "Flip Layer", "Fractal Warp",
            "Glass", "Gradient Overlay", "Halftone Dots", "Halftone Lines", "Lens Blur", "Kaleidoscope", "Light Glow",
            "Long Shadow", "Luma Key", "Mirror", "Motion Blur", "Noise", "Omino Diffusion+", "Palette Map",
            "Pinch/Bulge", "Pixelate", "Radial Rays", "Radial Shadow", "Radial Wipe", "Rays", "Squeeze", "Starfield",
            "Stripes", "Tile Shift", "Tile Rotate", "Tiles", "Wave Warp", "Tunnel",
        )
        for (name in listed) {
            val source = effects.firstOrNull { it.name == name }
            assertNotNull("missing Alight effect $name", source)
            val effect = EffectAlias.bind(source!!, builtIns)
            assertTrue("$name still uses a placeholder", effect.description.contains("Schnitt "))
            assertTrue("$name has no shader", effect.sksl?.contains("uniform float2 amSize") == true)
            assertEquals("$name duplicate controls", effect.params.size, effect.params.map { it.id }.toSet().size)
        }
        val catalog = EffectCatalog(builtIns + bound)
        val external = catalog.listed("external")
        assertEquals("duplicate names in external", external.size, external.map { it.name.trim().lowercase() }.toSet().size)
        val uniforms = Regex("""uniform\s+(?:shader|[A-Za-z0-9]+)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        for (effect in bound) {
            val shaders = listOfNotNull(effect.sksl) + effect.passes.map { it.sksl }
            for (shader in shaders) {
                val names = uniforms.findAll(shader).map { it.groupValues[1] }.toList()
                assertEquals("${effect.name} duplicate uniforms $names", names.size, names.toSet().size)
                val inputs = Regex("""uniform\s+shader\s+([A-Za-z_][A-Za-z0-9_]*)""").findAll(shader).map { it.groupValues[1] }.toSet()
                val internal = setOf("amSize", "amTime", "amDuration", "screen", "layer", "fromLayer", "toLayer", "orient",
                    "duration", "paletteSize", "paletteSpread", "swatches", "glyphCount", "glyphColumns", "glyphCell",
                    "visible", "beat", "beatTime", "beatIndex", "thresholds", "palette", "glyphs", "extent")
                for (name in names) {
                    if (name in inputs || name in internal) continue
                    assertTrue("${effect.name} has uniform $name with no control", effect.params.any { it.id == name })
                }
            }
        }
        val pixelate = bound.first { it.name == "Pixelate" }
        assertTrue(pixelate.sksl!!.contains("uniform float size;"))
        assertTrue(!pixelate.sksl!!.contains("uniform float blockSize;"))
    }

    @Test fun everyDefinitionHasSaneControlsAndAFallbackShader() {
        val words = strings()
        val effects = folder.listFiles { f -> f.extension == "xml" }.orEmpty().mapNotNull { AmEffectImporter.parse(it.readText(), words) }
        for (effect in effects) {
            assertNotNull(effect.name, effect.fallback)
            assertTrue(effect.name, effect.sksl?.isNotBlank() == true)
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
