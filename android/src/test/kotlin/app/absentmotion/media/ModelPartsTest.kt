package app.absentmotion.media

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class ModelPartsTest {
    @Test
    fun gltfNodesBecomeARigidParentedPartHierarchy() {
        val bytes = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
        // A small quad, shared by both nodes.
        for (v in listOf(-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f)) bytes.putFloat(v)
        for (i in listOf(0, 1, 2, 0, 2, 3)) bytes.putShort(i.toShort())
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes.array())
        val json = JSONObject(
            """
            {"asset":{"version":"2.0"},"buffers":[{"uri":"$uri","byteLength":84}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":48},{"buffer":0,"byteOffset":48,"byteLength":12}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3","min":[-1,-1,0],"max":[1,1,0]},
                          {"bufferView":1,"componentType":5123,"count":6,"type":"SCALAR"}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
             "nodes":[{"name":"Body","mesh":0,"children":[1]},{"name":"Arm","mesh":0,"translation":[1,0,0]}],
             "scenes":[{"nodes":[0]}],"scene":0}
            """.trimIndent(),
        )
        val mesh = parseModel("rig.gltf", json.toString().toByteArray(), { null })
        assertEquals(listOf("Body", "Arm"), mesh.partNames)
        assertArrayEquals(intArrayOf(-1, 0), mesh.partParents)
        assertEquals(2, mesh.partCenters.size / 3)
        assertTrue(mesh.partCenters[3] > mesh.partCenters[0])
        assertTrue(mesh.partOfVertex.any { it == 0 })
        assertTrue(mesh.partOfVertex.any { it == 1 })
    }
}
