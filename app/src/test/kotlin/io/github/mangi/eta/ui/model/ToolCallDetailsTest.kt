package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallDetailsTest {
    @Test
    fun repeatedCallIdsAreMatchedByOrder() {
        // 有的服务商每轮都从 call_0 编号；第二次调用要对应 journal 里的第二条结果。
        val journal = listOf(
            assistantCall("call_0", "terminal", """{"command":"ls"}"""),
            ConversationMessage(role = "tool", toolCallId = "call_0", content = """{"stdout":"first"}"""),
            assistantCall("call_0", "terminal", """{"command":"pwd"}"""),
            ConversationMessage(role = "tool", toolCallId = "call_0", content = """{"stdout":"second"}"""),
        )
        val first = tool("run-a-tool-1-call_0")
        val second = tool("run-b-tool-1-call_0")

        val detail = ToolCallDetails.find(journal, listOf(first, second), second)!!

        assertEquals("""{"command":"pwd"}""", detail.argumentsJson)
        assertEquals("""{"stdout":"second"}""", detail.result)
        assertFalse(detail.sensitive)
    }

    @Test
    fun sensitiveToolsExposeNothingAndMissingEntriesReturnNull() {
        val journal = listOf(
            assistantCall("call_s", "sms_query", JSONObject().put("redacted", true).toString()),
            ConversationMessage(role = "tool", toolCallId = "call_s", content = AgentConversationCodec.SENSITIVE_TOOL_OMITTED_TEXT),
        )
        val sensitive = tool("run-tool-2-call_s")
        val detail = ToolCallDetails.find(journal, listOf(sensitive), sensitive)!!
        assertTrue(detail.sensitive)
        assertNull(detail.argumentsJson)
        assertNull(detail.result)

        val missing = tool("run-tool-3-call_x")
        assertNull(ToolCallDetails.find(journal, listOf(missing), missing))
        assertNull(ToolCallDetails.toolCallId("run-tool-3-unknown"))
    }

    @Test
    fun runIdIsTakenFromToolMessageIdForCheckpointReads() {
        assertEquals("6f1c-run-a", ToolCallDetails.runId("6f1c-run-a-tool-12-call_0"))
        assertEquals("run", ToolCallDetails.runId("run-tool-1-call-with-dashes"))
        assertNull(ToolCallDetails.runId("assistant-run-1"))

        // 运行中只在本 run 的检查点里找：序号按本 run 的工具行数，前一个 run 的同名调用不占位。
        val checkpoint = listOf(
            assistantCall("call_0", "terminal", """{"command":"pwd"}"""),
            ConversationMessage(role = "tool", toolCallId = "call_0", content = """{"stdout":"live"}"""),
        )
        val earlier = tool("run-a-tool-1-call_0")
        val current = tool("run-b-tool-1-call_0")
        val runMessages = listOf(earlier, current).filter { it.id.startsWith("run-b-tool-") }
        assertEquals("""{"stdout":"live"}""", ToolCallDetails.find(checkpoint, runMessages, current)!!.result)
    }

    @Test
    fun terminalSearchAndPagePayloadsAreParsed() {
        val terminal = ToolCallDetails.terminalOutput(
            """{"exit_code":2,"timed_out":false,"stdout":"a\nb","stderr":null,"stdout_truncated":true,"duration_ms":1400}""",
        )!!
        assertEquals(2, terminal.exitCode)
        assertEquals("a\nb", terminal.stdout)
        assertEquals("", terminal.stderr)
        assertTrue(terminal.truncated)
        assertEquals(1_400L, terminal.durationMs)
        assertNull(ToolCallDetails.terminalOutput("""{"ok":true}"""))
        val job = ToolCallDetails.terminalOutput(
            """{"action":"read","exit_code":0,"output":"built\n","output_truncated":true,"log_path":"/x/job.log"}""",
        )!!
        assertEquals("built\n", job.stdout)
        assertTrue(job.truncated)

        val results = ToolCallDetails.searchResults(
            """{"results":[{"title":"T","url":"https://example.com/a","snippet":"S"},{"title":"","url":""}]}""",
        )
        assertEquals(listOf(WebSearchResult("T", "https://example.com/a", "S")), results)
        assertEquals("存储 清理", ToolCallDetails.searchQuery("""{"query":"存储 清理"}"""))

        val page = ToolCallDetails.webPage("""{"final_url":"https://example.com","title":null,"text":"body","has_more":true}""")!!
        assertEquals(WebPageContent("https://example.com", "", "body", true), page)
    }

    @Test
    fun prettyPrintingKeepsSlashesAndFallsBackToRawText() {
        val pretty = ToolCallDetails.pretty("""{"command":"df -h /data","note":"中文"}""")
        assertTrue(pretty.contains("\"command\": \"df -h /data\""))
        assertTrue(pretty.contains("中文"))
        assertEquals("plain text", ToolCallDetails.pretty("plain text"))
    }

    private fun tool(id: String) = ToolActivityMessageUi(
        id = id,
        toolName = "terminal",
        status = ToolActivityStatusUi.Success,
        argumentsSummary = "",
    )

    private fun assistantCall(id: String, name: String, arguments: String) = ConversationMessage(
        role = "assistant",
        toolCallsJson = JSONArray().put(
            JSONObject().put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", arguments)),
        ).toString(),
    )
}
