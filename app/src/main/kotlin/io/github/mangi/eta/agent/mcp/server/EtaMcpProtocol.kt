package io.github.mangi.eta.agent.mcp.server

import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 设备端 MCP 服务暴露给电脑端的一个工具。 */
internal data class EtaMcpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val readOnly: Boolean? = null,
)

internal data class EtaMcpToolOutcome(
    val text: String,
    val images: List<Image> = emptyList(),
    val isError: Boolean = false,
) {
    data class Image(val base64: String, val mimeType: String)
}

/** 工具来源与执行环境；每个 MCP 会话独占一个执行会话，便于隔离屏幕观察缓存与资源租约。 */
internal interface EtaMcpToolHost {
    fun listTools(): List<EtaMcpTool>

    fun openSession(sessionKey: String): Session

    interface Session : AutoCloseable {
        fun call(name: String, arguments: JSONObject): EtaMcpToolOutcome
    }
}

/**
 * MCP Streamable HTTP 协议层，只依赖 [EtaMcpToolHost]，不接触 socket。
 *
 * - 旧协议（2024-11-05 至 2025-11-25）：initialize 协商版本并分配 `Mcp-Session-Id`，
 *   后续请求带会话 id；会话失效返回 404，客户端按规范重新初始化。
 * - 2026-07-28：无 initialize，请求以 `MCP-Protocol-Version` 头或 `_meta` 声明版本，
 *   结果带 `resultType`；这类请求共用一个无状态执行会话。
 * - 鉴权固定为 Bearer 令牌；带 Origin 头的请求一律拒绝，防止浏览器页面经 DNS 重绑定访问。
 */
internal class EtaMcpProtocol(
    private val toolHost: EtaMcpToolHost,
    private val token: () -> String,
    private val serverVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private class Session(
        val id: String,
        @Volatile var lastUsedAt: Long,
    ) {
        /** 同一会话的工具调用串行执行；关闭只取状态锁，能打断正在执行的调用。 */
        private val callLock = Any()
        private val stateLock = Any()
        private var tools: EtaMcpToolHost.Session? = null
        private var closed = false

        fun <T> withTools(host: EtaMcpToolHost, block: (EtaMcpToolHost.Session) -> T): T = synchronized(callLock) {
            val session = synchronized(stateLock) {
                check(!closed) { "session closed" }
                tools ?: host.openSession("mcp:$id").also { tools = it }
            }
            block(session)
        }

        fun close() {
            val toClose = synchronized(stateLock) {
                closed = true
                tools.also { tools = null }
            }
            runCatching { toClose?.close() }
        }
    }

    private val sessions = LinkedHashMap<String, Session>()
    private val statelessSession = Session("stateless", now())

    fun handle(request: McpHttpRequest): McpHttpResponse {
        if (request.path.trimEnd('/') != PATH) return text(404, "not found")
        if (request.header("origin") != null) return text(403, "origin not allowed")
        if (!authorized(request.header("authorization"))) {
            return McpHttpResponse(401, "unauthorized".toByteArray(), TEXT_PLAIN, mapOf("WWW-Authenticate" to "Bearer"))
        }
        return when (request.method) {
            "POST" -> handlePost(request)
            "DELETE" -> handleDelete(request)
            else -> McpHttpResponse(405, ByteArray(0), headers = mapOf("Allow" to "POST, DELETE"))
        }
    }

    override fun close() {
        val all = synchronized(sessions) { sessions.values.toList().also { sessions.clear() } }
        all.forEach(Session::close)
        statelessSession.close()
    }

    private fun authorized(header: String?): Boolean {
        val expected = token().takeIf { it.isNotBlank() } ?: return false
        val presented = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)?.trim() ?: return false
        return MessageDigest.isEqual(presented.toByteArray(), expected.toByteArray())
    }

    private fun handleDelete(request: McpHttpRequest): McpHttpResponse {
        val id = request.header(HEADER_SESSION_ID) ?: return text(400, "missing session id")
        val removed = synchronized(sessions) { sessions.remove(id) } ?: return text(404, "session not found")
        removed.close()
        return McpHttpResponse(204)
    }

    private fun handlePost(request: McpHttpRequest): McpHttpResponse {
        val contentType = request.header("content-type").orEmpty()
        if (!contentType.contains("application/json", ignoreCase = true)) return text(415, "expected application/json")
        val raw = request.body.toString(Charsets.UTF_8).trim()
        if (raw.startsWith("[")) return rpcError(400, JSONObject.NULL, INVALID_REQUEST, "batch requests are not supported")
        val message = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return rpcError(400, JSONObject.NULL, PARSE_ERROR, "invalid JSON")
        }
        val method = message.optString("method")
        // 客户端对服务端请求的响应与通知都没有需要返回的内容。
        if (method.isBlank() || !message.has("id")) return McpHttpResponse(202)
        val id = message.get("id")
        val params = message.optJSONObject("params") ?: JSONObject()

        if (method == "initialize") return initialize(id, params)

        val session = resolveSession(request) ?: return rpcError(404, id, SESSION_NOT_FOUND, "session not found")
        session.lastUsedAt = now()
        val latest = request.header(HEADER_PROTOCOL_VERSION) == LATEST_PROTOCOL_VERSION ||
            params.optJSONObject("_meta")?.optString(META_PROTOCOL_VERSION) == LATEST_PROTOCOL_VERSION
        val result = try {
            when (method) {
                "ping" -> JSONObject()
                "tools/list" -> listTools(latest)
                "tools/call" -> callTool(session, params)
                else -> return rpcError(200, id, METHOD_NOT_FOUND, "method not found: $method")
            }
        } catch (failure: InvalidParamsException) {
            return rpcError(200, id, INVALID_PARAMS, failure.message.orEmpty())
        }
        if (latest) result.put("resultType", "complete")
        return rpcResult(id, result)
    }

    private fun initialize(id: Any, params: JSONObject): McpHttpResponse {
        val requested = params.optString("protocolVersion")
        val version = requested.takeIf { it in LEGACY_PROTOCOL_VERSIONS } ?: DEFAULT_LEGACY_PROTOCOL_VERSION
        val session = Session(UUID.randomUUID().toString(), now())
        val evicted = synchronized(sessions) {
            val expired = pruneLocked()
            sessions[session.id] = session
            // LinkedHashMap 按最近使用排序，超出上限时淘汰最久未用的会话。
            val overflow = if (sessions.size > MAX_SESSIONS) {
                listOfNotNull(sessions.remove(sessions.keys.first()))
            } else {
                emptyList()
            }
            expired + overflow
        }
        evicted.forEach(Session::close)
        val result = JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("title", "Eta").put("version", serverVersion))
            .put("instructions", INSTRUCTIONS)
        return rpcResult(id, result, mapOf(HEADER_SESSION_ID_OUT to session.id))
    }

    private fun resolveSession(request: McpHttpRequest): Session? {
        val sessionId = request.header(HEADER_SESSION_ID)
            // 未带会话 id 的请求按无状态处理：2026-07-28 客户端本就如此，旧客户端跳过 initialize 时也能用。
            ?: return statelessSession
        val (session, expired) = synchronized(sessions) {
            val stale = pruneLocked()
            // 取出再放回，让 LinkedHashMap 的顺序反映最近使用。
            sessions.remove(sessionId)?.also { sessions[sessionId] = it } to stale
        }
        expired.forEach(Session::close)
        return session
    }

    /** 移出空闲超时的会话；关闭可能等待正在执行的工具，由调用方在锁外进行。 */
    private fun pruneLocked(): List<Session> {
        val cutoff = now() - SESSION_IDLE_MS
        val expired = sessions.values.filter { it.lastUsedAt < cutoff }
        expired.forEach { sessions.remove(it.id) }
        return expired
    }

    private fun listTools(latest: Boolean): JSONObject {
        val tools = JSONArray()
        toolHost.listTools().forEach { tool ->
            tools.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("inputSchema", tool.inputSchema)
                    .apply {
                        tool.readOnly?.let { readOnly ->
                            put("annotations", JSONObject().put("readOnlyHint", readOnly))
                        }
                    },
            )
        }
        return JSONObject().put("tools", tools).apply {
            // 工具集随设置与系统状态变化，不让客户端长期缓存。
            if (latest) put("ttlMs", 0)
        }
    }

    private fun callTool(session: Session, params: JSONObject): JSONObject {
        val name = params.optString("name").takeIf(String::isNotBlank)
            ?: throw InvalidParamsException("missing tool name")
        if (params.has("arguments") && params.optJSONObject("arguments") == null && !params.isNull("arguments")) {
            throw InvalidParamsException("arguments must be an object")
        }
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        if (toolHost.listTools().none { it.name == name }) throw InvalidParamsException("unknown tool: $name")
        val outcome = session.withTools(toolHost) { tools -> tools.call(name, arguments) }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", outcome.text))
        outcome.images.forEach { image ->
            content.put(JSONObject().put("type", "image").put("data", image.base64).put("mimeType", image.mimeType))
        }
        return JSONObject().put("content", content).put("isError", outcome.isError)
    }

    private fun rpcResult(id: Any, result: JSONObject, headers: Map<String, String> = emptyMap()): McpHttpResponse =
        json(200, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result), headers)

    private fun rpcError(status: Int, id: Any, code: Int, message: String): McpHttpResponse =
        json(
            status,
            JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message)),
        )

    private fun json(status: Int, body: JSONObject, headers: Map<String, String> = emptyMap()) =
        McpHttpResponse(status, body.toString().toByteArray(Charsets.UTF_8), JSON, headers)

    private fun text(status: Int, message: String) =
        McpHttpResponse(status, message.toByteArray(Charsets.UTF_8), TEXT_PLAIN)

    private class InvalidParamsException(message: String) : IllegalArgumentException(message)

    internal companion object {
        const val PATH = "/mcp"
        const val SERVER_NAME = "eta"
        const val LATEST_PROTOCOL_VERSION = "2026-07-28"
        const val DEFAULT_LEGACY_PROTOCOL_VERSION = "2025-11-25"
        val LEGACY_PROTOCOL_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18", DEFAULT_LEGACY_PROTOCOL_VERSION)
        const val MAX_SESSIONS = 8
        const val SESSION_IDLE_MS = 30 * 60 * 1000L

        private const val HEADER_SESSION_ID = "mcp-session-id"
        private const val HEADER_PROTOCOL_VERSION = "mcp-protocol-version"
        private const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
        private const val HEADER_SESSION_ID_OUT = "Mcp-Session-Id"
        private const val JSON = "application/json"
        private const val TEXT_PLAIN = "text/plain; charset=utf-8"
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val SESSION_NOT_FOUND = -32001

        private const val INSTRUCTIONS =
            "Eta 是运行在用户 Android 手机上的系统级 Agent，本服务把它的设备工具开放给电脑端。" +
                "界面类工具（observe_screen、tap_element 等）操作的是用户正在使用的真实屏幕，" +
                "与手机上的 Eta 任务共用屏幕租约，遇到 SCREEN_BUSY 时稍后再试；" +
                "terminal 与文件工具在设备已授权 Root 时默认以 Root 身份执行。" +
                "工具结果为 JSON 文本，ok=false 时 code 字段给出原因。" +
                "需要多步完成的手机任务可用 eta_task_start 交给 Eta 自己的 Agent 执行，" +
                "再用 eta_task_status 等待结果，用 eta_task_answer 回答它提出的问题。"
    }
}
