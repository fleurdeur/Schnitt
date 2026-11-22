package app.absentmotion.extensions

import androidx.compose.ui.graphics.Color
import app.absentmotion.model.Scene
import app.absentmotion.ui.theme.Palette
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionSystemTest {
    private val manifest = """
        {"schema":1,"id":"auto-scroll","name":"Auto Scroll","version":"1.0.0","author":"fleurdeur",
         "category":"behavior","capabilities":["timeline.follow"],
         "properties":[
           {"id":"enabled","label":"Follow","type":"toggle","default":true},
           {"id":"mode","label":"Follow","type":"choice","options":["Playing","Selected"],"values":["playing","selected"],"default":"playing"},
           {"id":"speed","label":"Speed","type":"slider","default":1.0,"min":0.25,"max":3.0,"step":0.05}
         ]}
    """.trimIndent()

    @Test fun manifestDeclaresTypedProperties() {
        val parsed = ExtensionManifest.parse(JSONObject(manifest))
        assertEquals(ExtensionCategory.Behavior, parsed.category)
        assertTrue("timeline.follow" in parsed.capabilities)
        assertEquals("playing", parsed.property("mode")!!.choice(null))
        assertEquals(1f, parsed.property("speed")!!.number(null))
        assertEquals(3f, parsed.property("speed")!!.number("900"))
    }

    @Test fun rejectsUnknownCategoryAndUnsafeCapability() {
        assertFalse(runCatching { ExtensionManifest.parse(JSONObject(manifest.replace("behavior", "javascript"))) }.isSuccess)
        assertFalse(runCatching { ExtensionManifest.parse(JSONObject(manifest.replace("timeline.follow", "files.read"))) }.isSuccess)
    }

    @Test fun rejectsArchiveTraversalAndExecutables() {
        assertNull(ExtensionPaths.safeEntry("../escape"))
        assertNull(ExtensionPaths.safeEntry("/absolute"))
        assertNull(ExtensionPaths.safeEntry("icons/../../escape"))
        assertEquals("icons/icons.json", ExtensionPaths.safeEntry("icons/icons.json"))
        assertFalse(ExtensionPaths.allowed("classes.dex"))
        assertFalse(ExtensionPaths.allowed("lib/evil.so"))
        assertFalse(ExtensionPaths.allowed("panel.html"))
        assertTrue(ExtensionPaths.allowed("effects/my-effect.json"))
    }

    @Test fun bundledAutoScrollBundleIsSafeAndValid() {
        val zip = java.util.zip.ZipFile("../shared/assets/extensions/auto-scroll.sc")
        zip.use {
            val names = it.entries().asSequence().filterNot { e -> e.isDirectory }.map { e -> e.name }.toList()
            assertTrue(names.all { name -> ExtensionPaths.safeEntry(name) == name && ExtensionPaths.allowed(name) })
            val json = JSONObject(it.getInputStream(it.getEntry("manifest.json")).bufferedReader().use { r -> r.readText() })
            val manifest = ExtensionManifest.parse(json)
            assertEquals("auto-scroll", manifest.id)
            assertEquals(ExtensionCategory.Behavior, manifest.category)
            assertTrue(manifest.properties.any { p -> p.id == "speed" && p.kind == ExtensionPropertyKind.Slider })
            assertEquals("1.2.0", manifest.version)
            assertEquals("sweep", manifest.property("mode")!!.optionValues.last())
            assertEquals("playing", manifest.property("mode")!!.default)
            assertEquals("bottom", manifest.property("start")!!.default)
            assertTrue(manifest.properties.any { p -> p.id == "skipTop" && p.kind == ExtensionPropertyKind.Slider && p.default == "1.0" })
            assertTrue(manifest.properties.any { p -> p.id == "includeHidden" && p.kind == ExtensionPropertyKind.Toggle })
        }
    }

    @Test fun themeCssOnlyAppliesPaletteColours() {
        val palette = ExtensionCss.apply(Palette.Schnitt, """
            :root { --home-bg: #112233; --accent: #abc; --bad: url(https://example.com/x); --not-a-colour: red; }
        """.trimIndent())
        assertEquals(Color(0xFF112233), palette.HomeBg)
        assertEquals(Color(0xFFAABBCC), palette.Accent)
    }

    @Test fun autoScrollPicksTheNewestPlayingLayer() {
        val scene = Scene.parse("""
            <scene width="1080" height="1080" fps="30" totalTime="3000">
              <shape id="1" startTime="0" endTime="2000"/>
              <shape id="2" startTime="1000" endTime="3000"/>
              <text id="3" startTime="0" endTime="3000" hidden="true"/>
            </scene>
        """.trimIndent())
        assertEquals(1L, AutoScroll.activeLayerId(scene, 0, 30))
        assertEquals(2L, AutoScroll.activeLayerId(scene, 45, 30))
        assertEquals(2L, AutoScroll.activeLayerId(scene, 90, 30))
        assertNull(AutoScroll.activeLayerId(scene, 200, 30))
    }

    @Test fun autoScrollStartsAtTheBottomLayerAndCanSkipPinnedTopLayers() {
        val scene = Scene.parse("""
            <scene width="1080" height="1080" fps="30" totalTime="3000">
              <shape id="1" startTime="0" endTime="3000"/>
              <shape id="2" startTime="0" endTime="3000"/>
              <shape id="3" startTime="0" endTime="3000"/>
            </scene>
        """.trimIndent())
        assertEquals(1L, AutoScroll.activeLayerId(scene, 45, 30))
        assertEquals(3L, AutoScroll.activeLayerId(scene, 45, 30, start = "top"))
        assertEquals(1L, AutoScroll.activeLayerId(scene, 45, 30, skipTop = 1))
    }

    @Test fun sweepStartsAtTheBottomAndRisesToTheTop() {
        val scene = Scene.parse("""
            <scene width="1080" height="1080" fps="30" totalTime="3000">
              <shape id="1" startTime="0" endTime="3000"/>
              <shape id="2" startTime="0" endTime="3000"/>
              <shape id="3" startTime="0" endTime="3000"/>
            </scene>
        """.trimIndent())
        assertEquals(1L, AutoScroll.sweepLayerId(scene, 0, 30))
        assertEquals(2L, AutoScroll.sweepLayerId(scene, 45, 30))
        assertEquals(3L, AutoScroll.sweepLayerId(scene, 89, 30))
        assertEquals(3L, AutoScroll.sweepLayerId(scene, 0, 30, start = "top"))
    }

    @Test fun hiddenLayersCanBeIncludedOrSkipped() {
        val scene = Scene.parse("""
            <scene width="1080" height="1080" fps="30" totalTime="3000">
              <shape id="1" startTime="0" endTime="3000"/>
              <shape id="2" startTime="0" endTime="3000" hidden="true"/>
            </scene>
        """.trimIndent())
        assertEquals(1L, AutoScroll.activeLayerId(scene, 0, 30))
        assertEquals(1L, AutoScroll.activeLayerId(scene, 0, 30, includeHidden = true))
        assertEquals(2L, AutoScroll.activeLayerId(scene, 0, 30, start = "top", includeHidden = true))
    }
}
