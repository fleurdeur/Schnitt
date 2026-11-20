package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlightPivotImportTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun migrate(xml: String) = migration.migrate(Scene.parse(xml)).scene.node

    private fun transform(node: XmlNode) = node.children.single { it.name == "transform" }

    private fun value(node: XmlNode, name: String) = transform(node).children.single { it.name == name }["value"]!!

    @Test fun aStaticPivotIsFoldedIntoLocationOnce() {
        val out = migrate("""<scene width="1080" height="1080" fps="30" totalTime="2000" am="com.alightcreative.motion/5.0">
            <shape id="1" startTime="0" endTime="1999" fillType="color" s=".rect">
              <transform><location value="540,400,0"/><pivot value="100,-50"/><scale value="2,2"/></transform>
              <property name="size" type="vec2" value="10,10" />
            </shape></scene>""")
        val shape = out.children.single { it.name == "shape" }
        assertEquals("640.000000,350.000000,0.000000", value(shape, "location"))
        assertFalse(transform(shape).children.single { it.name == "pivot" }["value"].isNullOrBlank())
    }

    @Test fun nestedScenesAndTheirLayersAreAlignedToo() {
        val out = migrate("""<scene width="1080" height="1920" fps="30" totalTime="2000" am="com.alightcreative.motion/5.0">
            <embedScene id="1" startTime="0" endTime="1999">
              <transform><location value="540,960,0"/><pivot value="20,30"/></transform>
              <scene width="400" height="200" fps="30" totalTime="2000">
                <shape id="2" startTime="0" endTime="1999" fillType="color" s=".circle">
                  <transform><location value="200,100,0"/><pivot value="-5,10"/></transform>
                  <property name="size" type="vec2" value="50,50" />
                </shape>
              </scene>
            </embedScene></scene>""")
        val group = out.children.single { it.name == "embedScene" }
        assertEquals("560.000000,990.000000,0.000000", value(group, "location"))
        val inner = group.children.single { it.name == "scene" }.children.single { it.name == "shape" }
        assertEquals("195.000000,110.000000,0.000000", value(inner, "location"))
    }

    @Test fun aKeyedPivotGetsMatchingLocationKeys() {
        val out = migrate("""<scene width="1080" height="1080" fps="30" totalTime="2000" am="com.alightcreative.motion/5.0">
            <shape id="1" startTime="0" endTime="2000" fillType="color" s=".rect">
              <transform>
                <location value="100,100,0"/>
                <pivot><kf t="0" v="0,0"/><kf t="1" v="20,-10"/></pivot>
              </transform>
              <property name="size" type="vec2" value="10,10" />
            </shape></scene>""")
        val location = transform(out.children.single { it.name == "shape" }).children.single { it.name == "location" }
        assertEquals(listOf(0f, 1f), location.keyframes.map { it.t })
        assertEquals(listOf("100.000000,100.000000,0.000000", "120.000000,90.000000,0.000000"), location.keyframes.map { it.v })
    }

    @Test fun aGroupWithoutItsOwnCanvasInheritsTheProjectSize() {
        val out = migrate("""<scene width="1080" height="1920" fps="30" totalTime="2000" am="com.alightcreative.motion/5.0">
            <embedScene id="1" startTime="0" endTime="1999">
              <transform><location value="540,960,0"/></transform>
              <scene fps="30" totalTime="2000">
                <embedScene id="2" startTime="0" endTime="1999">
                  <scene fps="30" totalTime="2000"><shape id="3" startTime="0" endTime="1999" fillType="color"/></scene>
                </embedScene>
              </scene>
            </embedScene></scene>""")
        val group = out.children.single { it.name == "embedScene" }
        val inner = group.children.single { it.name == "scene" }
        assertEquals("1080", inner["width"])
        assertEquals("1920", inner["height"])
        val nested = inner.children.single { it.name == "embedScene" }.children.single { it.name == "scene" }
        assertEquals("1080", nested["width"])
        assertEquals("1920", nested["height"])
    }

    @Test fun camerasAreNotShiftedByTheirPivot() {
        val out = migrate("""<scene width="1080" height="1080" fps="30" totalTime="2000" am="com.alightcreative.motion/5.0">
            <camera id="1" startTime="0" endTime="2000">
              <transform><location value="540,540,-800"/><pivot value="10,20"/></transform>
            </camera></scene>""")
        assertEquals("540,540,-800", value(out.children.single { it.name == "camera" }, "location"))
    }

    @Test fun ourOwnScenesKeepTheirPivotSpace() {
        val out = migrate("""<scene width="1080" height="1080" fps="30" totalTime="2000">
            <shape id="1" startTime="0" endTime="2000" fillType="color" s=".rect">
              <transform><location value="540,400,0"/><pivot value="100,-50"/></transform>
              <property name="size" type="vec2" value="10,10" />
            </shape></scene>""")
        assertEquals("540,400,0", value(out.children.single { it.name == "shape" }, "location"))
    }
}
