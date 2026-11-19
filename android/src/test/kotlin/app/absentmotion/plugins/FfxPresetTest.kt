package app.absentmotion.plugins

import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfxPresetTest {
    @Test fun readsAfterEffectsEffectAndScalarValues() {
        val bytes = preset()
        val parsed = FfxPreset.parse(bytes)
        assertNotNull(parsed)
        assertEquals(listOf("Turbulent Displace", "Brightness & Contrast"), parsed!!.effects.take(2))
        assertTrue(parsed.scalars.any { it.name == "Amount" && it.value == 25.0 })
        assertTrue(parsed.scalars.any { it.name == "Size" && it.value == 1.5 })
    }

    @Test fun mapsSavedValuesOntoTheMatchingBuiltInEffect() {
        val folder = kotlin.io.path.createTempDirectory("ffx-test").toFile()
        try {
            folder.resolve("preset.ffx").writeBytes(preset())
            val plugin = Plugin("ffx", "Imported FFX", "1.0", PluginKind.Effect, folder.path, "preset.ffx", true, "", listOf("turbulentdisplace"), "file")
            val definition = EffectDefinition(
                id = "turbulentdisplace", key = "turbulentdisplace", name = "Turbulent Displace", category = "distort", description = "",
                params = listOf(
                    EffectParam("amount", "Amount", "float", "0.5", 0f, 2f, 0.01f),
                    EffectParam("size", "Size", "float", "0.5", 0f, 2f, 0.01f),
                ),
            )
            val values = FfxPreset.mappedFor(plugin, definition)
            assertEquals("0.250000", values["amount"])
            assertEquals("1.500000", values["size"])
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun preset(): ByteArray {
        val tdsn = utf8Chunk("Turbulent Displace") + utf8Chunk("Brightness & Contrast")
        val params = param("Amount", 25.0) + param("Size", 1.5)
        val list = ByteArrayOutputStream().apply {
            write("besc".toByteArray())
            write(tdsn)
            write(params)
        }.toByteArray()
        val body = "FaFX".toByteArray() + chunk("LIST", list)
        return chunk("RIFX", body)
    }

    private fun param(name: String, value: Double): ByteArray {
        val payload = ByteArray(148)
        val bytes = name.toByteArray()
        bytes.copyInto(payload, 16, 0, minOf(bytes.size, 31))
        ByteBuffer.wrap(payload, 56, 8).order(ByteOrder.BIG_ENDIAN).putDouble(value)
        return chunk("pard", payload)
    }

    private fun utf8Chunk(text: String): ByteArray {
        val bytes = text.toByteArray()
        val payload = ByteArrayOutputStream().apply {
            write("Utf8".toByteArray())
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array())
            write(bytes)
        }.toByteArray()
        return chunk("tdsn", payload)
    }

    private fun chunk(id: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray())
        write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array())
        write(payload)
    }.toByteArray()
}
