package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubagentDetailsTest {
    @Test
    fun transcriptBecomesThinkingTextAndToolRowsThatToolDetailsCanFind() {
        val call = JSONObject().put("id", "call_1").put("type", "function")
            .put("function", JSONObject().put("name", "terminal").put("arguments", """{"action":"exec","command":"df -h"}"""))
        val transcript = listOf(
            ConversationMessage(role = "user", content = "统计存储占用"),
            ConversationMessage(role = "assistant", reasoningContent = "先看分区", content = "我先查一下。", toolCallsJson = JSONArray().put(call).toString()),
            ConversationMessage(role = "tool", toolCallId = "call_1", content = """{"ok":true,"exit_code":0,"stdout":"/data 92%"}"""),
            ConversationMessage(role = "assistant", content = "/data 已用 92%。"),
        )

        val timeline = SubagentDetails.timeline(transcript)

        assertEquals(
            listOf("sub-thinking-1", "sub-assistant-1", "sub-tool-1-call_1", "sub-assistant-2"),
            timeline.map { it.id },
        )
        val tool = timeline[2] as ToolActivityMessageUi
        assertEquals("terminal", tool.toolName)
        assertEquals(ToolActivityStatusUi.Success, tool.status)
        assertEquals("df -h", tool.command)
        // 时间线的工具行 id 与主会话同一格式，工具详情能按调用 id 找回完整结果。
        assertEquals("""{"ok":true,"exit_code":0,"stdout":"/data 92%"}""",
            ToolCallDetails.find(transcript, timeline, tool)?.result)
    }

    @Test
    fun finishedSubagentsAreRestoredFromArgumentsAndResults() {
        val detail = ToolCallDetail(
            argumentsJson = """{"tasks":[{"task":"查日志"},{"task":"查网页"}]}""",
            result = """{"ok":true,"results":[{"index":1,"task":"查日志","ok":true,"result":"没有异常"},{"index":2,"task":"查网页","ok":false,"error":"超时"}]}""",
            sensitive = false,
        )
        val subagents = SubagentDetails.fromDetail(detail)
        assertEquals(listOf(ToolActivityStatusUi.Success, ToolActivityStatusUi.Failed), subagents.map { it.status })
        assertEquals(listOf("没有异常", "超时"), subagents.map { it.conclusion })
        assertEquals(listOf("查日志", "查网页"), subagents.map { it.task })
        assertEquals(2, subagents.first().total)
        assertEquals(emptyList<SubagentProgressUi>(), SubagentDetails.fromDetail(null))

        assertEquals("run-1" to "call_9", SubagentDetails.runAndCallId("run-1-tool-3-call_9"))
        assertNull(SubagentDetails.runAndCallId("assistant-run-1-3"))
    }
}
