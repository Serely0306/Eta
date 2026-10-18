package io.github.mangi.eta.agent.mcp.server

import io.github.mangi.eta.agent.model.AgentUserAnswer
import io.github.mangi.eta.agent.model.AgentUserQuestion
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
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
    /** 每次列出工具与启动任务时读取，用户在手机上修改勾选后立即生效。 */
    private val models: () -> ModelChoices = { ModelChoices.DEFAULT_MODEL },
) {
    /**
     * [efforts] 为该模型可选的思考强度（wire 值），为空表示模型不支持思考；
     * [defaultEffort] 为电脑端不指定时实际使用的强度。
     */
    data class ModelOption(
        val id: String,
        val label: String,
        val efforts: List<String> = emptyList(),
        val defaultEffort: String? = null,
    )

    /**
     * 委托任务可用的模型。[restricted] 为 false 时使用 Eta 的默认模型（[defaultModel] 描述它的强度选项）；
     * 为 true 时只能用 [options] 中的模型，第一个为默认，[options] 为空表示勾选的模型都已停用或删除。
     * [thinkingAllowed] 为 false 时手机上关闭了思考，所有任务都不思考。
     */
    data class ModelChoices(
        val restricted: Boolean,
        val options: List<ModelOption>,
        val defaultModel: ModelOption? = null,
        val thinkingAllowed: Boolean = true,
    ) {
        companion object {
            val DEFAULT_MODEL = ModelChoices(restricted = false, options = emptyList())
        }
    }

    interface Runner {
        /**
         * 阻塞执行一次 run，并维护该会话的历史；[modelId] 为 null 时使用 Eta 的默认模型，
         * [reasoningEffort] 为 null 时由 Runtime 按手机上的设置决定。
         */
        fun run(
            runId: String,
            conversationKey: String,
            prompt: String,
            modelId: String?,
            reasoningEffort: String?,
            onEvent: (AgentEvent) -> Unit,
        ): AgentRuntimeWire.RunResult
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

    private class Task(
        val id: String,
        val conversationKey: String,
        val model: ModelOption?,
        val reasoningEffort: String?,
    ) {
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
        val usage = UsageTotals()
    }

    /**
     * 本任务累计的模型用量。同一请求内的多次上报取最后一次（Anthropic 先报输入、再报累计输出），
     * 请求之间相加；服务商没返回 usage 的请求只计入请求数。
     */
    private class UsageTotals {
        private var requests = 0
        private val committed = LongArray(FIELDS)
        private var committedReports = 0
        /** 当前请求最近一次上报；下一次请求开始时才计入总数，快照只读不提交。 */
        private var pending: AgentTokenUsage? = null

        fun requestStarted() {
            pending?.let { usage ->
                add(committed, usage)
                committedReports += 1
            }
            pending = null
            requests += 1
        }

        fun received(usage: AgentTokenUsage) {
            if (requests == 0) requests = 1
            pending = usage
        }

        fun toJson(): JSONObject? {
            if (requests == 0) return null
            val totals = committed.copyOf()
            pending?.let { add(totals, it) }
            val reported = committedReports + if (pending != null) 1 else 0
            return JSONObject()
                .put("requests", requests)
                .put("requests_without_usage", requests - reported)
                .put("input_tokens", totals[0])
                .put("cached_tokens", totals[1])
                .put("cache_write_tokens", totals[2])
                .put("output_tokens", totals[3])
                .put("reasoning_tokens", totals[4])
        }

        private fun add(totals: LongArray, usage: AgentTokenUsage) {
            totals[0] += (usage.promptTokens ?: usage.inputTokens ?: 0).toLong()
            totals[1] += (usage.cachedTokens ?: 0).toLong()
            totals[2] += (usage.cacheWriteTokens ?: 0).toLong()
            totals[3] += (usage.outputTokens ?: 0).toLong()
            totals[4] += (usage.reasoningTokens ?: 0).toLong()
        }

        private companion object {
            const val FIELDS = 5
        }
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
        val choices = models()
        val model = when (val choice = chooseModel(choices, args.optString("model").trim())) {
            is ModelChoice.Use -> choice.model
            is ModelChoice.Unavailable -> return choice.failure
        }
        val effort = chooseEffort(model ?: choices.defaultModel, args.optString("reasoning_effort").trim(), choices)
        val task = synchronized(tasks) {
            prune()
            tasks.values.firstOrNull { it.conversationKey == conversationKey && !it.lock.withLock { it.status.terminal } }
                ?.let { busy ->
                    return failure("CONVERSATION_BUSY", "该会话已有任务在执行，可等待它结束或先停止")
                        .put("task_id", busy.id)
                }
            Task(UUID.randomUUID().toString(), conversationKey, model, effort).also { tasks[it.id] = it }
        }
        launch("eta-mcp-task") {
            val result = runCatching {
                runner.run(task.id, conversationKey, prompt, model?.id, effort) { event -> onEvent(task, event) }
            }.getOrElse { throwable ->
                AgentRuntimeWire.RunResult(task.id, ok = false, content = "", error = throwable.message ?: "任务启动失败")
            }
            finish(task, result)
        }
        return await(task, waitMs)
    }

    private sealed interface ModelChoice {
        /** [model] 为 null 表示使用 Eta 的默认模型。 */
        class Use(val model: ModelOption?) : ModelChoice
        class Unavailable(val failure: JSONObject) : ModelChoice
    }

    /**
     * 电脑端指定的强度必须在该模型的可选范围内；省略时用手机上的设置。返回实际交给 Runtime 的强度，
     * 模型不支持思考或手机上关闭了思考时为 off。
     */
    private fun chooseEffort(model: ModelOption?, requested: String, choices: ModelChoices): String? {
        if (requested.isNotEmpty()) {
            val efforts = model?.efforts.orEmpty()
            require(requested in efforts) {
                if (efforts.isEmpty()) "该模型不支持思考强度，省略 reasoning_effort"
                else "reasoning_effort 可选：" + efforts.joinToString()
            }
        }
        if (!choices.thinkingAllowed) return OFF_EFFORT
        return requested.ifEmpty { null } ?: model?.defaultEffort
    }

    /** 开放了模型选择时只接受勾选范围内的模型，任何情况下都不悄悄换成默认模型。 */
    private fun chooseModel(choices: ModelChoices, requested: String): ModelChoice {
        if (!choices.restricted) {
            require(requested.isEmpty()) { "手机上未开放模型选择，省略 model 即使用 Eta 的默认模型" }
            return ModelChoice.Use(null)
        }
        val first = choices.options.firstOrNull() ?: return ModelChoice.Unavailable(
            failure("MODEL_UNAVAILABLE", "手机上为电脑端勾选的模型均已停用或删除，请在 Eta 的 MCP 页重新勾选"),
        )
        if (requested.isEmpty()) return ModelChoice.Use(first)
        val model = choices.options.firstOrNull { it.id == requested }
            ?: throw IllegalArgumentException("model 不在可用列表中，可选：" + choices.options.joinToString { it.id })
        return ModelChoice.Use(model)
    }

    private fun onEvent(task: Task, event: AgentEvent) = task.lock.withLock {
        when (event) {
            // 用量只在状态快照里汇总，不单独唤醒等待中的长轮询。
            is AgentEvent.ProviderRequestStarted -> return@withLock task.usage.requestStarted()
            is AgentEvent.UsageReceived -> return@withLock task.usage.received(event.usage)
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
                task.model?.let { put("model", JSONObject().put("id", it.id).put("name", it.label)) }
                task.reasoningEffort?.let { put("reasoning_effort", it) }
                task.currentTool?.let { put("current_tool", it) }
                task.planJson?.let { raw -> runCatching { JSONObject(raw) }.getOrNull()?.let { put("plan", it) } }
                task.questionId?.let { id ->
                    put("question", JSONObject().put("question_id", id).apply {
                        task.question?.let { put("questions", JSONObject(it.toJson()).getJSONArray("questions")) }
                    })
                }
                task.usage.toJson()?.let { put("usage", it) }
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

    fun tools(): List<EtaMcpTool> = listOf(startTool(models())) + fixedTools

    private fun startTool(choices: ModelChoices): EtaMcpTool {
        val modelProperty = choices.options.takeIf { choices.restricted && it.isNotEmpty() }?.let { options ->
            "model" to JSONObject().put("type", "string").put("enum", JSONArray(options.map { it.id }))
                .put(
                    "description",
                    "可选；执行任务的模型，省略时使用 ${options.first().id}。可选：" +
                        options.joinToString("；") { "${it.id} = ${it.label}" },
                )
        }
        val modelHint = when {
            !choices.restricted -> "任务使用 Eta 的默认模型。"
            modelProperty != null -> "任务只能使用手机上为电脑端勾选的模型，见 model 参数。"
            else -> "手机上为电脑端勾选的模型当前均不可用，任务会以 MODEL_UNAVAILABLE 失败。"
        }
        val effortModels = (if (choices.restricted) choices.options else listOfNotNull(choices.defaultModel))
            .filter { it.efforts.isNotEmpty() }
        val effortProperty = effortModels.takeIf { it.isNotEmpty() && choices.thinkingAllowed }?.let { models ->
            "reasoning_effort" to JSONObject().put("type", "string")
                .put("enum", JSONArray(models.flatMap { it.efforts }.distinct()))
                .put(
                    "description",
                    "可选；思考强度，default 表示不发送强度参数、由服务商决定。省略时用手机上为该模型设的强度。各模型可选：" +
                        models.joinToString("；") { option ->
                            val name = if (choices.restricted) option.id else option.label
                            "$name = ${option.efforts.joinToString("/")}（省略时 ${option.defaultEffort}）"
                        },
                )
        }
        return EtaMcpTool(
            name = START,
            description = "把一项任务交给手机上的 Eta Agent 执行（它会用自己的模型和设备工具完成，包括操作界面、终端、文件等），" +
                "适合需要多步操作或在手机上自主完成的事情；只需单个设备操作时直接调用对应工具更快。" +
                "立即返回 task_id，并最多等待 wait_seconds 秒：任务结束或需要回答问题时提前返回。" +
                "继续同一段对话时传入上次返回的 conversation_id。" + modelHint,
            inputSchema = schema(
                listOf("prompt"),
                *listOfNotNull(
                    "prompt" to JSONObject().put("type", "string").put("maxLength", MAX_PROMPT_CHARS)
                        .put("description", "交给 Eta 的任务描述，写清目标与完成标准"),
                    "conversation_id" to JSONObject().put("type", "string").put("maxLength", 64)
                        .put("description", "可选；相同 id 的任务共享上下文，省略时新开会话"),
                    modelProperty,
                    effortProperty,
                    "wait_seconds" to waitSchema(DEFAULT_WAIT_SECONDS),
                ).toTypedArray(),
            ),
        )
    }

    private val fixedTools = listOf(
        EtaMcpTool(
            name = STATUS,
            description = "查询委托任务的状态、最近步骤、待回答问题与最终结果；任务仍在执行时最多等待 wait_seconds 秒，结束或需要回答时提前返回。" +
                "usage 为本任务累计的模型用量：input_tokens 含缓存读写，cached_tokens 为缓存命中的输入；" +
                "requests_without_usage 为服务商没有返回用量的请求数。",
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
        private const val OFF_EFFORT = "off"
        private const val STOP_WAIT_MS = 5_000L
        private const val STOPPED_ERROR = "已停止"
        private val CONVERSATION_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
