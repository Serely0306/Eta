package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubagentNoticeLoopTest {
    @Test
    fun pendingNoticeJoinsTheFirstRoundAsASystemNoticeNotAUserSupplement() {
        val controller = AgentRunController()
        assertTrue(controller.deliverNotice(notice("下载目录占 2 GB")))
        val events = mutableListOf<AgentEvent>()
        val response = AgentModelClient.complete(
            config = config(), prompt = "继续整理", operationId = "run-n", runController = controller,
            provider = provider { request ->
                val users = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                    .filter { it.optString("role") == "user" }
                val notice = users.last()
                assertEquals("notice-run-n-subagent-1", notice.getString("_eta_message_id"))
                assertTrue(notice.toString().contains("<subagent_notification>"))
                assertFalse(notice.toString().contains("用户补充指令"))
                reply("已收到子 Agent 的结论")
            },
            toolExecutor = { error("无需工具") },
            onEvent = events::add,
        )
        assertEquals("已收到子 Agent 的结论", response.content)
        assertEquals(listOf("sub_1 完成：下载目录占 2 GB"), events.filterIsInstance<AgentEvent.SubagentNotified>().map { it.display })
        assertTrue(response.transcript.any { it.messageId == "notice-run-n-subagent-1" })
    }

    @Test
    fun noticeArrivingWhileTheFinalAnswerIsWrittenKeepsTheRunGoingForOneMoreRound() {
        val controller = AgentRunController()
        var requests = 0
        val response = AgentModelClient.complete(
            config = config(), prompt = "派出后先回答", operationId = "run-m", runController = controller,
            provider = provider { request ->
                requests++
                if (requests == 1) {
                    // 模型写最终回答时子 Agent 恰好完成。
                    assertTrue(controller.deliverNotice(notice("目录 B 有 1 个文件")))
                    reply("先回答一半")
                } else {
                    assertTrue(request.messages.toString().contains("目录 B 有 1 个文件"))
                    reply("结合子 Agent 的结论完成")
                }
            },
            toolExecutor = { error("无需工具") },
        )
        assertEquals(2, requests)
        assertEquals("结合子 Agent 的结论完成", response.content)
        // 回答结束后封口：之后到达的通知不再被接受，由子 Agent 管理器留到下一个 run。
        assertFalse(controller.deliverNotice(notice("晚到的结论")))
    }

    private fun notice(text: String) = AgentRunController.Notice(
        modelText = "<subagent_notification>\n{\"agent_id\":\"sub_1\",\"result\":\"$text\"}\n</subagent_notification>",
        display = "sub_1 完成：$text",
        mailIds = listOf(1L),
    )

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
