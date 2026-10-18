package io.github.mangi.eta.agent.mcp.server

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentUserAnswer
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONObject

/**
 * 经 Agent Runtime 执行委托任务。请求以“电脑端”入口来源提交：模型取电脑端选定的勾选模型（未开放选择时取默认模型），工具开关取本地设置，
 * 结果按会话归档进 Eta。会话历史只保存在内存里，进程重启后同一 conversation_id 从新上下文开始。
 */
internal class EtaMcpRuntimeTaskRunner(context: Context) : EtaMcpTaskManager.Runner {
    private val client = AgentRuntimeClient(context.applicationContext, AndroidAgentLogger)
    private val histories = LinkedHashMap<String, List<AgentModelClient.ConversationMessage>>()

    override fun run(
        runId: String,
        conversationKey: String,
        prompt: String,
        modelId: String?,
        reasoningEffort: String?,
        onEvent: (AgentEvent) -> Unit,
    ): AgentRuntimeWire.RunResult {
        val history = synchronized(histories) { histories[conversationKey] }.orEmpty()
        val payload = AgentExternalArchivePayload(
            userText = prompt,
            conversationKey = conversationKey,
            title = prompt.take(TITLE_CHARS),
            // Runtime 按此解析模型与强度，并再次核对模型是否在勾选范围内。
            adapterPayload = JSONObject().apply {
                modelId?.let { put(EtaMcpModelAccess.PAYLOAD_MODEL_ID, it) }
                reasoningEffort?.let { put(EtaMcpModelAccess.PAYLOAD_REASONING_EFFORT, it) }
            },
        )
        val result = client.run(
            request = AgentRuntimeWire.RunRequest(
                runId = runId,
                prompt = prompt,
                config = AgentModelClient.loadConfig(),
                images = emptyList(),
                history = history,
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = "$conversationKey:$runId",
                    source = AgentRuntimeWire.PC_MCP_HANDOFF_SOURCE,
                    payload = payload.toJson(),
                ),
                modelSessionId = "${AgentRuntimeWire.PC_MCP_HANDOFF_SOURCE}:$conversationKey",
            ),
            onEvent = onEvent,
        )
        val nextHistory = result.contextSnapshot?.messages ?: if (result.ok) {
            history + AgentModelClient.buildUserHistoryMessage(prompt, emptyList()) + result.transcript
        } else {
            history
        }
        synchronized(histories) {
            histories.remove(conversationKey)
            histories[conversationKey] = nextHistory
            while (histories.size > MAX_CONVERSATIONS) histories.remove(histories.keys.first())
        }
        // 归档已由 Runtime 写入，结果收件箱里的副本由本入口确认后清理。
        client.ackResult(runId)
        return result
    }

    override fun cancel(runId: String) = client.cancelRun(runId)

    override fun steer(runId: String, text: String): Boolean = client.steer(runId, text)

    override fun answer(runId: String, questionId: String, answer: AgentUserAnswer): Boolean =
        client.answerQuestion(runId, questionId, answer)

    private companion object {
        const val TITLE_CHARS = 40
        const val MAX_CONVERSATIONS = 8
    }
}
