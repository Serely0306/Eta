package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.data.db.ConversationSubagentEntity
import io.github.mangi.eta.data.db.ConversationSubagentMailEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubagentManagerTest {
    private class MemoryStore : SubagentStore {
        val agents = LinkedHashMap<String, ConversationSubagentEntity>()
        val mail = mutableListOf<ConversationSubagentMailEntity>()
        private var nextMailId = 1L

        @Synchronized override fun agents(conversationId: String) = agents.values.filter { it.conversationId == conversationId }
        @Synchronized override fun save(agent: ConversationSubagentEntity) { agents[agent.id] = agent }
        @Synchronized override fun interruptStale(ownerInstanceId: String, now: Long) {
            agents.replaceAll { _, row ->
                if (row.status == "running" && row.ownerInstanceId != ownerInstanceId) row.copy(status = "interrupted") else row
            }
        }
        @Synchronized override fun addMail(mail: ConversationSubagentMailEntity) { this.mail += mail.copy(id = nextMailId++) }
        @Synchronized override fun undeliveredMail(conversationId: String) =
            mail.filter { it.conversationId == conversationId && it.deliveredRunId == null }
        @Synchronized override fun markDelivered(ids: List<Long>, runId: String?) {
            mail.replaceAll { if (it.id in ids) it.copy(deliveredRunId = runId) else it }
        }
        @Synchronized override fun deleteConversation(conversationId: String) {
            agents.values.removeAll { it.conversationId == conversationId }
            mail.removeAll { it.conversationId == conversationId }
        }
        @Synchronized override fun conversationsWithUndeliveredMail() =
            mail.filter { it.deliveredRunId == null }.map { it.conversationId }.distinct()
    }

    /** 每个回合把自己交出来，等测试给出结果。 */
    private class ScriptedRunner : SubagentTurnRunner {
        val turns = LinkedBlockingQueue<SubagentTurn>()
        val replies = LinkedBlockingQueue<(SubagentTurn) -> SubagentTurnResult>()

        override fun run(turn: SubagentTurn): SubagentTurnResult {
            turns.put(turn)
            val reply = replies.poll(5, TimeUnit.SECONDS) ?: return SubagentTurnResult(false, "测试超时", turn.history, 0)
            return reply(turn)
        }
    }

    private val store = MemoryStore()
    private val runner = ScriptedRunner()
    private val manager = AgentSubagentManager(store, runner, ownerInstanceId = "process-1", now = { 1L })
    private val environment = SubagentEnvironment(
        runId = "run-1",
        conversationId = "conv",
        parentSessionId = "session",
        config = AgentModelClient.ModelConfig(
            baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "fixture", systemPrompt = "fixture",
        ),
        capabilitiesProvider = { error("脚本回合不读取能力") },
    )

    private fun answer(text: String): (SubagentTurn) -> SubagentTurnResult = { turn ->
        SubagentTurnResult(
            ok = true, text = text, rounds = 1,
            history = turn.history + ConversationMessage("user", turn.prompt) + ConversationMessage("assistant", text),
        )
    }

    private fun nextTurn(): SubagentTurn = requireNotNull(runner.turns.poll(5, TimeUnit.SECONDS)) { "子 Agent 的回合没有开始" }

    @Test
    fun spawnReturnsAtOnceAndWaitTakesTheResultWithoutASecondNotice() {
        val parent = AgentRunController()
        manager.attachParent("conv", "run-1", parent)
        val spawned = manager.spawn(environment, task = "查日志", context = "", name = "log_scan", reason = "并行检索")
        assertTrue(spawned.getBoolean("ok"))
        assertEquals("sub_1", spawned.getString("agent_id"))
        assertEquals("running", spawned.getString("status"))
        assertTrue(nextTurn().prompt.contains("查日志"))

        thread { Thread.sleep(200); runner.replies.put(answer("无异常")) }
        val waited = manager.wait(environment, listOf("log_scan"), 5_000, parent)

        assertEquals("无异常", waited.getJSONArray("agents").getJSONObject(0).getString("result"))
        assertFalse(waited.getBoolean("timed_out"))
        assertFalse(parent.hasPendingNotices)
        assertTrue(store.undeliveredMail("conv").isEmpty())
    }

    @Test
    fun sendContinuesTheSameAgentWithItsEarlierContext() {
        val parent = AgentRunController()
        manager.spawn(environment, "查第一个目录", "", null, "")
        val first = nextTurn()
        thread { Thread.sleep(100); runner.replies.put(answer("目录 A 有 3 个文件")) }
        manager.wait(environment, listOf("sub_1"), 5_000, parent)

        val sent = manager.send(environment, "sub_1", "再查第二个目录", interrupt = false)
        assertEquals("started", sent.getString("delivery"))
        val second = nextTurn()
        assertEquals(2, second.turn)
        assertEquals(first.modelSessionId, second.modelSessionId)
        assertTrue(second.history.any { it.content == "目录 A 有 3 个文件" })
        runner.replies.put(answer("目录 B 有 1 个文件"))
    }

    @Test
    fun resultWhileTheMainRunIsBusyBecomesANoticeAndLeftoversReturnWhenTheRunEnds() {
        val parent = AgentRunController()
        val woken = CountDownLatch(1)
        manager.wakeListener = { if (it == "conv") woken.countDown() }
        manager.attachParent("conv", "run-1", parent)
        manager.spawn(environment, "统计占用", "", null, "")
        nextTurn()
        runner.replies.put(answer("下载目录占 2 GB"))

        val deadline = System.currentTimeMillis() + 5_000
        while (!parent.hasPendingNotices && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(parent.hasPendingNotices)
        assertEquals("run-1", store.mail.single().deliveredRunId)

        // 主 run 没来得及把通知交给模型就结束：通知恢复为未投递，并唤醒主 Agent。
        manager.detachParent("conv", "run-1")
        assertNull(store.mail.single().deliveredRunId)
        assertTrue(woken.await(5, TimeUnit.SECONDS))

        // 下一个 run 开始时交给它。
        val next = AgentRunController()
        manager.attachParent("conv", "run-2", next)
        val notice = next.pollNotices().single()
        assertTrue(notice.modelText.contains("<subagent_notification>"))
        assertTrue(notice.display.contains("sub_1 完成：下载目录占 2 GB"))
        assertEquals("run-2", store.mail.single().deliveredRunId)
    }

    @Test
    fun stoppingTheMainRunInterruptsRunningAgentsWithoutANotice() {
        manager.spawn(environment, "长任务", "", null, "")
        val turn = nextTurn()
        manager.stopRunning("conv")
        assertTrue(turn.controller.isCancelled)
        runner.replies.put { SubagentTurnResult(false, "已停止", it.history, 0, cancelled = it.controller.isCancelled) }

        val deadline = System.currentTimeMillis() + 5_000
        while (manager.snapshot("conv").single().status == "running" && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("interrupted", manager.snapshot("conv").single().status)
        assertTrue(store.mail.isEmpty())
        // 之后仍可继续。
        assertEquals("started", manager.send(environment, "sub_1", "接着做", interrupt = false).getString("delivery"))
        nextTurn()
        runner.replies.put(answer("完成"))
    }

    @Test
    fun runningAgentsAreLimitedPerConversation() {
        repeat(4) { manager.spawn(environment, "任务 $it", "", null, "") }
        val fifth = manager.spawn(environment, "任务 5", "", null, "")
        assertEquals("SUBAGENT_LIMIT", fifth.getString("code"))
        repeat(4) { nextTurn(); runner.replies.put(answer("ok")) }
    }

    @Test
    fun agentsLeftRunningByAnEarlierProcessBecomeInterruptedAndClosedOnesRejectMessages() {
        store.save(ConversationSubagentEntity(
            id = ConversationSubagentEntity.rowId("conv", "sub_1"), conversationId = "conv", agentId = "sub_1",
            name = "sub_1", task = "旧任务", status = "running", turn = 1, rounds = 3, lastResult = "", lastError = "",
            transcriptJson = "[]", modelSessionId = "session:sub:sub_1", ownerInstanceId = "process-0",
            createdRunId = "run-0", lastRunId = "run-0", createdAt = 0, updatedAt = 0,
        ))
        assertEquals("interrupted", manager.snapshot("conv").single().status)

        manager.close(environment, "sub_1")
        assertEquals("SUBAGENT_CLOSED", manager.send(environment, "sub_1", "还在吗", interrupt = false).getString("code"))
        assertEquals("SUBAGENT_NOT_FOUND", manager.send(environment, "sub_9", "你好", interrupt = false).getString("code"))
    }
}
