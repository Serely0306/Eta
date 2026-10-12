package io.github.mangi.eta.agent.mcp.server

import io.github.mangi.eta.agent.model.AgentUserAnswer
import io.github.mangi.eta.agent.model.AgentUserQuestion
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 电脑端把任务委托给 Eta 自己的 Agent：启动后立即返回任务 id，之后以有界长轮询取进度、
 * 待答问题与最终结果。任务在 Runtime 中以“电脑端”入口运行，同样受并发上限、屏幕租约约束，
 * 结束后归档进 Eta 的“电脑端”会话分组。同一 conversation_id 的任务共享上下文。
 */
internal class EtaMcpTaskManager(
    private val runner: Runner,
    private val now: () -> Long = System::currentTimeMillis,
    private val launch: (name: String, block: () -> Unit) -> Unit = { name, block ->
        thread(name = name, isDaemon = true) { block() }
    },
) {
    interface Runner {
        /** 阻塞执行一次 run，并维护该会话的历史。 */
        fun run(runId: String, conversationKey: String, prompt: String, onEvent: (AgentEvent) -> Unit): AgentRuntimeWire.RunResult
        fun cancel(runId: String)
        fun steer(runId: String, text: String): Boolean
        fun answer(runId: String, questionId: String, answer: AgentUserAnswer): Boolean
    }

    private enum class Status(val wire: String, val terminal: Boolean) {
        RUNNING("running", false),
        WAITING("waiting_for_answer", false),
        COMPLETED("completed", true),
        FAILED("failed", true),
        STOPPED("stopped", true),
    }

    private class Task(val id: String, val conversationKey: String) {
        val lock = ReentrantLock()
        val changed = lock.newCondition()
        var status = Status.RUNNING
        var currentTool: String? = null
        val steps = ArrayDeque<JSONObject>()
        var stepCount = 0
        var questionId: String? = null
        var question: AgentUserQuestion? = null
        var planJson: String? = null
        var result: String? = null
        var error: String? = null
        var finishedAt: Long? = null
        var stopRequested = false
    }

    private val tasks = LinkedHashMap<String, Task>()

    fun handles(name: String): Boolean = name in TOOL_NAMES

    fun call(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            START -> start(args)
            STATUS -> withTask(args) { task -> await(task, waitMs(args, DEFAULT_WAIT_SECONDS)) }
            ANSWER -> withTask(args) { task -> answer(task, args) }
            STEER -> withTask(args) { task -> steer(task, args) }
            STOP -> withTask(args) { task -> stop(task) }
            else -> failure("UNKNOWN_TOOL", "未知任务工具")
        }
    } catch (failure: IllegalArgumentException) {
        failure("INVALID_ARGUMENT", failure.message ?: "参数无效")
    }

    private fun start(args: JSONObject): JSONObject {
        val prompt = args.optString("prompt").trim()
        require(prompt.isNotEmpty()) { "prompt 不能为空" }
        require(prompt.length <= MAX_PROMPT_CHARS) { "prompt 超过 $MAX_PROMPT_CHARS 字" }
        val conversationKey = args.optString("conversation_id").trim().ifEmpty { UUID.randomUUID().toString() }
        require(CONVERSATION_ID.matches(conversationKey)) { "conversation_id 只能包含字母、数字、- 和 _，最长 64 字" }
        val waitMs = waitMs(args, DEFAULT_WAIT_SECONDS)
        val task = synchronized(tasks) {
            prune()
            tasks.values.firstOrNull { it.conversationKey == conversationKey && !it.lock.withLock { it.status.terminal } }
                ?.let { busy ->
                    return failure("CONVERSATION_BUSY", "该会话已有任务在执行，可等待它结束或先停止")
                        .put("task_id", busy.id)
                }
            Task(UUID.randomUUID().toString(), conversationKey).also { tasks[it.id] = it }
        }
        launch("eta-mcp-task") {
            val result = runCatching {
                runner.run(task.id, conversationKey, prompt) { event -> onEvent(task, event) }
            }.getOrElse { throwable ->
                AgentRuntimeWire.RunResult(task.id, ok = false, content = "", error = throwable.message ?: "任务启动失败")
            }
            finish(task, result)
        }
        return await(task, waitMs)
    }

    private fun onEvent(task: Task, event: AgentEvent) = task.lock.withLock {
        when (event) {
            is AgentEvent.ToolStarted -> task.currentTool = event.name
            is AgentEvent.HostedToolStarted -> task.currentTool = event.name
            is AgentEvent.ToolFinished -> {
                record(task, JSONObject().put("tool", event.name).put("ok", event.success ?: true)
                    .put("summary", event.resultSummary.take(MAX_SUMMARY_CHARS)))
            }
            is AgentEvent.HostedToolFinished -> record(task, JSONObject().put("tool", event.name).put("ok", event.success))
            is AgentEvent.PlanUpdated -> task.planJson = event.planJson
            is AgentEvent.UserQuestionAsked -> {
                task.questionId = event.questionId
                task.question = AgentUserQuestion.parse(event.questionJson)
                if (!task.status.terminal) task.status = Status.WAITING
            }
            is AgentEvent.UserQuestionResolved -> if (task.questionId == event.questionId) {
                task.questionId = null
                task.question = null
                if (task.status == Status.WAITING) task.status = Status.RUNNING
            }
            else -> return@withLock
        }
        task.changed.signalAll()
    }

    private fun record(task: Task, step: JSONObject) {
        task.currentTool = null
        task.stepCount += 1
        task.steps.addLast(step)
        while (task.steps.size > MAX_STEPS) task.steps.removeFirst()
    }

    private fun finish(task: Task, result: AgentRuntimeWire.RunResult) = task.lock.withLock {
        task.status = when {
            result.ok -> Status.COMPLETED
            task.stopRequested || result.error == STOPPED_ERROR -> Status.STOPPED
            else -> Status.FAILED
        }
        task.result = result.content.takeIf { result.ok }
        task.error = result.error.takeUnless { result.ok }
        task.currentTool = null
        task.questionId = null
        task.question = null
        task.finishedAt = now()
        task.changed.signalAll()
    }

    /** 等到任务需要处理（结束或待回答）或超时，返回当前快照。 */
    private fun await(task: Task, waitMs: Long, answeredQuestionId: String? = null): JSONObject = task.lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(waitMs)
        fun pending() = task.status == Status.RUNNING ||
            (answeredQuestionId != null && task.status == Status.WAITING && task.questionId == answeredQuestionId)
        while (pending() && remaining > 0) {
            remaining = try {
                task.changed.awaitNanos(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                0L
            }
        }
        snapshot(task)
    }

    private fun answer(task: Task, args: JSONObject): JSONObject {
        val (questionId, question) = task.lock.withLock { task.questionId to task.question }
        if (questionId == null) return failure("NO_PENDING_QUESTION", "任务当前没有待回答的问题").put("task_id", task.id)
        val requestedId = args.optString("question_id").trim()
        if (requestedId.isNotEmpty() && requestedId != questionId) {
            return failure("QUESTION_EXPIRED", "该问题已结束，请用 eta_task_status 查看当前问题").put("task_id", task.id)
        }
        val waitMs = waitMs(args, DEFAULT_ANSWER_WAIT_SECONDS)
        val text = args.optString("text").trim()
        val selections = args.optJSONArray("selections")
        val answer = when {
            text.isNotEmpty() -> AgentUserAnswer.Text(text.take(MAX_ANSWER_CHARS))
            selections != null && selections.length() > 0 -> AgentUserAnswer.Selected(parseSelections(selections, question))
            args.optBoolean("skip", false) -> AgentUserAnswer.Skipped
            else -> throw IllegalArgumentException("需要提供 text、selections 或 skip=true")
        }
        if (!runner.answer(task.id, questionId, answer)) return failure("RUNTIME_UNAVAILABLE", "无法把回答送达 Eta Runtime")
        // 回答送达到问题关闭之间仍是 waiting_for_answer，等这道题结束后再按常规条件等待。
        return await(task, waitMs, answeredQuestionId = questionId)
    }

    /** 单个问题可直接给标签数组；多个问题按顺序给二维数组。 */
    private fun parseSelections(raw: JSONArray, question: AgentUserQuestion?): List<List<String>> {
        val nested = raw.optJSONArray(0) != null
        val selections = if (nested) {
            (0 until raw.length()).map { index ->
                val item = raw.optJSONArray(index) ?: JSONArray()
                (0 until item.length()).map { item.optString(it) }.filter(String::isNotBlank)
            }
        } else {
            listOf((0 until raw.length()).map { raw.optString(it) }.filter(String::isNotBlank))
        }
        question?.let { require(selections.size <= it.items.size) { "selections 数量多于问题数量" } }
        return selections
    }

    private fun steer(task: Task, args: JSONObject): JSONObject {
        val text = args.optString("text").trim()
        require(text.isNotEmpty()) { "text 不能为空" }
        if (task.lock.withLock { task.status.terminal }) return failure("TASK_FINISHED", "任务已结束").put("task_id", task.id)
        val accepted = runner.steer(task.id, text.take(MAX_ANSWER_CHARS))
        if (!accepted) return failure("STEER_REJECTED", "任务正在收尾或已结束，补充未送达").put("task_id", task.id)
        return snapshot(task)
    }

    private fun stop(task: Task): JSONObject {
        val alreadyFinished = task.lock.withLock {
            task.stopRequested = true
            task.status.terminal
        }
        if (!alreadyFinished) runner.cancel(task.id)
        return task.lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(STOP_WAIT_MS)
            while (!task.status.terminal && remaining > 0) {
                remaining = try {
                    task.changed.awaitNanos(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    0L
                }
            }
            snapshot(task)
        }
    }

    private fun snapshot(task: Task): JSONObject = task.lock.withLock {
        JSONObject()
            .put("ok", true)
            .put("task_id", task.id)
            .put("conversation_id", task.conversationKey)
            .put("status", task.status.wire)
            .put("step_count", task.stepCount)
            .put("recent_steps", JSONArray(task.steps.toList()))
            .apply {
                task.currentTool?.let { put("current_tool", it) }
                task.planJson?.let { raw -> runCatching { JSONObject(raw) }.getOrNull()?.let { put("plan", it) } }
                task.questionId?.let { id ->
                    put("question", JSONObject().put("question_id", id).apply {
                        task.question?.let { put("questions", JSONObject(it.toJson()).getJSONArray("questions")) }
                    })
                }
                task.result?.let { put("result", it) }
                task.error?.let { put("error", it) }
                put("hint", hintFor(task.status))
            }
    }

    private fun hintFor(status: Status): String = when (status) {
        Status.RUNNING -> "任务仍在执行，用 eta_task_status 继续等待"
        Status.WAITING -> "Eta 在等待回答：用 eta_task_answer 回答，用户也可以直接在手机上回答"
        Status.COMPLETED -> "任务已完成，result 为 Eta 的最终回复；继续同一会话时传入相同 conversation_id"
        Status.FAILED -> "任务失败，error 给出原因"
        Status.STOPPED -> "任务已停止"
    }

    private fun withTask(args: JSONObject, block: (Task) -> JSONObject): JSONObject {
        val id = args.optString("task_id").trim()
        require(id.isNotEmpty()) { "task_id 不能为空" }
        val task = synchronized(tasks) { prune(); tasks[id] } ?: return failure("TASK_NOT_FOUND", "任务不存在或已过期")
        return block(task)
    }

    /** 已结束的任务保留一段时间供查询，总数超限时先淘汰最早结束的。调用方持有 tasks 锁。 */
    private fun prune() {
        val cutoff = now() - FINISHED_RETENTION_MS
        tasks.values.removeAll { task -> task.lock.withLock { task.finishedAt?.let { it < cutoff } == true } }
        while (tasks.size > MAX_TASKS) {
            val oldest = tasks.values.firstOrNull { task -> task.lock.withLock { task.status.terminal } } ?: break
            tasks.remove(oldest.id)
        }
    }

    private fun waitMs(args: JSONObject, defaultSeconds: Int): Long {
        val seconds = if (args.has("wait_seconds")) args.optInt("wait_seconds", defaultSeconds) else defaultSeconds
        require(seconds in 0..MAX_WAIT_SECONDS) { "wait_seconds 需在 0 到 $MAX_WAIT_SECONDS 之间" }
        return seconds * 1000L
    }

    private fun failure(code: String, message: String) =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    fun tools(): List<EtaMcpTool> = listOf(
        EtaMcpTool(
            name = START,
            description = "把一项任务交给手机上的 Eta Agent 执行（它会用自己的模型和设备工具完成，包括操作界面、终端、文件等），" +
                "适合需要多步操作或在手机上自主完成的事情；只需单个设备操作时直接调用对应工具更快。" +
                "立即返回 task_id，并最多等待 wait_seconds 秒：任务结束或需要回答问题时提前返回。" +
                "继续同一段对话时传入上次返回的 conversation_id。",
            inputSchema = schema(
                listOf("prompt"),
                "prompt" to JSONObject().put("type", "string").put("maxLength", MAX_PROMPT_CHARS)
                    .put("description", "交给 Eta 的任务描述，写清目标与完成标准"),
                "conversation_id" to JSONObject().put("type", "string").put("maxLength", 64)
                    .put("description", "可选；相同 id 的任务共享上下文，省略时新开会话"),
                "wait_seconds" to waitSchema(DEFAULT_WAIT_SECONDS),
            ),
        ),
        EtaMcpTool(
            name = STATUS,
            description = "查询委托任务的状态、最近步骤、待回答问题与最终结果；任务仍在执行时最多等待 wait_seconds 秒，结束或需要回答时提前返回。",
            inputSchema = schema(
                listOf("task_id"),
                "task_id" to JSONObject().put("type", "string"),
                "wait_seconds" to waitSchema(DEFAULT_WAIT_SECONDS),
            ),
            readOnly = true,
        ),
        EtaMcpTool(
            name = ANSWER,
            description = "回答委托任务中 Eta 提出的问题（status 为 waiting_for_answer 时）。text 为自由回答；" +
                "selections 为所选选项标签，单个问题给一维数组，多个问题按顺序给二维数组；skip=true 表示不回答。",
            inputSchema = schema(
                listOf("task_id"),
                "task_id" to JSONObject().put("type", "string"),
                "question_id" to JSONObject().put("type", "string").put("description", "可选；省略时回答当前问题"),
                "text" to JSONObject().put("type", "string").put("maxLength", MAX_ANSWER_CHARS),
                "selections" to JSONObject().put("type", "array"),
                "skip" to JSONObject().put("type", "boolean"),
                "wait_seconds" to waitSchema(DEFAULT_ANSWER_WAIT_SECONDS),
            ),
        ),
        EtaMcpTool(
            name = STEER,
            description = "向执行中的委托任务追加要求或纠正方向，Eta 会在下一步前读到。",
            inputSchema = schema(
                listOf("task_id", "text"),
                "task_id" to JSONObject().put("type", "string"),
                "text" to JSONObject().put("type", "string").put("maxLength", MAX_ANSWER_CHARS),
            ),
        ),
        EtaMcpTool(
            name = STOP,
            description = "停止委托任务。",
            inputSchema = schema(listOf("task_id"), "task_id" to JSONObject().put("type", "string")),
        ),
    )

    private fun waitSchema(default: Int) = JSONObject().put("type", "integer").put("minimum", 0)
        .put("maximum", MAX_WAIT_SECONDS).put("default", default)

    private fun schema(required: List<String>, vararg properties: Pair<String, JSONObject>) = JSONObject()
        .put("type", "object")
        .put("required", JSONArray(required))
        .put("properties", JSONObject().apply { properties.forEach { (key, value) -> put(key, value) } })

    internal companion object {
        const val START = "eta_task_start"
        const val STATUS = "eta_task_status"
        const val ANSWER = "eta_task_answer"
        const val STEER = "eta_task_steer"
        const val STOP = "eta_task_stop"
        val TOOL_NAMES = setOf(START, STATUS, ANSWER, STEER, STOP)

        const val MAX_WAIT_SECONDS = 90
        const val DEFAULT_WAIT_SECONDS = 30
        const val DEFAULT_ANSWER_WAIT_SECONDS = 10
        const val FINISHED_RETENTION_MS = 30 * 60 * 1000L
        const val MAX_TASKS = 30
        private const val MAX_STEPS = 20
        private const val MAX_SUMMARY_CHARS = 160
        private const val MAX_PROMPT_CHARS = 8_000
        private const val MAX_ANSWER_CHARS = 2_000
        private const val STOP_WAIT_MS = 5_000L
        private const val STOPPED_ERROR = "已停止"
        private val CONVERSATION_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
