package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentTaskPlan
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.AgentUserQuestion
import io.github.mangi.eta.agent.model.ModelFallbackPolicy
import io.github.mangi.eta.agent.model.ModelUsageScope
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * run_subagents：把相互独立的子问题交给子 Agent 并行处理。每个子 Agent 有独立上下文与工具执行器，
 * 使用与主 run 相同的模型和非界面工具，只把结论交回主 Agent，中间的读取与检索不占主上下文。
 *
 * 子 Agent 不能操作界面（屏幕只有一块，由主 Agent 串行使用）、不能提问、不能再派子 Agent；
 * 主 run 取消时一并取消。进度以 [AgentEvent.SubagentUpdate] 汇总，内部事件不进入主 run 的消息流；
 * 每个子 Agent 的过程在运行中每轮、结束时各交给 [saveTranscript] 另存一次，供界面查看。
 */
internal class AgentSubagentTool(
    private val parentController: AgentRunController,
    private val config: AgentModelClient.ModelConfig,
    private val parentSessionId: String,
    private val runId: String,
    /** 主 run 可用的附加工具（MCP）；执行器级工具不传给子 Agent。 */
    private val additionalTools: JSONArray,
    private val disabledTools: () -> Set<String>,
    private val capabilitiesProvider: () -> AgentToolCapabilities,
    /** 按执行器 id 新建工具执行器；id 同时作为租约与浏览器归属。 */
    private val newExecutor: (ownerId: String) -> RoutingToolExecutor,
    private val emit: (AgentEvent) -> Unit,
    private val fallback: ModelFallbackPolicy = ModelFallbackPolicy.NONE,
    /** 保存子 Agent 的过程（已脱敏）：运行中每轮一次快照，结束时一次最终结果。 */
    private val saveTranscript: (SubagentTranscriptStore.Record) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** 主 run 的终端执行上下文：子 Agent 与主 Agent 共用同一工作区与默认身份。 */
    private val executionContext: TerminalExecutionContext? = null,
) {
    private class Task(val task: String, val context: String)

    private class Outcome(
        val ok: Boolean,
        val text: String,
        val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
    )

    private val emitLock = Any()

    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val tasks = parseTasks(call.argumentsJson).getOrElse { failure ->
            return AgentModelClient.ToolResult(
                JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT")
                    .put("message", failure.message ?: "子任务参数无效").toString(),
            )
        }
        val excluded = excludedTools(catalogNames()) + COORDINATOR_TOOLS
        val outcomes = arrayOfNulls<Outcome>(tasks.size)
        // 子 Agent 在新线程上运行，用量账本的归属要显式带过去。
        val usageContext = ModelUsageScope.current()
        val workers = tasks.mapIndexed { index, task ->
            emitSafely(
                AgentEvent.SubagentUpdate(
                    call.id, index, tasks.size, AgentEvent.SubagentUpdate.PHASE_STARTED,
                    detail = task.task.take(TASK_ECHO_CHARS),
                ),
            )
            thread(name = "agent-subagent-$index", isDaemon = true) {
                val outcome = ModelUsageScope.run(usageContext?.copy(subagentIndex = index)) {
                    runSubagent(call.id, index, tasks.size, task, excluded)
                }
                outcomes[index] = outcome
                runCatching {
                    saveTranscript(
                        SubagentTranscriptStore.Record(
                            runId = runId, toolCallId = call.id, index = index, total = tasks.size,
                            task = task.task, ok = outcome.ok, result = outcome.text, createdAt = now(),
                            transcript = outcome.transcript,
                        ),
                    )
                }
                emitSafely(
                    AgentEvent.SubagentUpdate(
                        call.id, index, tasks.size,
                        if (outcome.ok) AgentEvent.SubagentUpdate.PHASE_FINISHED else AgentEvent.SubagentUpdate.PHASE_FAILED,
                        detail = outcome.text.take(CONCLUSION_ECHO_CHARS),
                    ),
                )
            }
        }
        workers.forEach { worker ->
            try {
                worker.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                parentController.cancel()
            }
        }
        parentController.throwIfCancelled()
        val results = JSONArray()
        tasks.forEachIndexed { index, task ->
            val outcome = outcomes[index] ?: Outcome(false, "子任务未返回结果")
            results.put(
                JSONObject().put("index", index + 1).put("task", task.task.take(TASK_ECHO_CHARS)).put("ok", outcome.ok)
                    .put(if (outcome.ok) "result" else "error", outcome.text),
            )
        }
        val anySucceeded = outcomes.any { it?.ok == true }
        return AgentModelClient.ToolResult(
            JSONObject().put("ok", anySucceeded).apply {
                if (!anySucceeded) put("code", "SUBAGENTS_FAILED").put("message", "全部子任务失败，详见 results")
            }.put("results", results).toString(),
        )
    }

    private fun runSubagent(toolCallId: String, index: Int, total: Int, task: Task, excluded: Set<String>): Outcome {
        val child = AgentRunController()
        val binding = parentController.register { child.cancel() }
        val roundLimitHit = AtomicBoolean(false)
        var rounds = 0
        val executor = newExecutor("$runId-sub-$index")
        // 失败或取消时 complete 不返回结果，用最近一次的过程快照。
        var latestTranscript = emptyList<AgentModelClient.ConversationMessage>()
        return try {
            val response = AgentModelClient.complete(
                config = config,
                prompt = subagentPrompt(task),
                toolExecutor = AgentModelClient.ToolExecutor { toolCall ->
                    if (toolCall.name in excluded) {
                        AgentModelClient.ToolResult(
                            JSONObject().put("ok", false).put("code", "SUBAGENT_TOOL_UNAVAILABLE")
                                .put("message", "子 Agent 不能使用此工具；需要界面操作或用户决定的步骤请写进结论，由主 Agent 处理").toString(),
                        )
                    } else {
                        executor.execute(toolCall)
                    }
                },
                runController = child,
                additionalTools = additionalTools,
                executionContext = executionContext,
                capabilitiesProvider = capabilitiesProvider,
                disabledToolsProvider = { disabledTools() + excluded },
                sessionId = "$parentSessionId:sub:$toolCallId:$index",
                operationId = "$runId-sub-$index",
                onTranscript = { transcript ->
                    latestTranscript = transcript
                    // 过程每更新一次（模型回复、工具结果）就覆盖写一次快照，运行中也能在界面查看。
                    runCatching {
                        saveTranscript(
                            SubagentTranscriptStore.Record(
                                runId = runId, toolCallId = toolCallId, index = index, total = total,
                                task = task.task, ok = false, result = "", createdAt = now(),
                                transcript = transcript, running = true,
                            ),
                        )
                    }
                },
                fallback = fallback,
            ) { event ->
                when (event) {
                    is AgentEvent.RoundStarted -> if (++rounds > MAX_ROUNDS && roundLimitHit.compareAndSet(false, true)) {
                        child.cancel()
                    }
                    is AgentEvent.ToolStarted -> emitSafely(
                        AgentEvent.SubagentUpdate(
                            toolCallId, index, total, AgentEvent.SubagentUpdate.PHASE_TOOL, event.name,
                            detail = event.argsPreview,
                        ),
                    )
                    else -> Unit
                }
            }
            Outcome(true, response.content.trim().take(MAX_RESULT_CHARS), response.transcript.ifEmpty { latestTranscript })
        } catch (throwable: Throwable) {
            val transcript = (throwable as? AgentModelExecutionException)?.transcript?.takeIf { it.isNotEmpty() }
                ?: latestTranscript
            when {
                roundLimitHit.get() -> Outcome(false, "超过 $MAX_ROUNDS 轮仍未完成，已停止该子任务", transcript)
                parentController.isCancelled -> Outcome(false, "已停止", transcript)
                else -> Outcome(false, (throwable.cause ?: throwable).message ?: throwable.javaClass.simpleName, transcript)
            }
        } finally {
            binding.close()
            runCatching { executor.close() }
        }
    }

    private fun catalogNames(): List<String> {
        val catalog = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            capabilities = capabilitiesProvider(),
        )
        return (0 until catalog.length()).mapNotNull { catalog.optJSONObject(it)?.optJSONObject("function")?.optString("name") }
    }

    private fun emitSafely(event: AgentEvent) = synchronized(emitLock) { runCatching { emit(event) } }

    internal companion object {
        const val TOOL_NAME = "run_subagents"
        const val MAX_TASKS = 4
        const val MAX_ROUNDS = 40
        private const val MAX_TASK_CHARS = 4_000
        private const val MAX_CONTEXT_CHARS = 8_000
        private const val MAX_RESULT_CHARS = 8_000
        private const val TASK_ECHO_CHARS = 120
        private const val CONCLUSION_ECHO_CHARS = 200

        /** 只属于主 Agent 的协作工具；列入停用集合，子 Agent 的提示词也不会提到它们。 */
        private val COORDINATOR_TOOLS = setOf(AgentUserQuestion.TOOL_NAME, AgentTaskPlan.TOOL_NAME, TOOL_NAME)

        /**
         * 子 Agent 不开放的目录工具：需要手机操作权或无障碍的界面工具、拉起前台界面的工具、
         * 释放操作权（子 Agent 从不持有）、主 run 的会话浏览器，以及 Skill 管理。
         */
        fun excludedTools(catalogNames: Iterable<String>): Set<String> = catalogNames.filterTo(mutableSetOf()) { name ->
            AgentToolRequirements.requiresGuiControl(name) || AgentToolRequirements.requiresAccessibility(name) ||
                AgentToolRequirements.hasForegroundIntentFallback(name) || name == RELEASE_GUI_TOOL ||
                name == BROWSER_TOOL || name.startsWith("skills_")
        }

        private const val RELEASE_GUI_TOOL = "release_gui"
        private const val BROWSER_TOOL = "browser_use"

        private fun parseTasks(raw: String): Result<List<Task>> = runCatching {
            val array = JSONObject(raw.ifBlank { "{}" }).optJSONArray("tasks")
                ?: throw IllegalArgumentException("缺少 tasks 数组")
            require(array.length() in 1..MAX_TASKS) { "一次派出 1–$MAX_TASKS 个子任务" }
            (0 until array.length()).map { index ->
                val item = array.optJSONObject(index) ?: throw IllegalArgumentException("第 ${index + 1} 个子任务格式无效")
                val task = item.optString("task").trim()
                require(task.isNotEmpty()) { "第 ${index + 1} 个子任务缺少 task" }
                Task(task.take(MAX_TASK_CHARS), item.optString("context").trim().take(MAX_CONTEXT_CHARS))
            }
        }

        private fun subagentPrompt(task: Task): String = buildString {
            append("你是 Eta 主 Agent 派出的子 Agent，只负责下面这一项子任务，完成后直接给出结论。\n")
            append("- 只用当前公开的工具独立完成；你不能操作手机界面，也不能向用户提问。")
            append("需要界面操作或用户决定的部分，在结论里写明，由主 Agent 处理。\n")
            append("- 你看不到主对话，只依据下面的任务与背景工作，不要假设未提供的信息。\n")
            append("- terminal_job、terminal_daemon 启动的后台任务在你结束后仍会继续运行；只在确有必要时使用，并在结论里写明任务 id。\n")
            append("- 结论要具体：给出关键事实、文件路径、命令输出要点或来源链接，并说明不确定之处；不寒暄，不复述任务。\n\n")
            append("子任务：\n").append(task.task)
            if (task.context.isNotBlank()) append("\n\n背景：\n").append(task.context)
        }

        fun schema(): JSONObject = AgentToolSchema.function(
            name = TOOL_NAME,
            description = "把相互独立、需要大量读取或检索的子问题交给子 Agent 并行处理；每个子 Agent 有独立上下文，只把结论返回给你。" +
                "适合在多个目录、日志或网页中查找与归纳、分别调研几个方向、阅读大段输出后提炼要点，避免中间结果占满当前上下文。" +
                "子 Agent 使用与你相同的模型和非界面工具（终端、文件、网页等），不能操作手机界面、不能向用户提问、看不到当前对话，" +
                "因此 task 要写清目标、范围与期望的结论形式，必要背景放进 context。一次最多 $MAX_TASKS 个，彼此并行执行；" +
                "只需一两次工具调用的简单查询直接自己做更快。",
            parameters = JSONObject().put("type", "object").put("required", JSONArray().put("tasks"))
                .put("properties", JSONObject().put("tasks", JSONObject()
                    .put("type", "array").put("minItems", 1).put("maxItems", MAX_TASKS)
                    .put("items", JSONObject().put("type", "object").put("required", JSONArray().put("task"))
                        .put("properties", JSONObject()
                            .put("task", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", MAX_TASK_CHARS)
                                .put("description", "子任务的目标、范围与期望的结论形式"))
                            .put("context", JSONObject().put("type", "string").put("maxLength", MAX_CONTEXT_CHARS)
                                .put("description", "可选；子 Agent 需要的背景，如路径、已知结论、限制条件")))))),
        )
    }
}
