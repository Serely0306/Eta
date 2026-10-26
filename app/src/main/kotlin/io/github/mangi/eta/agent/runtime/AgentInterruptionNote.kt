package io.github.mangi.eta.agent.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 被中断的 run 恢复时补给模型的说明。中断常常就是 run 自己的操作造成的（强停 Eta、重启设备），
 * 模型只看到“结果未知”时会照计划重做；写明中断时间、原因、正在执行的工具、计划步骤与半截回复，
 * 让它先核实再决定。作为本轮最后一条助手消息放进模型历史，不计入用户轮数；界面另有中断提示。
 */
internal object AgentInterruptionNote {
    private const val MAX_ARGUMENT_CHARS = 300
    private const val MAX_PARTIAL_REPLY_CHARS = 2_000
    private const val INTERRUPTED_CODE = "TOOL_INTERRUPTED"
    private const val PLAN_TOOL = "update_plan"
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun messageId(runId: String): String = "interrupted-note-$runId"

    fun build(
        runId: String,
        interruptedAt: Long,
        cause: String,
        transcript: List<AgentModelClient.ConversationMessage>,
        events: List<AgentEvent>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AgentModelClient.ConversationMessage {
        val time = timeFormat.withZone(zone).format(Instant.ofEpochMilli(interruptedAt))
        val lines = mutableListOf("[Eta 系统说明] 上一轮回复在 $time 被中断：$cause。")
        interruptedCalls(transcript).takeIf { it.isNotEmpty() }?.let { calls ->
            lines += "中断时正在执行：${calls.joinToString("；")}，结果未知。"
        }
        inProgressPlanStep(transcript)?.let { lines += "计划中进行中的步骤：$it。" }
        partialReply(runId, transcript, events)?.let { lines += "中断前已输出的回复：\n$it" }
        lines += "如果中断时正在执行的工具会结束 Eta 自身或重启设备，中断很可能就是它生效造成的；" +
            "继续之前先核实结果，不要直接重做。这条说明由 Eta 在恢复会话时补充。"
        return AgentModelClient.ConversationMessage(
            role = "assistant",
            content = lines.joinToString("\n"),
            messageId = messageId(runId),
        )
    }

    /** 中断原因：开机时间晚于中断时间说明设备重启过；否则查系统记录的本进程退出原因。 */
    fun cause(
        context: Context,
        interruptedAt: Long,
        now: Long = System.currentTimeMillis(),
        elapsedRealtime: Long = SystemClock.elapsedRealtime(),
    ): String {
        if (now - elapsedRealtime > interruptedAt) return "设备重启"
        val exit = runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(null, 0, 16)
        }.getOrNull().orEmpty()
            .filter { it.processName == context.packageName && it.timestamp >= interruptedAt - EXIT_SLACK_MS }
            .minByOrNull { it.timestamp }
        return exit?.let { describe(it.reason) } ?: "Eta 进程结束"
    }

    fun describe(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_USER_REQUESTED -> "Eta 被强行停止"
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "Eta 崩溃"
        ApplicationExitInfo.REASON_ANR -> "Eta 无响应，被系统结束"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足，回收了 Eta"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Eta 进程自行退出"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "Eta 更新安装"
        else -> "Eta 进程被系统结束"
    }

    private fun interruptedCalls(transcript: List<AgentModelClient.ConversationMessage>): List<String> {
        val interruptedIds = transcript
            .filter { it.role == "tool" && it.content.contains(INTERRUPTED_CODE) }
            .mapTo(linkedSetOf()) { it.toolCallId }
        if (interruptedIds.isEmpty()) return emptyList()
        return toolCalls(transcript).filter { it.id in interruptedIds }.map { call ->
            val arguments = call.argumentsJson.trim().let {
                if (it.length > MAX_ARGUMENT_CHARS) it.take(MAX_ARGUMENT_CHARS) + "…" else it
            }
            if (arguments.isEmpty() || arguments == "{}") call.name else "${call.name} $arguments"
        }
    }

    private fun inProgressPlanStep(transcript: List<AgentModelClient.ConversationMessage>): String? {
        val plan = toolCalls(transcript).lastOrNull { it.name == PLAN_TOOL } ?: return null
        val steps = runCatching { JSONObject(plan.argumentsJson).optJSONArray("plan") }.getOrNull() ?: return null
        return (0 until steps.length())
            .mapNotNull { steps.optJSONObject(it) }
            .firstOrNull { it.optString("status") == "in_progress" }
            ?.optString("step")
            ?.takeIf(String::isNotBlank)
    }

    /** 最后一轮的回复还没进记录（流式输出到一半）时，从检查点事件里取已经输出的文字。 */
    private fun partialReply(
        runId: String,
        transcript: List<AgentModelClient.ConversationMessage>,
        events: List<AgentEvent>,
    ): String? {
        val start = events.indexOfLast { it is AgentEvent.RoundStarted }
        if (start < 0) return null
        val round = (events[start] as AgentEvent.RoundStarted).round
        if (transcript.any { it.role == "assistant" && it.messageId == "assistant-$runId-$round" }) return null
        val text = events.subList(start + 1, events.size)
            .filterIsInstance<AgentEvent.AssistantBlockDelta>()
            .filter { it.round == round && it.kind == AgentEvent.AssistantBlockKind.TEXT }
            .joinToString("") { it.delta }
            .trim()
        if (text.isEmpty()) return null
        return if (text.length > MAX_PARTIAL_REPLY_CHARS) text.take(MAX_PARTIAL_REPLY_CHARS) + "…" else text
    }

    private fun toolCalls(transcript: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ToolCall> =
        transcript.filter { it.role == "assistant" && it.toolCallsJson.isNotBlank() }.flatMap { message ->
            val calls = runCatching { JSONArray(message.toolCallsJson) }.getOrNull() ?: return@flatMap emptyList()
            AgentConversationCodec.parseToolCalls(JSONObject().put("tool_calls", calls))
        }

    /** 进程退出记录与检查点最后更新时间之间允许的误差。 */
    private const val EXIT_SLACK_MS = 5_000L
}
