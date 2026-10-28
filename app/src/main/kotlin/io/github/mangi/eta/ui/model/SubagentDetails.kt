package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.runtime.AgentSubagentManager
import io.github.mangi.eta.agent.runtime.AgentSubagentTool
import io.github.mangi.eta.data.db.ConversationSubagentSummary
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

    /**
     * 一个会话里派出过的全部子 Agent（按调用顺序）；运行中的取实时进度，结束的从会话记录还原。
     * [storedToolRows] 是库里这个会话的全部工具行：按页加载时更早的批次不在 [messages] 里，
     * 它们排在已加载的消息之前，用来补齐列表并保持同一调用 id 的序号。
     */
    fun forConversation(
        messages: List<AgentChatMessageUi>,
        journal: List<AgentModelClient.ConversationMessage>,
        storedToolRows: List<ToolActivityMessageUi> = emptyList(),
    ): List<ConversationSubagentUi> {
        val loadedIds = messages.mapTo(HashSet()) { it.id }
        val all = storedToolRows.filter { it.id !in loadedIds } + messages
        return all.filterIsInstance<ToolActivityMessageUi>()
            .filter { it.toolName == AgentSubagentTool.TOOL_NAME }
            .flatMap { message ->
                val ids = runAndCallId(message.id) ?: return@flatMap emptyList()
                message.subagents.ifEmpty { fromDetail(ToolCallDetails.find(journal, all, message)) }
                    .map { ConversationSubagentUi(ids.first, ids.second, it) }
            }
    }

    /**
     * 会话级子 Agent 的侧栏条目，按会话分组、按派出顺序，任务前带上名字（没起名时就是编号）。
     * 已中断与已关闭作为标签显示（AG-27）：已关闭的保留关闭前的结果（有结论算完成、有错误算失败）。
     */
    fun fromRegistry(rows: List<ConversationSubagentSummary>): Map<String, List<ConversationSubagentUi>> =
        rows.groupBy { it.conversationId }.mapValues { (conversationId, agents) ->
            agents.mapIndexed { index, row ->
                ConversationSubagentUi(
                    runId = "",
                    toolCallId = row.agentId,
                    conversationId = conversationId,
                    agentId = row.agentId,
                    progress = SubagentProgressUi(
                        index = index,
                        total = agents.size,
                        status = when (row.status) {
                            AgentSubagentManager.STATUS_RUNNING -> ToolActivityStatusUi.Running
                            AgentSubagentManager.STATUS_COMPLETED -> ToolActivityStatusUi.Success
                            AgentSubagentManager.STATUS_FAILED -> ToolActivityStatusUi.Failed
                            AgentSubagentManager.STATUS_CLOSED -> when {
                                row.lastError.isNotBlank() -> ToolActivityStatusUi.Failed
                                row.lastResult.isNotBlank() -> ToolActivityStatusUi.Success
                                else -> ToolActivityStatusUi.Unknown
                            }
                            else -> ToolActivityStatusUi.Unknown
                        },
                        task = if (row.name == row.agentId) row.task else "${row.name} · ${row.task}",
                        conclusion = row.lastError.ifBlank { row.lastResult },
                        turn = row.turn,
                        rounds = row.rounds,
                        interrupted = row.status == AgentSubagentManager.STATUS_INTERRUPTED,
                        closed = row.status == AgentSubagentManager.STATUS_CLOSED,
                    ),
                )
            }
        }

    /**
     * 子 Agent 过程转成与主会话相同的思考、正文与工具行；首条用户消息是任务，不重复显示（AG-27 起按回合分段）：
     * 之后的回合以“第 N 回合”与主 Agent 交给它的任务开头，回合中插入的“主 Agent 补充”单独标出。
     * [running] 为真时，还没出结果的工具显示为执行中，否则为结果未知。
     */
    fun timeline(
        transcript: List<AgentModelClient.ConversationMessage>,
        running: Boolean = false,
        formatter: AgentTraceFormatter = AgentTraceFormatter(),
    ): List<AgentChatMessageUi> {
        val results = transcript.filter { it.role == "tool" }
            .groupBy { it.toolCallId }
            .mapValues { (_, messages) -> ArrayDeque(messages) }
        val timeline = mutableListOf<AgentChatMessageUi>()
        var round = 0
        var turn = 0
        transcript.forEachIndexed { index, message ->
            if (message.role == "user") {
                val text = message.content.trim()
                val supplement = SUPPLEMENT_PREFIXES.firstOrNull { text.startsWith("$it：") }
                when {
                    supplement != null -> timeline += AgentMessageUi(
                        id = "sub-supplement-$index",
                        content = "**$supplement**\n\n" + text.removePrefix("$supplement：").substringBefore(SUPPLEMENT_SUFFIX).trim(),
                    )
                    text.isNotBlank() && !message.contextSummary -> {
                        turn++
                        if (turn > 1) timeline += AgentMessageUi(id = "sub-turn-$turn", content = "**第 $turn 回合**\n\n$text")
                    }
                }
                return@forEachIndexed
            }
            if (message.role != "assistant") return@forEachIndexed
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
                        result == null && running -> ToolActivityStatusUi.Running
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

    /** 回合中插入的消息前缀（见 AgentLoop.steeringPrompt）；旧记录里主 Agent 的补充标的是“用户补充指令”。 */
    private val SUPPLEMENT_PREFIXES = listOf(
        io.github.mangi.eta.agent.runtime.AgentRunController.COORDINATOR_STEERING_LABEL,
        io.github.mangi.eta.agent.runtime.AgentRunController.DEFAULT_STEERING_LABEL,
    )
    private const val SUPPLEMENT_SUFFIX = "\n\n请基于当前任务上下文继续执行"
}
