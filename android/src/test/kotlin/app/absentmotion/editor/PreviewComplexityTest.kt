package app.absentmotion.editor

import app.absentmotion.model.Scene
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewComplexityTest {
    @Test fun nestedGroupsCountTowardPreviewCost() {
        val flat = Scene.parse(
            """<scene width="1080" height="1080" fps="60">${(1..12).joinToString("") { "<shape id=\"$it\" startTime=\"0\" endTime=\"999\" s=\".rect\"/>" }}</scene>""",
        )
        val nested = Scene.parse(
            buildString {
                append("""<scene width="1080" height="1080" fps="60"><shape id="1" startTime="0" endTime="999" s=".rect"/><embedScene id="2" startTime="0" endTime="999"><scene width="1080" height="1080" fps="60">""")
                for (i in 3..62) append("""<shape id="$i" startTime="0" endTime="999" s=".rect"><effect id="absent.tiles"/></shape>""")
                append("</scene></embedScene></scene>")
            },
        )
        assertTrue(previewComplexityDivisor(nested) > previewComplexityDivisor(flat))
    }

    @Test fun hiddenLayersAreNotChargedForPreview() {
        val hidden = Scene.parse(
            """<scene width="1080" height="1080" fps="60"><shape id="1" hidden="true" startTime="0" endTime="999" s=".rect">${
                (1..80).joinToString("") { "<effect id='absent.blur' />" }
            }</shape></scene>""",
        )
        assertTrue(previewComplexityDivisor(hidden) == 0)
    }

    @Test fun aHeavyEffectStackRaisesThePreviewDivisor() {
        val scene = Scene.parse(
            """<scene width="1080" height="1080" fps="60"><shape id="1" startTime="0" endTime="999" s=".rect">${
                (1..40).joinToString("") { "<effect id='absent.blur' />" }
            }</shape></scene>""",
        )
        assertTrue(previewComplexityDivisor(scene) >= 4)
    }

    @Test fun playbackNeverAsksForMoreFramesThanThePhoneCanShow() {
        assertTrue(previewFpsStep(60, 5.0, 0) == 1)
        assertTrue(previewFpsStep(120, 5.0, 0) == 2)
        assertTrue(previewFpsStep(60, 30.0, 0) == 2)
        assertTrue(previewFpsStep(120, 80.0, 12) >= 4)
        assertTrue(previewFpsStep(24, 100.0, 12) == 1)
    }
}
