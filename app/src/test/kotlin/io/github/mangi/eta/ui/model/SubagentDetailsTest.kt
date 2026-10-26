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

        // 运行中的快照：工具还没出结果时显示执行中；结束后的记录显示结果未知。
        val inFlight = transcript.take(2)
        assertEquals(ToolActivityStatusUi.Running,
            (SubagentDetails.timeline(inFlight, running = true).last() as ToolActivityMessageUi).status)
        assertEquals(ToolActivityStatusUi.Unknown,
            (SubagentDetails.timeline(inFlight).last() as ToolActivityMessageUi).status)
    }

    @Test
    fun conversationListsLiveAndFinishedSubagentsInCallOrder() {
        val call = JSONObject().put("id", "call_s").put("type", "function").put("function", JSONObject()
            .put("name", "run_subagents").put("arguments", """{"tasks":[{"task":"查日志"},{"task":"查网页"}]}"""))
        val journal = listOf(
            ConversationMessage(role = "assistant", toolCallsJson = JSONArray().put(call).toString()),
            ConversationMessage(role = "tool", toolCallId = "call_s",
                content = """{"ok":true,"results":[{"index":1,"ok":true,"result":"无异常"},{"index":2,"ok":false,"error":"超时"}]}"""),
        )
        val finished = ToolActivityMessageUi("run-a-tool-1-call_s", "run_subagents", ToolActivityStatusUi.Success, "")
        val live = ToolActivityMessageUi(
            "run-b-tool-2-call_t", "run_subagents", ToolActivityStatusUi.Running, "",
            subagents = listOf(SubagentProgressUi(0, 1, ToolActivityStatusUi.Running, "统计占用")),
        )
        val other = ToolActivityMessageUi("run-b-tool-1-call_x", "terminal", ToolActivityStatusUi.Success, "")

        val subagents = SubagentDetails.forConversation(listOf(finished, other, live), journal)

        assertEquals(listOf("run-a", "run-a", "run-b"), subagents.map { it.runId })
        assertEquals(listOf("call_s", "call_s", "call_t"), subagents.map { it.toolCallId })
        assertEquals(listOf("查日志", "查网页", "统计占用"), subagents.map { it.progress.task })
        assertEquals(
            listOf(ToolActivityStatusUi.Success, ToolActivityStatusUi.Failed, ToolActivityStatusUi.Running),
            subagents.map { it.progress.status },
        )
    }

    @Test
    fun sessionSubagentsFromTheRegistryAreGroupedPerConversationInSpawnOrder() {
        fun row(conversation: String, agent: String, name: String, status: String, result: String = "", error: String = "") =
            io.github.mangi.eta.data.db.ConversationSubagentSummary(
                id = "$conversation|$agent", conversationId = conversation, agentId = agent, name = name,
                task = "任务 $agent", status = status, turn = 1, lastResult = result, lastError = error, createdAt = 1,
            )
        val grouped = SubagentDetails.fromRegistry(listOf(
            row("conv-a", "sub_1", "log_scan", "completed", result = "无异常"),
            row("conv-a", "sub_2", "sub_2", "running"),
            row("conv-b", "sub_1", "sub_1", "interrupted", error = ""),
            row("conv-a", "sub_3", "sub_3", "failed", error = "超时"),
        ))

        val a = grouped.getValue("conv-a")
        assertEquals(listOf("sub_1", "sub_2", "sub_3"), a.map { it.agentId })
        assertEquals(listOf("log_scan · 任务 sub_1", "任务 sub_2", "任务 sub_3"), a.map { it.progress.task })
        assertEquals(
            listOf(ToolActivityStatusUi.Success, ToolActivityStatusUi.Running, ToolActivityStatusUi.Failed),
            a.map { it.progress.status },
        )
        assertEquals(listOf("无异常", "", "超时"), a.map { it.progress.conclusion })
        assertEquals(ToolActivityStatusUi.Unknown, grouped.getValue("conv-b").single().progress.status)
        assertEquals("conv-b", grouped.getValue("conv-b").single().conversationId)
    }

    @Test
    fun earlierBatchesOutsideTheLoadedPageComeFromStoredToolRows() {
        // 三批都用 call_0（有的服务商每轮从 call_0 编号）；只加载了最后一页，前两批只在库里。
        fun batch(task: String, result: String) = listOf(
            ConversationMessage(role = "assistant", toolCallsJson = JSONArray().put(JSONObject().put("id", "call_0")
                .put("type", "function").put("function", JSONObject().put("name", "run_subagents")
                    .put("arguments", JSONObject().put("tasks", JSONArray().put(JSONObject().put("task", task))).toString())))
                .toString()),
            ConversationMessage(role = "tool", toolCallId = "call_0",
                content = JSONObject().put("ok", true).put("results", JSONArray().put(JSONObject().put("ok", true).put("result", result))).toString()),
        )
        val journal = batch("第一批", "一") + batch("第二批", "二") + batch("第三批", "三")
        fun row(run: String) = ToolActivityMessageUi("$run-tool-1-call_0", "run_subagents", ToolActivityStatusUi.Unknown, "")
        val stored = listOf(row("run-1"), row("run-2"), row("run-3"))
        val loaded = listOf(ToolActivityMessageUi("run-3-tool-1-call_0", "run_subagents", ToolActivityStatusUi.Success, ""))

        val subagents = SubagentDetails.forConversation(loaded, journal, stored)

        assertEquals(listOf("run-1", "run-2", "run-3"), subagents.map { it.runId })
        assertEquals(listOf("第一批", "第二批", "第三批"), subagents.map { it.progress.task })
        assertEquals(listOf("一", "二", "三"), subagents.map { it.progress.conclusion })
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
