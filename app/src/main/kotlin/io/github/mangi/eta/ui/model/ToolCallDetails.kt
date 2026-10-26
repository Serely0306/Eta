package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.json.JSONArray
import org.json.JSONObject

/** 一次工具调用保存在会话记录里的完整参数与结果；敏感工具两者都不保存。 */
@Immutable
internal data class ToolCallDetail(
    val argumentsJson: String?,
    val result: String?,
    val sensitive: Boolean,
)

@Immutable
internal data class TerminalOutput(
    val exitCode: Int?,
    val timedOut: Boolean,
    val stdout: String,
    val stderr: String,
    /** 工具为模型截断过输出。 */
    val truncated: Boolean,
    val durationMs: Long?,
)

@Immutable
internal data class WebSearchResult(val title: String, val url: String, val snippet: String)

@Immutable
internal data class WebPageContent(val url: String, val title: String, val text: String, val hasMore: Boolean)

/**
 * 工具详情从会话 journal 中按 tool_call_id 取完整内容，不另存副本。
 * 部分服务商的调用 id 会在不同回合重复，这时按出现顺序把消息列表中的第 n 次调用对应到 journal 中的第 n 次。
 */
internal object ToolCallDetails {
    private val MESSAGE_ID = Regex("^.*-tool-\\d+-(.+)$")
    private val RUN_ID = Regex("^(.*)-tool-\\d+-.+$")
    private val prettyJson = Json { prettyPrint = true }

    fun toolCallId(messageId: String): String? =
        MESSAGE_ID.find(messageId)?.groupValues?.get(1)?.takeUnless { it == "unknown" }

    /** 工具行 id 是 `<run>-tool-<轮>-<调用 id>`，取出 run。 */
    fun runId(messageId: String): String? =
        RUN_ID.find(messageId)?.groupValues?.get(1)?.takeIf(String::isNotBlank)

    fun find(
        journal: List<AgentModelClient.ConversationMessage>,
        messages: List<AgentChatMessageUi>,
        target: ToolActivityMessageUi,
    ): ToolCallDetail? {
        val callId = toolCallId(target.id) ?: return null
        val ordinal = messages.asSequence()
            .filterIsInstance<ToolActivityMessageUi>()
            .filter { toolCallId(it.id) == callId }
            .indexOfFirst { it.id == target.id }
            .coerceAtLeast(0)
        val result = journal.asSequence()
            .filter { it.role == "tool" && it.toolCallId == callId }
            .elementAtOrNull(ordinal)
            ?.let { it.content.ifBlank { it.contentJson } }
        val arguments = journal.asSequence()
            .filter { it.role == "assistant" && it.toolCallsJson.isNotBlank() }
            .flatMap { callArguments(it.toolCallsJson, callId) }
            .elementAtOrNull(ordinal)
        if (result == null && arguments == null) return null
        val sensitive = result == AgentConversationCodec.SENSITIVE_TOOL_OMITTED_TEXT ||
            arguments?.let(::isRedactedArguments) == true
        return ToolCallDetail(
            argumentsJson = arguments.takeUnless { sensitive },
            result = result.takeUnless { sensitive },
            sensitive = sensitive,
        )
    }

    /** JSON 格式化；不是 JSON 时原样返回。不转义斜杠与中文，命令和链接保持可读。 */
    fun pretty(raw: String): String =
        runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw)) }
            .getOrDefault(raw)

    fun terminalOutput(result: String?): TerminalOutput? {
        val json = result?.let(::jsonObject) ?: return null
        if (!json.has("stdout") && !json.has("stderr") && !json.has("exit_code")) return null
        return TerminalOutput(
            exitCode = if (json.has("exit_code") && !json.isNull("exit_code")) json.optInt("exit_code") else null,
            timedOut = json.optBoolean("timed_out"),
            // 后台命令的 read 结果把日志放在 output，没有 stdout。
            stdout = json.text("stdout").ifEmpty { json.text("output") },
            stderr = json.text("stderr"),
            truncated = json.optBoolean("stdout_truncated") || json.optBoolean("stderr_truncated") ||
                json.optBoolean("output_truncated"),
            durationMs = sequenceOf("duration_ms", "elapsed_ms")
                .firstNotNullOfOrNull { key -> json.optLong(key, -1).takeIf { it >= 0 } },
        )
    }

    fun searchQuery(argumentsJson: String?): String? =
        argumentsJson?.let(::jsonObject)?.text("query")?.takeIf(String::isNotBlank)

    fun searchResults(result: String?): List<WebSearchResult> {
        val items = result?.let(::jsonObject)?.optJSONArray("results") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let { item ->
                WebSearchResult(item.text("title"), item.text("url"), item.text("snippet"))
                    .takeIf { it.title.isNotBlank() || it.url.isNotBlank() }
            }
        }
    }

    /** 服务商托管搜索的来源链接：界面把它们写在摘要第一行之后。 */
    fun hostedSources(summary: String?): List<String> =
        summary?.lines().orEmpty().map(String::trim).filter {
            it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
        }

    fun webPage(result: String?): WebPageContent? {
        val json = result?.let(::jsonObject) ?: return null
        if (!json.has("text")) return null
        return WebPageContent(
            url = json.text("final_url").ifBlank { json.text("requested_url") },
            title = json.text("title"),
            text = json.text("text"),
            hasMore = json.optBoolean("has_more") || json.optBoolean("truncated"),
        )
    }

    private fun callArguments(toolCallsJson: String, callId: String): Sequence<String> {
        val calls = runCatching { JSONArray(toolCallsJson) }.getOrNull() ?: return emptySequence()
        return (0 until calls.length()).asSequence()
            .mapNotNull(calls::optJSONObject)
            .filter { it.optString("id") == callId }
            .map { call ->
                when (val arguments = call.optJSONObject("function")?.opt("arguments")) {
                    is String -> arguments
                    null, JSONObject.NULL -> "{}"
                    else -> arguments.toString()
                }
            }
    }

    private fun isRedactedArguments(arguments: String): Boolean =
        jsonObject(arguments)?.let { it.length() == 1 && it.optBoolean("redacted") } == true

    private fun jsonObject(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()

    /** 缺失或为 JSON null 时为空串（optString 会把 null 变成 "null"）。 */
    private fun JSONObject.text(key: String): String =
        if (!has(key) || isNull(key)) "" else optString(key)
}
