package io.github.mangi.eta.agent.model

import android.app.ApplicationExitInfo
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentInterruptionNote
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInterruptionContinuityTest {
    @Test
    fun replyWithToolCallsReachesTheCheckpointBeforeTheFirstToolRuns() {
        val transcripts = mutableListOf<List<ConversationMessage>>()
        var publishedAtExecution: List<ConversationMessage>? = null
        var round = 0
        val provider = provider {
            if (round++ == 0) {
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "现在强停 Eta")
                    .put("finish_reason", "tool_calls")
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-stop").put("type", "function")
                        .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}")))))
            } else {
                reply("完成")
            }
        }
        AgentModelClient.complete(
            config = config(), prompt = "强停 Eta", operationId = "run-x",
            provider = provider, onTranscript = transcripts::add,
            toolExecutor = {
                publishedAtExecution = transcripts.lastOrNull()
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
        )

        // 第一个工具开始执行时，带调用的回复已经在检查点里。
        val published = requireNotNull(publishedAtExecution)
        val call = published.last()
        assertEquals("assistant", call.role)
        assertEquals("现在强停 Eta", call.content)
        assertTrue(call.toolCallsJson.contains("call-stop"))

        // 进程若在这里结束：恢复时补成“已中断”，说明里写明中断原因与正在执行的工具。
        val recovered = AgentToolBatchRecovery.completeInterrupted(published)
        assertTrue(recovered.last().content.contains("TOOL_INTERRUPTED"))
        val note = AgentInterruptionNote.build("run-x", 0L, "Eta 被强行停止", recovered, emptyList(), ZoneId.of("UTC"))
        assertEquals("assistant", note.role)
        assertEquals(AgentInterruptionNote.messageId("run-x"), note.messageId)
        assertTrue(note.content.contains("1970-01-01 00:00:00"))
        assertTrue(note.content.contains("Eta 被强行停止"))
        assertTrue(note.content.contains("中断时正在执行：get_current_context，结果未知"))
        assertTrue(note.content.contains("不要直接重做"))
    }

    @Test
    fun noteCarriesThePlanStepAndThePartialReplyOfTheUnfinishedRound() {
        val planArguments = JSONObject().put("plan", JSONArray()
            .put(JSONObject().put("step", "读取内存").put("status", "completed"))
            .put(JSONObject().put("step", "强行停止 Eta").put("status", "in_progress"))).toString()
        val transcript = listOf(
            ConversationMessage(role = "assistant", messageId = "assistant-run-y-1",
                toolCallsJson = JSONArray().put(JSONObject().put("id", "p1").put("type", "function")
                    .put("function", JSONObject().put("name", "update_plan").put("arguments", planArguments))).toString()),
            ConversationMessage(role = "tool", toolCallId = "p1", content = """{"ok":true}"""),
        )
        val events = listOf(
            AgentEvent.RoundStarted(1, 2),
            AgentEvent.RoundStarted(2, 4),
            AgentEvent.AssistantBlockDelta(2, AgentEvent.AssistantBlockKind.TEXT, 0, 3, "内存约"),
            AgentEvent.AssistantBlockDelta(2, AgentEvent.AssistantBlockKind.THINKING, 0, 2, "思考"),
            AgentEvent.AssistantBlockDelta(2, AgentEvent.AssistantBlockKind.TEXT, 0, 7, " 10 GiB"),
        )

        val note = AgentInterruptionNote.build("run-y", 0L, "设备重启", transcript, events, ZoneId.of("UTC")).content
        assertTrue(note.contains("被中断：设备重启"))
        assertTrue(note.contains("计划中进行中的步骤：强行停止 Eta"))
        assertTrue(note.contains("中断前已输出的回复：\n内存约 10 GiB"))
        assertFalse(note.contains("中断时正在执行："))

        // 那一轮已经进了记录时，不再重复半截回复。
        val recorded = transcript + ConversationMessage(role = "assistant", content = "内存约 10 GiB", messageId = "assistant-run-y-2")
        assertFalse(AgentInterruptionNote.build("run-y", 0L, "设备重启", recorded, events, ZoneId.of("UTC"))
            .content.contains("中断前已输出"))

        assertEquals("Eta 被强行停止", AgentInterruptionNote.describe(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("系统内存不足，回收了 Eta", AgentInterruptionNote.describe(ApplicationExitInfo.REASON_LOW_MEMORY))
    }

    @Test
    fun manualStopTakesTheUnrecordedRoundFromTheVisibleReplies() {
        val transcript = listOf(
            ConversationMessage(role = "assistant", content = "第一轮已记录", messageId = "assistant-run-z-1"),
        )
        val replies = listOf(
            "assistant-run-z-1-0" to "第一轮已记录",
            "assistant-run-z-2-0" to "第二轮说到一半",
            "assistant-other-2-0" to "别的 run",
        )
        assertEquals("第二轮说到一半", AgentInterruptionNote.partialReplyFromReplies("run-z", transcript, replies))

        val note = AgentInterruptionNote.build(
            runId = "run-z", interruptedAt = 0L, cause = "用户手动停止", transcript = transcript,
            zone = ZoneId.of("UTC"),
            partialReply = AgentInterruptionNote.partialReplyFromReplies("run-z", transcript, replies),
        ).content
        assertTrue(note.contains("被中断：用户手动停止"))
        assertTrue(note.contains("中断前已输出的回复：\n第二轮说到一半"))
    }

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "fixture",
        contextWindow = 128_000, systemPrompt = "fixture", browserTools = false,
    )

    private fun reply(text: String) = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", "stop"))

    private fun provider(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = OpenAiChatCompletionsProvider.capabilities
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
            block(request)
    }
}
