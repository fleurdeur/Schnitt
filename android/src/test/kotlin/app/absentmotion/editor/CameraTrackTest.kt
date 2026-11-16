package app.absentmotion.editor

import app.absentmotion.model.PropRef
import app.absentmotion.model.Pt
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.model.valueAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTrackTest {
    @Test fun homographyRecoversATranslation() {
        val from = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(0f, 100f))
        val to = from.map { Pt(it.x + 25f, it.y - 10f) }
        val h = homography(from, to)
        assertNotNull(h)
        val hh = h!!.h
        assertEquals(1.0, hh[0], 1e-6)
        assertEquals(1.0, hh[4], 1e-6)
        assertEquals(25.0, hh[2], 1e-4)
        assertEquals(-10.0, hh[5], 1e-4)
    }

    @Test fun expressionsBakeIntoOrdinaryKeyframes() {
        val scene = Scene.parse(
            """<scene width="100" height="100" fps="10" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">""" +
                """<transform><tilt value="0,0"/><location value="0,0,0"/></transform></shape></scene>"""
        )
        val baked = scene.bakeExpression(1, PropRef.Tilt, "sin(time) * 10, 20", "tilt")
        val layer = baked.layer(1)!!
        assertNotNull(layer.element(PropRef.Tilt)?.children?.firstOrNull { it.name == "expression" })
        val keys = layer.element(PropRef.Tilt)!!.keyframes
        assertTrue(keys.size > 5)
        assertEquals("tilt", layer.element(PropRef.Tilt)!!.children.first { it.name == "expression" }["label"])
        assertTrue(layer.element(PropRef.Tilt)!!.valueAt(0.5f)!!.contains(","))
    }

    @Test fun expressionFunctionsAndVariablesAreSafe() {
        assertEquals(10f, Expressions.evaluate("clamp(20, 0, 10)", 0f, 0f, 0, 30, 100, 100, 0, 0f), 1e-5f)
        assertEquals(2f, Expressions.evaluate("1 + 1", 0f, 0f, 0, 30, 100, 100, 0, 0f), 1e-5f)
        assertTrue(Expressions.evaluate("wiggle(2, 10)", 1f, 0f, 0, 30, 100, 100, 0, 0f).isFinite())
        assertEquals(0f, Expressions.evaluate("does_not_exist", 0f, 0f, 0, 30, 100, 100, 0, 0f), 1e-5f)
    }
}
