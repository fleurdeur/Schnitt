package app.absentmotion.library

import app.absentmotion.R
import app.absentmotion.i18n.txt
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportReportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun settingsTheAppWritesItselfAreNotReportedAsUnknown() {
        val xml = """<scene width="100" height="100" fps="30" totalTime="1000" depthOrder="true">
            <shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect" scaleLinked="false" threeD="true" mixPieces="false" pathLayer="2"/>
            <shape id="2" startTime="0" endTime="1000" fillType="media" fillVideo="x://clip" loop="true" loopIn="0" noiseId="1" matteFrame="10,10" matteEdge="1"/>
            <embedScene id="3" startTime="0" endTime="1000" retimeLength="1000"><scene width="100" height="100" fps="30" totalTime="1000"/></embedScene>
            <model id="4" startTime="0" endTime="1000" src="x://phone" scaleLinked="false"/>
        </scene>"""
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for ((name, data) in listOf("project.xml" to xml, "manifest.json" to """{"project":"project.xml"}""")) { zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry() }
            }
        }.toByteArray()
        val report = Importer(ProjectStore(tmp.newFolder())).import { bytes.inputStream() }.single()
        assertEquals(null, report.sections.firstOrNull { it.first == txt(R.string.library_report_unknown) }?.second)
    }

    @Test fun replacingAnImportedEffectKeepsItsKeyframesAndProperties() {
        val store = ProjectStore(tmp.newFolder())
        val xml = """<scene width="100" height="100" fps="30" totalTime="1000">
            <shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
                <effect id="com.example.missing">
                    <property name="amount" type="float" value="0.5"><kf t="0" v="0.2"/><kf t="1" v="0.8"/></property>
                </effect>
            </shape>
        </scene>"""
        store.save("p1", xml)
        val blur = EffectDefinition("blur", "blur", "Blur", "blur", "", listOf(EffectParam("strength", "Strength", "float", "10")))
        assertEquals(1, store.remapEffects("p1", mapOf("com.example.missing" to blur.id), mapOf(blur.id to blur)))
        val changed = store.text("p1")!!
        assertEquals(true, changed.contains("id=\"${blur.id}\""))
        assertEquals(true, changed.contains("name=\"amount\""))
        assertEquals(true, changed.contains("<kf t=\"1\" v=\"0.8\""))
    }
}
