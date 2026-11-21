package app.absentmotion.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression coverage for the XML shapes used by community Alight packs. */
class CommunityEffectImportTest {
    private fun effect(id: String, passes: String, shaders: String): String = """
        <?xml version='1.0' encoding='UTF-8' ?>
        <effect id="$id" name="Test Effect" category="distort">
            <params>
                <texture id="inputImg" srcType="content" />
                <texture id="xcipher" srcType="buffer" />
            </params>
            $passes
            $shaders
        </effect>
    """.trimIndent()


    @Test fun scriptOnlyEffectsAreNativeAndKeepTheirRealControls() {
        val root = File("../shared/assets/am-effects")
        val grow = AmEffectImporter.parse(File(root, "grow-parts.xml").readText())!!
        assertTrue(grow.native)
        assertTrue(grow.applyToChildren)
        assertEquals(listOf("progress", "depth"), grow.params.map { it.id })

        val morph = AmEffectImporter.parse(File(root, "morph-path.xml").readText())!!
        assertTrue(morph.native)
        assertEquals(listOf("progress"), morph.params.map { it.id })

        val shake = AmEffectImporter.parse(File(root, "shake-parts.xml").readText())!!
        assertTrue(shake.native)
        assertTrue(shake.applyToChildren)
        assertEquals(listOf("magnitude", "evolution"), shake.params.map { it.id })

        val manual = AmEffectImporter.parse(File(root, "vshake.xml").readText())!!
        assertTrue(manual.native)
        assertTrue(manual.params.any { it.id == "xmag" })
        assertTrue(AmEffectImporter.parse(File(root, "vshakenew.xml").readText())!!.native)
        assertTrue(AmEffectImporter.parse(File(root, "transform2.xml").readText())!!.native)
        assertTrue(AmEffectImporter.parse(File(root, "skexnxx15.xml").readText())!!.native)
        assertTrue(AmEffectImporter.parse(File(root, "facemotion.xml").readText())!!.native)
        assertTrue(AmEffectImporter.parse(File(root, "perlindisplace.xml").readText())!!.native)
    }

    @Test fun scriptlessPackEffectsUseTheirNativeImplementation() {
        listOf(
            "com.alightcreative.effects.skexnxxposeshake" to """<spinner id="Xmag" default="25" />""",
            "com.alightcreative.effects.drawing.strokecolor" to """<color id="color" default="#FF2D1EF6" />""",
            "com.alightcreative.effects.textspacing" to """<spinner id="letterspacing" default="0" />""",
            "com.alightcreative.effects.freepalette" to """<color id="color1" default="#FF1E2239" />""",
        ).forEach { (id, param) ->
            val parsed = AmEffectImporter.parse(
                """<effect id="$id" name="Test" category="transform"><params>$param</params></effect>"""
            )
            assertNotNull(id, parsed)
            assertTrue("$id should be native", parsed!!.native)
            assertEquals("$id should not get a fallback shader", null, parsed.sksl)
        }
    }

    @Test fun openAndSelfClosingPassTagsKeepEveryStage() {
        val xml = effect(
            "com.alightcreative.effects.testpasses",
            """<passes><pass target="xcipher" /><pass /></passes>""",
            """<shader type="fragment"><![CDATA[
                void main() {
                    if (acPass == 0) { gl_FragColor = texture2D(inputImg.texture, acScreenNorm); }
                    else if (acPass == 1) { gl_FragColor = vec4(0.25, 0.5, 0.75, 1.0); }
                }
            ]]></shader>""",
        )
        val parsed = AmEffectImporter.parse(xml)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.passes.size)
        assertEquals("xcipher", parsed.passes[0].target)
        assertTrue(parsed.passes[0].sksl.contains("uniform shader image"))
        assertTrue(parsed.passes[1].sksl.contains("0.25"))
    }

    @Test fun fragmentStageIsPreferredOverAVertexStage() {
        val xml = effect(
            "com.alightcreative.effects.testfragment",
            "",
            """
            <shader type="vertex"><![CDATA[
                varying float vertexOnly;
                void main() { vertexOnly = 1.0; }
            ]]></shader>
            <shader type="fragment"><![CDATA[
                void main() { gl_FragColor = vec4(0.5, 0.25, 0.125, 1.0); }
            ]]></shader>
            """,
        )
        val parsed = AmEffectImporter.parse(xml)
        assertNotNull(parsed)
        val shader = parsed!!.sksl.orEmpty()
        assertTrue(shader.contains("0.5"))
        assertFalse(shader.contains("vertexOnly"))
    }

    @Test fun whitespaceAfterCdataStillFindsTheShader() {
        val xml = effect(
            "com.alightcreative.effects.testcdata",
            "",
            """<shader type="fragment"><![CDATA[void main() { gl_FragColor = vec4(0.1, 0.2, 0.3, 1.0); }]]>   </shader>""",
        )
        val parsed = AmEffectImporter.parse(xml)
        assertNotNull(parsed)
        assertTrue(parsed!!.sksl.orEmpty().contains("0.1"))
    }

    @Test fun hexEncodedEffectIdIsDecoded() {
        val xml = effect(
            "636f6d2e616c6967687463726561746976652e656666656374732e68657874657374",
            "",
            """<shader type="fragment"><![CDATA[void main() { gl_FragColor = image.eval(coord); }]]></shader>""",
        )
        val parsed = AmEffectImporter.parse(xml)
        assertNotNull(parsed)
        assertEquals("com.alightcreative.effects.hextest", parsed!!.id)
        assertTrue(parsed.key.startsWith("am-hextest-"))
    }

    @Test fun aPassThatNamesItsOwnEffectDoesNotDiscardTheEffect() {
        val xml = effect(
            "com.alightcreative.effects.testfeedback",
            """<passes><pass target="xcipher" effect="636f6d2e616c6967687463726561746976652e656666656374732e74657374666565646261636b" /><pass /></passes>""",
            """<shader type="fragment"><![CDATA[void main() { gl_FragColor = image.eval(coord); }]]></shader>""",
        )
        val parsed = AmEffectImporter.parse(xml)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.passes.size)
    }
}
