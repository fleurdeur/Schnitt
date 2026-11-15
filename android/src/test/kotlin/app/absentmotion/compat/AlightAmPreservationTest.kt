package app.absentmotion.compat

import app.absentmotion.effects.AmEffectImporter
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlightAmPreservationTest {
    @Test fun importedAmEffectsKeepTheirOwnPropertiesAndKeyframes() {
        val pack = File("../shared/assets/effects")
        val base = EffectCatalog.parse { File(pack, it).inputStream() }
        val am = listOf("tile.xml", "dblur.xml").mapNotNull { name ->
            File("../shared/assets/am-effects/$name").takeIf { it.isFile }?.readText()?.let { AmEffectImporter.parse(it) }
        }
        val catalog = base.copy(definitions = base.definitions + am)
        val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)
        val scene = Scene.parse("""<scene title="x" width="1080" height="1080" fps="60" totalTime="2000" bgcolor="#00000000"><shape id="1" startTime="0" endTime="2000"><effect id="com.alightcreative.effects.tile"><property name="scale" type="float"><kf t="0.1" v="1.5"/><kf t="0.9" v="2.5" e="cubicBezier 0.0 0.58 1.0 1.0"/></property><property name="phase" type="float" value="0.25"/><property name="mirror" type="bool" value="true"/></effect><effect id="com.alightcreative.effects.dblur"><property name="strength" type="float"><kf t="0.2" v="0.1"/><kf t="0.8" v="0.6"/></property><property name="angle" type="float" value="90"/></effect></shape></scene>""")
        val effects = migration.migrate(scene).scene.node.children.first { it.name == "shape" }.children.filter { it.name == "effect" }
        val tiles = effects.first { it["id"] == "com.alightcreative.effects.tile" }
        val scale = tiles.children.first { it["name"] == "scale" }
        assertEquals(listOf("1.5", "2.5"), scale.children.filter { it.name == "kf" }.map { it["v"] })
        assertEquals("cubicBezier 0.0 0.58 1.0 1.0", scale.children.filter { it.name == "kf" }[1]["e"])
        assertEquals("0.25", tiles.children.first { it["name"] == "phase" }["value"])
        assertEquals("true", tiles.children.first { it["name"] == "mirror" }["value"])
        val blur = effects.first { it["id"] == "com.alightcreative.effects.dblur" }
        assertEquals(listOf("0.1", "0.6"), blur.children.first { it["name"] == "strength" }.children.filter { it.name == "kf" }.map { it["v"] })
        assertTrue(blur.children.first { it["name"] == "angle" }["value"] == "90")
    }
}
