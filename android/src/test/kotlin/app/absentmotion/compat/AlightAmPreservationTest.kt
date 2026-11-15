package app.absentmotion.compat

import app.absentmotion.effects.AmEffectImporter
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlightAmPreservationTest {
    @Test fun curatedAliasesKeepImportedKeyframesWhenTheBuiltInWins() {
        val pack = File("../shared/assets/effects")
        val base = EffectCatalog.parse { File(pack, it).inputStream() }
        val am = listOf("tile.xml", "dblur.xml").mapNotNull { name ->
            File("../shared/assets/am-effects/$name").takeIf { it.isFile }?.readText()?.let { AmEffectImporter.parse(it) }
        }
        val catalog = base.copy(definitions = base.definitions + am)
        val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)
        val scene = Scene.parse("""<scene title="x" width="1080" height="1080" fps="60" totalTime="2000" bgcolor="#00000000"><shape id="1" startTime="0" endTime="2000"><effect id="com.alightcreative.effects.tile"><property name="scale" type="float"><kf t="0.1" v="1.5"/><kf t="0.9" v="2.5" e="cubicBezier 0.0 0.58 1.0 1.0"/></property><property name="phase" type="float" value="0.25"/><property name="mirror" type="bool" value="true"/></effect><effect id="com.alightcreative.effects.dblur"><property name="strength" type="float"><kf t="0.2" v="0.1"/><kf t="0.8" v="0.6" e="cubicBezier 0.0 0.58 1.0 1.0"/></property><property name="angle" type="float" value="90"/></effect></shape></scene>""")
        val migrationResult = migration.migrate(scene)
        val effects = migrationResult.scene.node.children.first { it.name == "shape" }.children.filter { it.name == "effect" }
        val tiles = effects.first { it["id"] == "absent.tiles" }
        val zoom = tiles.children.first { it["name"] == "zoom" }
        assertEquals(listOf("1.500000", "2.500000"), zoom.children.filter { it.name == "kf" }.map { it["v"] })
        assertEquals("cubicBezier 0.0 0.58 1.0 1.0", zoom.children.filter { it.name == "kf" }[1]["e"])
        assertEquals("0.250000", tiles.children.first { it["name"] == "offset" }["value"])
        assertEquals("true", tiles.children.first { it["name"] == "mirror" }["value"])
        val blur = effects.first { it["id"] == "absent.directionalblur" }
        val length = blur.children.first { it["name"] == "blurLength" }
        assertEquals(2, length.children.filter { it.name == "kf" }.size)
        assertEquals("cubicBezier 0.0 0.58 1.0 1.0", length.children.filter { it.name == "kf" }[1]["e"])
        assertTrue(length.children.filter { it.name == "kf" }.map { it["v"]!!.toFloat() }.zipWithNext().all { (a, b) -> b > a })
        assertTrue(migrationResult.converted.keys.any { it.startsWith("tile →") })
        assertTrue(migrationResult.converted.keys.any { it.startsWith("dblur →") })
    }

}
