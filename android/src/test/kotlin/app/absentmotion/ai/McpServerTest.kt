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

class McpHttpTest {
    @Test fun httpEndpointAnswersInitializeAndHealth() {
        val port = 18765
        McpServer.startLocal(port, "test-token")
        try {
            val url = java.net.URL("http://127.0.0.1:$port/mcp")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer test-token")
            connection.doOutput = true
            connection.outputStream.use { it.write("""{"jsonrpc":"2.0","id":7,"method":"initialize"}""".toByteArray()) }
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            assertEquals("2024-11-05", JSONObject(body).getJSONObject("result").getString("protocolVersion"))

            val health = java.net.URL("http://127.0.0.1:$port/health").openConnection() as java.net.HttpURLConnection
            assertEquals(200, health.responseCode)

            val denied = java.net.URL("http://127.0.0.1:$port/mcp").openConnection() as java.net.HttpURLConnection
            denied.requestMethod = "POST"
            denied.doOutput = true
            denied.outputStream.use { it.write("{}".toByteArray()) }
            assertEquals(401, denied.responseCode)
        } finally {
            McpServer.stop()
        }
    }
}
