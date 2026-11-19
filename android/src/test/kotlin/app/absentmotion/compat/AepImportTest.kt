package app.absentmotion.compat

import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AepImportTest {
    @Test fun readsCompositionLayerTransformAndKeyframes() {
        val scene = AepImport.read(project())
        assertEquals(1, scene.size)
        val parsed = Scene.parse(scene[0].toXml())
        assertEquals(1080, parsed.width)
        assertEquals(1080, parsed.height)
        assertEquals(30, parsed.fps)
        assertEquals(1000, parsed.totalTime)
        val layer = parsed.layers.single()
        assertEquals(0, layer.node["startTime"]?.toInt())
        assertEquals(1000, layer.node["endTime"]?.toInt())
        val transform = layer.node.child("transform")!!
        assertEquals(null, transform.child("location")!!["value"])
        assertEquals("0.500000,0.500000", transform.child("scale")!!["value"])
        assertEquals("0.250000", transform.child("opacity")!!["value"])
        val positionKeys = transform.child("location")!!.children.filter { it.name == "kf" }
        assertEquals(2, positionKeys.size)
        assertEquals("0.000000", positionKeys[0]["t"])
        assertEquals("1.000000", positionKeys[1]["t"])
        assertEquals("300.000000,400.000000,0.000000", positionKeys[1]["v"])
    }

    @Test fun readsTextContentFromDocument() {
        val top = AepImport.read(project(layer(type = 3, label = "Title", extra = textDocument("Hi\rThere"))))
        val text = Scene.parse(top.single().toXml()).layers.single()
        assertEquals("text", text.kind)
        assertEquals("Title", text.node["label"])
        assertEquals("Hi\nThere", text.node.child("content")?.text)
    }

    @Test fun readsPrecompositionAsNestedScene() {
        val outer = comp(10, "Outer", layer(id = 1, source = 11))
        val inner = comp(11, "Inner", layer(id = 2))
        val top = AepImport.read(projectOf(outer, inner))
        val scene = Scene.parse(top.first { it["title"] == "Outer" }.toXml())
        val nested = scene.layers.single().nested
        assertNotNull(nested)
        assertEquals(1, nested!!.layers.size)
    }

    private fun textDocument(text: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write('('.code)
        body.write(0xFE)
        body.write(0xFF)
        for (c in text) {
            body.write((c.code ushr 8) and 0xFF)
            body.write(c.code and 0xFF)
        }
        body.write(')'.code)
        return bytes("LIST", "btdk".toByteArray() + body.toByteArray())
    }

    private fun project(vararg layers: ByteArray): ByteArray {
        val actual = layers.ifEmpty { arrayOf(layer()) }
        return projectOf(comp(10, "Comp", *actual))
    }

    private fun projectOf(vararg items: ByteArray): ByteArray =
        bytes("RIFX", "Egg!".toByteArray() + list("Fold", *items)).also {
            ByteBuffer.wrap(it, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(it.size - 8)
        }

    private fun comp(id: Int, name: String, vararg layers: ByteArray): ByteArray {
        val idta = ByteArray(84).also {
            be16(it, 0, 4)
            be32(it, 16, id)
        }
        val cdta = ByteArray(154).also {
            be32(it, 4, 1024)
            be32(it, 8, 30720)
            be32(it, 44, 30720)
            it[52] = 0
            it[53] = 0
            it[54] = 0
            be16(it, 140, 1080)
            be16(it, 142, 1080)
        }
        return list("Item", bytes("idta", idta), bytes("Utf8", name.toByteArray()), bytes("cdta", cdta), *layers)
    }

    private fun layer(type: Int = 0, label: String = "Layer", extra: ByteArray? = null, id: Int = 1, source: Int = 0): ByteArray {
        val ldta = ByteArray(160).also {
            be32(it, 0, id)
            be32(it, 8, 1)
            be32(it, 12, 0)
            be32(it, 16, 30720)
            be32(it, 20, 0)
            be32(it, 24, 30720)
            be32(it, 28, 30720)
            be32(it, 32, 30720)
            it[38] = 0
            it[39] = 1
            be32(it, 40, source)
            be32(it, 108, 1)
            be32(it, 128, type)
            be32(it, 132, 0)
        }
        val position = property("ADBE Position", 3, 0, listOf(100.0, 200.0, 0.0), listOf(0.0 to listOf(100.0, 200.0, 0.0), 30720.0 to listOf(300.0, 400.0, 0.0)))
        val scale = property("ADBE Scale", 3, 0, listOf(0.5, 0.5, 1.0))
        val opacity = property("ADBE Opacity", 1, 0, listOf(0.25))
        val group = list("tdgp", mn("ADBE Position"), position, mn("ADBE Scale"), scale, mn("ADBE Opacity"), opacity)
        val root = list("tdgp", mn("ADBE Transform Group"), group)
        return list("Layr", bytes("ldta", ldta), bytes("Utf8", label.toByteArray()), root, *(extra?.let { arrayOf(it) } ?: emptyArray()))
    }

    private fun property(name: String, dims: Int, record: Int, value: List<Double>, keys: List<Pair<Double, List<Double>>> = emptyList()): ByteArray {
        val header = ByteArray(124).also {
            be32(it, 0, 0x00db9900)
            it[3] = dims.toByte()
            it[5] = if (keys.isEmpty()) 1 else 0
            it[7] = 0
            be32(it, 12, 30720)
            it[68] = if (keys.isEmpty()) 0 else 1
        }
        val parts = ArrayList<ByteArray>()
        parts += bytes("tdb4", header)
        if (keys.isEmpty()) {
            parts += bytes("cdat", doubles(value))
        } else {
            val size = 8 + 40 * dims
            val list = ByteArray(52).also {
                be32(it, 0, 0x00d00bee)
                be32(it, 8, keys.size)
                be32(it, 16, size)
            }
            val data = ByteArray(keys.size * size)
            keys.forEachIndexed { i, (at, v) ->
                val base = i * size
                be32(data, base, at.toInt())
                data[base + 4] = 1
                data[base + 5] = 1
                v.forEachIndexed { c, n -> be64(data, base + 8 + c * 8, n) }
            }
            parts += list("list", bytes("lhd3", list), bytes("ldat", data))
        }
        return list("tdbs", *parts.toTypedArray())
    }

    private fun mn(name: String): ByteArray = bytes("tdmn", ByteArray(40).also { name.toByteArray().copyInto(it) })

    private fun list(type: String, vararg children: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type.toByteArray())
        children.forEach(out::write)
        return bytes("LIST", out.toByteArray())
    }

    private fun bytes(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray())
        out.write(be32(body.size))
        out.write(body)
        if (body.size and 1 != 0) out.write(0)
        return out.toByteArray()
    }

    private fun doubles(values: List<Double>) = ByteArray(values.size * 8).also { data ->
        values.forEachIndexed { i, v -> be64(data, i * 8, v) }
    }

    private fun be16(data: ByteArray, at: Int, value: Int) {
        data[at] = (value ushr 8).toByte()
        data[at + 1] = value.toByte()
    }

    private fun be32(data: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) data[at + i] = (value ushr (24 - i * 8)).toByte()
    }

    private fun be64(data: ByteArray, at: Int, value: Double) {
        var bits = value.toRawBits()
        for (i in 7 downTo 0) {
            data[at + i] = bits.toByte()
            bits = bits ushr 8
        }
    }

    private fun be32(value: Int): ByteArray = ByteArray(4).also { be32(it, 0, value) }
}
