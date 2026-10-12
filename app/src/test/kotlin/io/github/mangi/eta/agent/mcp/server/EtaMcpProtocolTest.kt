package io.github.mangi.eta.agent.mcp.server

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaMcpProtocolTest {
    private class FakeHost : EtaMcpToolHost {
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val calls = mutableListOf<Pair<String, String>>()

        override fun listTools() = listOf(
            EtaMcpTool("echo", "回显参数", JSONObject().put("type", "object")),
            EtaMcpTool("fail", "总是失败", JSONObject().put("type", "object"), readOnly = true),
        )

        override fun openSession(sessionKey: String): EtaMcpToolHost.Session {
            opened += sessionKey
            return object : EtaMcpToolHost.Session {
                override fun call(name: String, arguments: JSONObject): EtaMcpToolOutcome {
                    calls += sessionKey to name
                    return if (name == "fail") {
                        EtaMcpToolOutcome("""{"ok":false,"code":"X"}""", isError = true)
                    } else {
                        EtaMcpToolOutcome(arguments.toString(), listOf(EtaMcpToolOutcome.Image("AAAA", "image/png")))
                    }
                }

                override fun close() {
                    closed += sessionKey
                }
            }
        }
    }

    private var clock = 1_000L
    private val host = FakeHost()
    private val protocol = EtaMcpProtocol(host, token = { TOKEN }, serverVersion = "test", now = { clock })

    @After
    fun tearDown() = protocol.close()

    @Test
    fun rejectsMissingOrWrongTokenAndBrowserOrigins() {
        assertEquals(401, protocol.handle(post(ping(), token = null)).status)
        assertEquals(401, protocol.handle(post(ping(), token = "wrong")).status)
        assertEquals(403, protocol.handle(post(ping(), extraHeaders = mapOf("origin" to "http://evil.test"))).status)
        assertEquals(404, protocol.handle(post(ping(), path = "/other")).status)
        assertEquals(405, protocol.handle(request("GET", ByteArray(0))).status)
    }

    @Test
    fun legacyInitializeNegotiatesVersionAndIssuesSession() {
        val response = protocol.handle(post(initialize("2025-06-18")))
        assertEquals(200, response.status)
        val result = body(response).getJSONObject("result")
        assertEquals("2025-06-18", result.getString("protocolVersion"))
        assertEquals("eta", result.getJSONObject("serverInfo").getString("name"))
        assertNotNull(response.headers["Mcp-Session-Id"])

        val unknown = protocol.handle(post(initialize("1999-01-01")))
        assertEquals(
            EtaMcpProtocol.DEFAULT_LEGACY_PROTOCOL_VERSION,
            body(unknown).getJSONObject("result").getString("protocolVersion"),
        )
    }

    @Test
    fun sessionsKeepSeparateToolExecutorsAndExpire() {
        val first = sessionOf(protocol.handle(post(initialize("2025-11-25"))))
        val second = sessionOf(protocol.handle(post(initialize("2025-11-25"))))
        protocol.handle(post(call("echo"), session = first))
        protocol.handle(post(call("echo"), session = second))
        protocol.handle(post(call("echo"), session = first))
        assertEquals(listOf("mcp:$first", "mcp:$second"), host.opened)

        clock += EtaMcpProtocol.SESSION_IDLE_MS + 1
        val expired = protocol.handle(post(call("echo"), session = first))
        assertEquals(404, expired.status)
        assertTrue("mcp:$first" in host.closed)
    }

    @Test
    fun deleteEndsSessionAndClosesExecutor() {
        val session = sessionOf(protocol.handle(post(initialize("2025-11-25"))))
        protocol.handle(post(call("echo"), session = session))
        assertEquals(204, protocol.handle(request("DELETE", ByteArray(0), session = session)).status)
        assertEquals(listOf("mcp:$session"), host.closed)
        assertEquals(404, protocol.handle(request("DELETE", ByteArray(0), session = session)).status)
    }

    @Test
    fun toolsListAndCallFollowMcpShapes() {
        val listed = body(protocol.handle(post(rpc("tools/list")))).getJSONObject("result")
        val tools = listed.getJSONArray("tools")
        assertEquals(2, tools.length())
        assertEquals("echo", tools.getJSONObject(0).getString("name"))
        assertTrue(tools.getJSONObject(1).getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertFalse(listed.has("resultType"))

        val ok = body(protocol.handle(post(call("echo", JSONObject().put("x", 1))))).getJSONObject("result")
        assertFalse(ok.getBoolean("isError"))
        val content = ok.getJSONArray("content")
        assertEquals("""{"x":1}""", content.getJSONObject(0).getString("text"))
        assertEquals("image", content.getJSONObject(1).getString("type"))

        val failed = body(protocol.handle(post(call("fail")))).getJSONObject("result")
        assertTrue(failed.getBoolean("isError"))
    }

    @Test
    fun unknownToolAndMethodAreProtocolErrors() {
        val unknownTool = body(protocol.handle(post(call("missing"))))
        assertEquals(-32602, unknownTool.getJSONObject("error").getInt("code"))
        val unknownMethod = body(protocol.handle(post(rpc("resources/list"))))
        assertEquals(-32601, unknownMethod.getJSONObject("error").getInt("code"))
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun latestProtocolRequestsAreStatelessAndMarkedComplete() {
        val response = protocol.handle(
            post(rpc("tools/list"), extraHeaders = mapOf("mcp-protocol-version" to EtaMcpProtocol.LATEST_PROTOCOL_VERSION)),
        )
        val result = body(response).getJSONObject("result")
        assertEquals("complete", result.getString("resultType"))
        assertEquals(0, result.getInt("ttlMs"))
        assertNull(response.headers["Mcp-Session-Id"])
    }

    @Test
    fun notificationsAndBatchesAreHandled() {
        val notification = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        assertEquals(202, protocol.handle(post(notification)).status)
        val batch = request("POST", "[]".toByteArray(), contentType = "application/json")
        assertEquals(400, protocol.handle(batch).status)
        assertEquals(415, protocol.handle(request("POST", ping().toString().toByteArray(), contentType = "text/plain")).status)
    }

    @Test
    fun httpServerRoundTripsOverLoopback() {
        val server = EtaMcpHttpServer(InetAddress.getLoopbackAddress(), 0, protocol::handle)
        server.start()
        try {
            val payload = initialize("2025-11-25").toString().toByteArray()
            val raw = Socket(InetAddress.getLoopbackAddress(), server.boundPort).use { socket ->
                val head = "POST /mcp HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $TOKEN\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n\r\n"
                socket.getOutputStream().apply { write(head.toByteArray()); write(payload); flush() }
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
            assertTrue(raw.startsWith("HTTP/1.1 200 OK"))
            assertTrue(raw.contains("Mcp-Session-Id: "))
            val json = JSONObject(raw.substringAfter("\r\n\r\n"))
            assertEquals("2025-11-25", json.getJSONObject("result").getString("protocolVersion"))
        } finally {
            server.close()
        }
    }

    @Test
    fun parserHandlesChunkedBodiesAndRejectsOversizedOnes() {
        val chunked = "POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabcd\r\n2\r\nef\r\n0\r\n\r\n"
        val request = EtaMcpHttpServer.readRequest(ByteArrayInputStream(chunked.toByteArray()))
        assertEquals("abcdef", request.body.toString(Charsets.UTF_8))
        assertEquals("/mcp", request.path)

        val oversized = "POST /mcp HTTP/1.1\r\nContent-Length: ${EtaMcpHttpServer.MAX_BODY_BYTES + 1}\r\n\r\n"
        val failure = runCatching { EtaMcpHttpServer.readRequest(ByteArrayInputStream(oversized.toByteArray())) }
            .exceptionOrNull() as? EtaMcpHttpServer.HttpParseException
        assertEquals(413, failure?.status)

        val out = ByteArrayOutputStream()
        EtaMcpHttpServer.writeResponse(out, McpHttpResponse(200, "{}".toByteArray(), "application/json", mapOf("X-Bad" to "a\r\nb")))
        val written = out.toString(Charsets.UTF_8.name())
        assertTrue(written.contains("Content-Length: 2\r\n"))
        assertFalse(written.contains("X-Bad"))
    }

    private fun ping() = rpc("ping")

    private fun initialize(version: String) = rpc(
        "initialize",
        JSONObject().put("protocolVersion", version).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "test").put("version", "1")),
    )

    private fun call(name: String, arguments: JSONObject = JSONObject()) =
        rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments))

    private fun rpc(method: String, params: JSONObject = JSONObject()) =
        JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)

    private fun post(
        message: JSONObject,
        token: String? = TOKEN,
        session: String? = null,
        path: String = "/mcp",
        extraHeaders: Map<String, String> = emptyMap(),
    ) = request("POST", message.toString().toByteArray(), token, session, path, extraHeaders)

    private fun request(
        method: String,
        body: ByteArray,
        token: String? = TOKEN,
        session: String? = null,
        path: String = "/mcp",
        extraHeaders: Map<String, String> = emptyMap(),
        contentType: String = "application/json",
    ): McpHttpRequest {
        val headers = buildMap {
            put("content-type", contentType)
            token?.let { put("authorization", "Bearer $it") }
            session?.let { put("mcp-session-id", it) }
            putAll(extraHeaders)
        }
        return McpHttpRequest(method, path, headers, body)
    }

    private fun body(response: McpHttpResponse) = JSONObject(response.body.toString(Charsets.UTF_8))

    private fun sessionOf(response: McpHttpResponse) = requireNotNull(response.headers["Mcp-Session-Id"])

    private companion object {
        const val TOKEN = "test-token"
    }
}
