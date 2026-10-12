package io.github.mangi.eta.agent.mcp.server

import io.github.mangi.eta.agent.model.AgentUserAnswer
import io.github.mangi.eta.agent.model.AgentUserQuestion
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaMcpTaskManagerTest {
    /** 每次 run 按测试脚本推进：脚本里的动作在运行线程上依次执行，最后返回结果。 */
    private class ScriptedRunner : EtaMcpTaskManager.Runner {
        val steps = LinkedBlockingQueue<(onEvent: (AgentEvent) -> Unit) -> AgentRuntimeWire.RunResult?>()
        val answers = LinkedBlockingQueue<AgentUserAnswer>()
        val cancelled = CountDownLatch(1)
        val prompts = mutableListOf<Pair<String, String>>()

        override fun run(
            runId: String,
            conversationKey: String,
            prompt: String,
            onEvent: (AgentEvent) -> Unit,
        ): AgentRuntimeWire.RunResult {
            synchronized(prompts) { prompts += conversationKey to prompt }
            while (true) {
                val step = steps.poll(5, TimeUnit.SECONDS) ?: return AgentRuntimeWire.RunResult(runId, false, "", "timeout")
                step(onEvent)?.let { return it.copy(runId = runId) }
            }
        }

        override fun cancel(runId: String) = cancelled.countDown()

        override fun steer(runId: String, text: String) = true

        override fun answer(runId: String, questionId: String, answer: AgentUserAnswer): Boolean {
            answers.put(answer)
            return true
        }
    }

    private val runner = ScriptedRunner()
    private val manager = EtaMcpTaskManager(runner)

    @After
    fun tearDown() {
        // 让仍在等待脚本的运行线程尽快结束。
        repeat(4) { runner.steps.put { AgentRuntimeWire.RunResult("", false, "", "test finished") } }
    }

    @Test
    fun startReturnsRunningThenStatusWaitsForCompletion() {
        val started = manager.call(EtaMcpTaskManager.START, JSONObject().put("prompt", "整理下载目录").put("wait_seconds", 0))
        assertTrue(started.getBoolean("ok"))
        assertEquals("running", started.getString("status"))
        val taskId = started.getString("task_id")

        runner.steps.put { onEvent ->
            onEvent(AgentEvent.ToolFinished(1, "call-1", "terminal", "ok exit=0", 0, 0, success = true))
            null
        }
        runner.steps.put { AgentRuntimeWire.RunResult("", ok = true, content = "已整理") }
        val finished = manager.call(EtaMcpTaskManager.STATUS, JSONObject().put("task_id", taskId).put("wait_seconds", 5))
        assertEquals("completed", finished.getString("status"))
        assertEquals("已整理", finished.getString("result"))
        assertEquals(1, finished.getInt("step_count"))
        assertEquals("terminal", finished.getJSONArray("recent_steps").getJSONObject(0).getString("tool"))
    }

    @Test
    fun questionsAreSurfacedAndAnsweredWithSelections() {
        val question = AgentUserQuestion(
            listOf(
                AgentUserQuestion.Item(
                    question = "保存到哪里？",
                    header = "",
                    options = listOf(AgentUserQuestion.Option("下载", ""), AgentUserQuestion.Option("文档", "")),
                    multiSelect = false,
                ),
            ),
        )
        runner.steps.put { onEvent ->
            onEvent(AgentEvent.UserQuestionAsked("q-1", "call-1", question.toJson(), 600_000))
            null
        }
        val started = manager.call(EtaMcpTaskManager.START, JSONObject().put("prompt", "保存文件").put("wait_seconds", 5))
        assertEquals("waiting_for_answer", started.getString("status"))
        val asked = started.getJSONObject("question")
        assertEquals("q-1", asked.getString("question_id"))
        assertEquals("保存到哪里？", asked.getJSONArray("questions").getJSONObject(0).getString("question"))

        // 第二步在运行线程上等回答送达，再像 Runtime 一样发出 Resolved 并结束。
        val received = AtomicReference<AgentUserAnswer?>()
        runner.steps.put { onEvent ->
            received.set(runner.answers.poll(5, TimeUnit.SECONDS))
            onEvent(AgentEvent.UserQuestionResolved("q-1", AgentUserAnswer.STATUS_ANSWERED))
            AgentRuntimeWire.RunResult("", ok = true, content = "已保存到下载")
        }
        val answered = manager.call(
            EtaMcpTaskManager.ANSWER,
            JSONObject().put("task_id", started.getString("task_id")).put("selections", JSONArray().put("下载"))
                .put("wait_seconds", 5),
        )
        assertEquals("completed", answered.getString("status"))
        assertEquals(AgentUserAnswer.Selected(listOf(listOf("下载"))), received.get())
        assertFalse(answered.has("question"))
    }

    @Test
    fun sameConversationCannotRunTwoTasksAtOnce() {
        val first = manager.call(
            EtaMcpTaskManager.START,
            JSONObject().put("prompt", "任务一").put("conversation_id", "pc-1").put("wait_seconds", 0),
        )
        val second = manager.call(
            EtaMcpTaskManager.START,
            JSONObject().put("prompt", "任务二").put("conversation_id", "pc-1").put("wait_seconds", 0),
        )
        assertFalse(second.getBoolean("ok"))
        assertEquals("CONVERSATION_BUSY", second.getString("code"))
        assertEquals(first.getString("task_id"), second.getString("task_id"))
        assertEquals("pc-1", first.getString("conversation_id"))
    }

    @Test
    fun stopCancelsRunAndReportsStopped() {
        val started = manager.call(EtaMcpTaskManager.START, JSONObject().put("prompt", "长任务").put("wait_seconds", 0))
        val taskId = started.getString("task_id")
        Thread {
            if (runner.cancelled.await(5, TimeUnit.SECONDS)) {
                runner.steps.put { AgentRuntimeWire.RunResult("", ok = false, content = "", error = "已停止") }
            }
        }.start()
        val stopped = manager.call(EtaMcpTaskManager.STOP, JSONObject().put("task_id", taskId))
        assertEquals("stopped", stopped.getString("status"))
    }

    @Test
    fun invalidArgumentsAndUnknownTasksFailWithCodes() {
        fun code(name: String, args: JSONObject) = manager.call(name, args).getString("code")
        assertEquals("INVALID_ARGUMENT", code(EtaMcpTaskManager.START, JSONObject()))
        assertEquals("INVALID_ARGUMENT", code(EtaMcpTaskManager.START, JSONObject().put("prompt", "x").put("conversation_id", "含空格 的id")))
        assertEquals("INVALID_ARGUMENT", code(EtaMcpTaskManager.START, JSONObject().put("prompt", "x").put("wait_seconds", 999)))
        assertEquals("TASK_NOT_FOUND", code(EtaMcpTaskManager.STATUS, JSONObject().put("task_id", "missing")))
        assertTrue(runner.prompts.isEmpty())
    }

    @Test
    fun answeringWithoutPendingQuestionIsRejected() {
        val started = manager.call(EtaMcpTaskManager.START, JSONObject().put("prompt", "任务").put("wait_seconds", 0))
        val result = manager.call(
            EtaMcpTaskManager.ANSWER,
            JSONObject().put("task_id", started.getString("task_id")).put("text", "好的"),
        )
        assertEquals("NO_PENDING_QUESTION", result.getString("code"))
    }

    @Test
    fun toolCatalogCoversAllTaskTools() {
        assertEquals(EtaMcpTaskManager.TOOL_NAMES, manager.tools().map { it.name }.toSet())
        manager.tools().forEach { tool -> assertEquals("object", tool.inputSchema.getString("type")) }
    }
}
