package app.absentmotion.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTest {
    @Test fun initializeAdvertisesToolsAndProtocol() {
        val reply = McpServer.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")!!
        assertEquals("2.0", reply.getString("jsonrpc"))
        assertEquals(1, reply.getInt("id"))
        assertEquals("2024-11-05", reply.getJSONObject("result").getString("protocolVersion"))
        assertTrue(reply.getJSONObject("result").getJSONObject("capabilities").has("tools"))
    }

    @Test fun toolsListIncludesSceneAndLayerEditing() {
        val reply = McpServer.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!
        val tools = reply.getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
        assertTrue("scene.xml" in names)
        assertTrue("layer.set" in names)
        assertTrue("keyframe.add" in names)
    }

    @Test fun notificationsHaveNoResponseAndUnknownMethodsFail() {
        assertNull(McpServer.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        val error = McpServer.handle("""{"jsonrpc":"2.0","id":3,"method":"nope"}""")!!
        assertEquals(-32601, error.getJSONObject("error").getInt("code"))
    }
}
