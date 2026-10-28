package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.data.db.ConversationSubagentEntity
import io.github.mangi.eta.data.db.ConversationSubagentMailEntity
import io.github.mangi.eta.data.db.ConversationSubagentSummary
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        @Synchronized override fun interruptStale(ownerInstanceId: String, now: Long): List<ConversationSubagentSummary> {
            val stale = agents.values.filter { it.status == "running" && it.ownerInstanceId != ownerInstanceId }
            stale.forEach { agents[it.id] = it.copy(status = "interrupted") }
            return stale.map {
                ConversationSubagentSummary(it.id, it.conversationId, it.agentId, it.name, it.task, it.status, it.turn,
                    it.lastResult, it.lastError, it.createdAt)
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
            mail.filter { it.deliveredRunId == null && it.status != "interrupted" }.map { it.conversationId }.distinct()
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

    /** 像 AgentLoop 那样：模型写出最终回复后先封口，再交出结论。 */
    private fun sealThenAnswer(text: String): (SubagentTurn) -> SubagentTurnResult = { turn ->
        assertNull("子 Agent 写完结论后不能再消费排队消息", turn.controller.pollSteeringOrSeal())
        answer(text)(turn)
    }

    private fun nextTurn(): SubagentTurn = requireNotNull(runner.turns.poll(5, TimeUnit.SECONDS)) { "子 Agent 的回合没有开始" }

    private fun awaitStatus(status: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (manager.snapshot("conv").first().status != status && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(status, manager.snapshot("conv").first().status)
    }

    @Test
    fun spawnReturnsAtOnceAndTheConclusionArrivesAsANotice() {
        val parent = AgentRunController()
        manager.attachParent("conv", "run-1", parent)
        val spawned = manager.spawn(environment, task = "查日志", context = "", name = "log_scan", reason = "并行检索")
        assertTrue(spawned.getBoolean("ok"))
        assertEquals("sub_1", spawned.getString("agent_id"))
        assertEquals("running", spawned.getString("status"))
        val turn = nextTurn()
        assertTrue(turn.prompt.contains("查日志"))
        // 主 Agent 发来的消息不当作用户的话。
        assertEquals(AgentRunController.COORDINATOR_STEERING_LABEL, turn.controller.steeringLabel)

        runner.replies.put(answer("无异常"))
        val deadline = System.currentTimeMillis() + 5_000
        while (!parent.hasPendingNotices && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val notice = parent.pollNotices().single()
        assertTrue(notice.modelText.contains("无异常"))
        assertFalse(notice.modelText.contains("close_agent"))
        assertTrue(notice.display.contains("log_scan（sub_1） 完成：无异常"))
    }

    @Test
    fun sendContinuesTheSameAgentWithItsEarlierContext() {
        manager.spawn(environment, "查第一个目录", "", null, "")
        val first = nextTurn()
        runner.replies.put(answer("目录 A 有 3 个文件"))
        awaitStatus("completed")

        val sent = manager.send(environment, "sub_1", "再查第二个目录")
        assertEquals("started", sent.getString("delivery"))
        val second = nextTurn()
        assertEquals(2, second.turn)
        assertEquals(first.modelSessionId, second.modelSessionId)
        assertTrue(second.history.any { it.content == "目录 A 有 3 个文件" })
        runner.replies.put(answer("目录 B 有 1 个文件"))
    }

    @Test
    fun messageBetweenToolCallsStaysInTheCurrentTurn() {
        manager.spawn(environment, "查日志", "", null, "")
        val turn = nextTurn()
        assertEquals("queued", manager.send(environment, "sub_1", "顺便看 crash 缓冲区").getString("delivery"))
        // AgentLoop 在下一次请求模型前取走它，回合继续。
        assertEquals("顺便看 crash 缓冲区", turn.controller.pollSteeringMessage())
        runner.replies.put(sealThenAnswer("两处都没有异常"))
        awaitStatus("completed")
        assertNull(runner.turns.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(1, store.mail.size)
    }

    @Test
    fun messageAfterTheFinalReplyEndsTheTurnFirstAndStartsTheNext() {
        manager.spawn(environment, "查日志", "", null, "")
        val first = nextTurn()
        assertEquals("queued", manager.send(environment, "sub_1", "再查一下内核日志").getString("delivery"))
        // 模型已写出最终回复：封口时不消费排队消息，回合照常结束交出结论。
        runner.replies.put(sealThenAnswer("应用日志没有异常"))
        val second = nextTurn()
        assertEquals(2, second.turn)
        assertEquals("再查一下内核日志", second.prompt)
        assertTrue(second.history.any { it.content == "应用日志没有异常" })
        val firstMail = store.mail.single()
        assertEquals(1, firstMail.turn)
        assertEquals("completed", firstMail.status)
        assertTrue(firstMail.payload.contains("应用日志没有异常"))
        assertTrue(first.controller !== second.controller)
        runner.replies.put(answer("内核日志也正常"))
        awaitStatus("completed")
        assertEquals(listOf(1, 2), store.mail.map { it.turn })
    }

    @Test
    fun userMessagesAreLabelledAsTheUsersAndUserCanStopAndClose() {
        manager.spawn(environment, "查日志", "", null, "")
        val turn = nextTurn()
        assertEquals("queued", manager.sendFromUser("conv", "sub_1", "只看今天的").getString("delivery"))
        val steering = turn.controller.pollSteering()!!
        assertEquals("只看今天的", steering.text)
        assertEquals(AgentRunController.DEFAULT_STEERING_LABEL, steering.label)
        // 封口后才到的用户消息开下一回合时同样标明是用户说的。
        assertEquals("queued", manager.sendFromUser("conv", "sub_1", "再看昨天的").getString("delivery"))
        runner.replies.put(sealThenAnswer("今天没有异常"))
        val second = nextTurn()
        assertEquals("用户补充指令：再看昨天的", second.prompt)

        manager.stop("conv", "sub_1")
        assertTrue(second.controller.isCancelled)
        runner.replies.put { SubagentTurnResult(false, "已停止", it.history, 0, cancelled = it.controller.isCancelled) }
        awaitStatus("interrupted")
        manager.close("conv", "sub_1")
        assertEquals("closed", manager.snapshot("conv").single().status)
        assertEquals("SUBAGENT_CLOSED", manager.sendFromUser("conv", "sub_1", "还在吗").getString("code"))
    }

    @Test
    fun runningAgentsReportProgressWithoutTheirTranscript() {
        manager.spawn(environment, "查日志", "", null, "")
        val turn = nextTurn()
        val call = """[{"id":"c1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"/data/log.txt\"}"}}]"""
        turn.onProgress(turn.history + ConversationMessage("user", turn.prompt) +
            ConversationMessage("assistant", "先读应用日志", toolCallsJson = call))
        val agent = manager.list(environment, emptyList()).getJSONArray("agents").getJSONObject(0)
        assertEquals("running", agent.getString("status"))
        val progress = agent.getJSONObject("progress")
        assertEquals(1, progress.getInt("rounds_this_turn"))
        assertEquals("先读应用日志", progress.getString("latest_note"))
        assertTrue(progress.getString("latest_tool").startsWith("read_file"))
        assertEquals(0, progress.getInt("queued_messages"))
        runner.replies.put(answer("完成"))
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
    fun stoppingTheMainRunInterruptsRunningAgentsWithAQuietNotice() {
        val wakes = AtomicInteger()
        manager.wakeListener = { wakes.incrementAndGet() }
        manager.spawn(environment, "长任务", "", null, "")
        val turn = nextTurn()
        manager.stopRunning("conv")
        assertTrue(turn.controller.isCancelled)
        runner.replies.put { SubagentTurnResult(false, "已停止", it.history, 0, cancelled = it.controller.isCancelled) }

        awaitStatus("interrupted")
        val mail = store.mail.single()
        assertEquals("interrupted", mail.status)
        Thread.sleep(200)
        // 只有中断通知：不唤醒，下一次 run 再交给主 Agent。
        assertEquals(0, wakes.get())
        val next = AgentRunController()
        manager.attachParent("conv", "run-2", next)
        assertTrue(next.pollNotices().single().display.contains("sub_1 已中断"))
        // 之后仍可继续。
        assertEquals("started", manager.send(environment, "sub_1", "接着做").getString("delivery"))
        nextTurn()
        runner.replies.put(answer("完成"))
    }

    @Test
    fun ownedBackgroundCommandsReportBackAndStoppedOnesDoNotWake() {
        val parent = AgentRunController()
        manager.attachParent("conv", "run-1", parent)
        val finished = DetachedTask(
            id = "job_1", pid = 1, token = "t", command = "make", cwd = "/", identity = "root",
            environment = TerminalEnvironment.ANDROID, logPath = "/daemon/job_1.log", startedAt = 0, commandTask = true,
            description = "编译", completedAt = 5, exitCode = 0, ownerConversationId = "conv",
        )
        manager.notifyBackgroundCommand(finished, "BUILD SUCCESSFUL")
        val notice = parent.pollNotices().single()
        assertTrue(notice.modelText.contains("<background_command_notification>"))
        assertTrue(notice.modelText.contains("BUILD SUCCESSFUL"))
        assertFalse(notice.modelText.contains("send_agent"))
        assertEquals("后台命令 编译（job_1） 完成（退出码 0）", notice.display)

        manager.detachParent("conv", "run-1")
        val wakes = AtomicInteger()
        manager.wakeListener = { wakes.incrementAndGet() }
        manager.notifyBackgroundCommand(finished.copy(id = "job_2", endReason = "stopped", exitCode = null), "")
        Thread.sleep(200)
        assertEquals(0, wakes.get())
        manager.notifyBackgroundCommand(finished.copy(id = "job_3", exitCode = 2), "error: x")
        val deadline = System.currentTimeMillis() + 5_000
        while (wakes.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, wakes.get())
        // 没有归属会话的（子 Agent 或电脑端直调启动的）只发系统通知。
        val before = store.mail.size
        manager.notifyBackgroundCommand(finished.copy(id = "job_4", ownerConversationId = null), "")
        assertEquals(before, store.mail.size)
    }

    @Test
    fun runningAgentsAreLimitedPerConversation() {
        repeat(4) { manager.spawn(environment, "任务 $it", "", null, "") }
        val fifth = manager.spawn(environment, "任务 5", "", null, "")
        assertEquals("SUBAGENT_LIMIT", fifth.getString("code"))
        repeat(4) { nextTurn(); runner.replies.put(answer("ok")) }
    }

    @Test
    fun agentsLeftRunningByAnEarlierProcessBecomeInterruptedWithANoticeAndClosedOnesRejectMessages() {
        store.save(ConversationSubagentEntity(
            id = ConversationSubagentEntity.rowId("conv", "sub_1"), conversationId = "conv", agentId = "sub_1",
            name = "sub_1", task = "旧任务", status = "running", turn = 1, rounds = 3, lastResult = "", lastError = "",
            transcriptJson = "[]", modelSessionId = "session:sub:sub_1", ownerInstanceId = "process-0",
            createdRunId = "run-0", lastRunId = "run-0", createdAt = 0, updatedAt = 0,
        ))
        assertEquals("interrupted", manager.snapshot("conv").single().status)
        val mail = store.mail.single()
        assertEquals("interrupted", mail.status)
        assertTrue(store.conversationsWithUndeliveredMail().isEmpty())

        manager.close(environment, "sub_1")
        assertEquals("SUBAGENT_CLOSED", manager.send(environment, "sub_1", "还在吗").getString("code"))
        assertEquals("SUBAGENT_NOT_FOUND", manager.send(environment, "sub_9", "你好").getString("code"))
    }
}
