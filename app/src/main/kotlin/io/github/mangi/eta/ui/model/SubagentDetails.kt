package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import org.json.JSONArray
import org.json.JSONObject

/** run_subagents 的界面数据：结束后的子 Agent 列表，以及单个子 Agent 过程的时间线。 */
internal object SubagentDetails {
    private val RUN_TOOL_ID = Regex("^(.*)-tool-\\d+-(.+)$")

    /** 工具行 id 中的 run id 与调用 id，用于找到子 Agent 的过程文件。 */
    fun runAndCallId(messageId: String): Pair<String, String>? =
        RUN_TOOL_ID.find(messageId)?.groupValues?.let { it[1] to it[2] }

    /** 结束后从会话记录还原各子 Agent：任务来自调用参数，状态与结论来自工具结果。 */
    fun fromDetail(detail: ToolCallDetail?): List<SubagentProgressUi> {
        val tasks = detail?.argumentsJson?.let { runCatching { JSONObject(it).optJSONArray("tasks") }.getOrNull() }
        val results = detail?.result?.let { runCatching { JSONObject(it).optJSONArray("results") }.getOrNull() }
        val total = maxOf(tasks?.length() ?: 0, results?.length() ?: 0)
        return (0 until total).map { index ->
            val result = results?.optJSONObject(index)
            val ok = result?.takeIf { it.has("ok") }?.optBoolean("ok")
            SubagentProgressUi(
                index = index,
                total = total,
                status = when (ok) {
                    true -> ToolActivityStatusUi.Success
                    false -> ToolActivityStatusUi.Failed
                    null -> ToolActivityStatusUi.Unknown
                },
                task = tasks?.optJSONObject(index)?.optString("task").orEmpty()
                    .ifBlank { result?.optString("task").orEmpty() },
                conclusion = result?.optString(if (ok == true) "result" else "error").orEmpty(),
            )
        }
    }

    /** 子 Agent 过程转成与主会话相同的思考、正文与工具行；首条用户消息是任务，不重复显示。 */
    fun timeline(
        transcript: List<AgentModelClient.ConversationMessage>,
        formatter: AgentTraceFormatter = AgentTraceFormatter(),
    ): List<AgentChatMessageUi> {
        val results = transcript.filter { it.role == "tool" }
            .groupBy { it.toolCallId }
            .mapValues { (_, messages) -> ArrayDeque(messages) }
        val timeline = mutableListOf<AgentChatMessageUi>()
        var round = 0
        transcript.filter { it.role == "assistant" }.forEach { message ->
            round++
            if (message.reasoningContent.isNotBlank()) {
                timeline += ThinkingMessageUi(
                    id = "sub-thinking-$round",
                    content = message.reasoningContent.trim(),
                    isStreaming = false,
                    collapsed = true,
                )
            }
            if (message.content.isNotBlank()) {
                timeline += AgentMessageUi(id = "sub-assistant-$round", content = message.content.trim())
            }
            toolCalls(message.toolCallsJson).forEach { call ->
                val result = results[call.id]?.removeFirstOrNull()
                    ?.let { AgentModelClient.ToolResult(it.content.ifBlank { it.contentJson }) }
                timeline += ToolActivityMessageUi(
                    id = "sub-tool-$round-${call.id}",
                    toolName = call.name,
                    status = when {
                        result == null -> ToolActivityStatusUi.Unknown
                        formatter.isSuccessResult(result) -> ToolActivityStatusUi.Success
                        else -> ToolActivityStatusUi.Failed
                    },
                    argumentsSummary = formatter.summarizeArguments(call),
                    command = formatter.displayCommand(call),
                    resultSummary = result?.let { formatter.summarizeResult(call.name, it) },
                )
            }
        }
        return timeline
    }

    private fun toolCalls(toolCallsJson: String): List<AgentModelClient.ToolCall> {
        if (toolCallsJson.isBlank()) return emptyList()
        val calls = runCatching { JSONArray(toolCallsJson) }.getOrNull() ?: return emptyList()
        return AgentConversationCodec.parseToolCalls(JSONObject().put("tool_calls", calls))
    }
}
