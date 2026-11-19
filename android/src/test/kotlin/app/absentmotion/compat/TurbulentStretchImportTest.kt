package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TurbulentStretchImportTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun convert(id: String, props: String): Pair<XmlNode, Set<String>> {
        val xml = """<scene width="1080" height="1080" fps="30" totalTime="2000">
            <shape id="1" startTime="0" endTime="1999" fillType="color" s=".rect">
              <effect id="$id" locallyApplied="false">$props</effect>
              <property name="size" type="vec2" value="100,100" />
            </shape></scene>"""
        val result = migration.migrate(Scene.parse(xml))
        return result.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" } to result.dropped
    }

    private fun XmlNode.value(name: String) = children.single { it.name == "property" && it["name"] == name }["value"]!!

    private fun props(scale: String, stretch: String) = """
        <property name="scale" type="float" value="$scale" />
        <property name="stretch" type="float" value="$stretch" />
    """

    @Test fun oldSignedStretchWidensOneAxisAtTheCurrentScale() {
        for (id in listOf("com.alightcreative.effects.turbulentdisplace", "com.alightcreative.effects.turbulentdisplace2",
                "com.alightcreative.effects.turbulence")) {
            val (effect, dropped) = convert(id, props("2", "-4"))
            assertEquals("$id stretch", "3.000000,1.000000", effect.value("stretch"))
            assertTrue("$id: $dropped", dropped.isEmpty())
            assertEquals("1.000000,3.000000", convert(id, props("2", "4")).first.value("stretch"))
        }
    }

    @Test fun newTwoAxisStretchIsKeptExactly() {
        for (id in listOf("com.alightcreative.effects.turbulentdisplace3", "com.alightcreative.effects.turbulence2")) {
            val (effect, dropped) = convert(id, """
                <property name="scale" type="float" value="2" />
                <property name="stretch" type="vec2" value="0.25,6.5" />
            """)
            assertEquals("$id stretch", "0.250000,6.500000", effect.value("stretch"))
            assertTrue("$id: $dropped", dropped.isEmpty())
        }
    }

    @Test fun stretchKeyframesKeepTheirTimesAndBecomeTwoAxisValues() {
        val (effect, dropped) = convert("com.alightcreative.effects.turbulentdisplace", """
            <property name="scale" type="float" value="2" />
            <property name="stretch" type="float">
              <kf t="0" v="-4" />
              <kf t="1" v="2" />
            </property>
        """)
        val stretch = effect.children.single { it.name == "property" && it["name"] == "stretch" }
        assertEquals(listOf(0f, 1f), stretch.keyframes.map { it.t })
        assertEquals(listOf("3.000000,1.000000", "1.000000,2.000000"), stretch.keyframes.map { it.v })
        assertTrue(dropped.isEmpty())
    }

    @Test fun anAnimatedScaleIsUsedAtEachStretchKeyframe() {
        val (effect, dropped) = convert("com.alightcreative.effects.turbulentdisplace", """
            <property name="scale" type="float">
              <kf t="0" v="2" />
              <kf t="1" v="4" />
            </property>
            <property name="stretch" type="float">
              <kf t="0" v="2" />
              <kf t="1" v="2" />
            </property>
        """)
        val stretch = effect.children.single { it.name == "property" && it["name"] == "stretch" }
        assertEquals(listOf("1.000000,2.000000", "1.000000,1.500000"), stretch.keyframes.map { it.v })
        assertTrue(dropped.isEmpty())
    }
    @Test fun oldAxisChoicesUseTheCurrentDirectionOrder() {
        for (id in listOf("com.alightcreative.effects.turbulentdisplace", "com.alightcreative.effects.turbulentdisplace3")) {
            for ((old, now) in listOf("0" to "1.000000", "1" to "2.000000", "2" to "0.000000", "3" to "3.000000")) {
                val (effect, _) = convert(id, """<property name="direction" type="int" value="$old" />""")
                assertEquals("$id $old", now, effect.value("direction"))
            }
        }
    }
}
