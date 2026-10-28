package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.model.ModelFallbackPolicy
import io.github.mangi.eta.agent.model.ModelUsageContext
import io.github.mangi.eta.agent.model.ModelUsageScope
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.terminal.commandStatus
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.db.ConversationSubagentEntity
import io.github.mangi.eta.data.db.ConversationSubagentMailEntity
import io.github.mangi.eta.data.db.ConversationSubagentSummary
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** 子 Agent 与通知的持久化；生产用 Room，单测用内存实现。 */
internal interface SubagentStore {
    fun agents(conversationId: String): List<ConversationSubagentEntity>
    fun save(agent: ConversationSubagentEntity)
    /** 上一个进程里还在运行的子 Agent 改为已中断，返回它们以便补发中断通知。 */
    fun interruptStale(ownerInstanceId: String, now: Long): List<ConversationSubagentSummary>
    fun addMail(mail: ConversationSubagentMailEntity)
    fun undeliveredMail(conversationId: String): List<ConversationSubagentMailEntity>
    fun markDelivered(ids: List<Long>, runId: String?)
    fun deleteConversation(conversationId: String)
    fun conversationsWithUndeliveredMail(): List<String>
}

internal class RoomSubagentStore(context: Context) : SubagentStore {
    private val dao = EtaDatabase.get(context.applicationContext).subagentDao()

    override fun agents(conversationId: String) = io { dao.agents(conversationId) }
    override fun save(agent: ConversationSubagentEntity) = io { dao.upsertAgent(agent) }
    override fun interruptStale(ownerInstanceId: String, now: Long) = io { dao.interruptStale(ownerInstanceId, now) }
    override fun addMail(mail: ConversationSubagentMailEntity) { io { dao.insertMail(mail) } }
    override fun undeliveredMail(conversationId: String) = io { dao.undeliveredMail(conversationId) }
    override fun markDelivered(ids: List<Long>, runId: String?) {
        if (ids.isNotEmpty()) io { dao.markMailDelivered(ids, runId) }
    }
    override fun deleteConversation(conversationId: String) = io { dao.deleteConversation(conversationId) }
    override fun conversationsWithUndeliveredMail() = io { dao.conversationsWithUndeliveredMail() }

    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}

/** 派出或续用子 Agent 时从当前 run 带过去的执行环境；主 run 结束后，子 Agent 用它继续跑完本回合。 */
internal class SubagentEnvironment(
    val runId: String,
    /** 子 Agent 归属的会话（[AgentConversationIdentity]）。 */
    val conversationId: String,
    val parentSessionId: String,
    val config: AgentModelClient.ModelConfig,
    val capabilitiesProvider: () -> AgentToolCapabilities,
    val additionalTools: JSONArray = JSONArray(),
    val disabledTools: () -> Set<String> = { emptySet() },
    /** 按执行器 id 新建工具执行器；id 同时作为浏览器与操作权归属，子 Agent 不会取得手机操作权。 */
    val newExecutor: (ownerId: String) -> RoutingToolExecutor? = { null },
    val fallback: ModelFallbackPolicy = ModelFallbackPolicy.NONE,
    val executionContext: TerminalExecutionContext? = null,
    val usageContext: ModelUsageContext? = null,
)

internal class SubagentTurn(
    val environment: SubagentEnvironment,
    val agentId: String,
    val number: Int,
    val turn: Int,
    val prompt: String,
    /** 之前各回合的上下文（已补齐中断的工具批次）。 */
    val history: List<AgentModelClient.ConversationMessage>,
    val modelSessionId: String,
    val controller: AgentRunController,
    /** 回合进行中每次过程更新时给出完整上下文，用于落盘。 */
    val onProgress: (List<AgentModelClient.ConversationMessage>) -> Unit,
)

internal data class SubagentTurnResult(
    val ok: Boolean,
    val text: String,
    val history: List<AgentModelClient.ConversationMessage>,
    val rounds: Int,
    val cancelled: Boolean = false,
)

internal fun interface SubagentTurnRunner {
    fun run(turn: SubagentTurn): SubagentTurnResult
}

/** 子 Agent 回合运行时持有的执行租约：主 run 结束后运行时服务与前台通知仍在，进程不被回收。 */
internal fun interface SubagentLeases {
    /** 返回释放函数；[onStop] 在用户从通知停止时调用。 */
    fun acquire(id: String, onStop: () -> Unit): () -> Unit

    companion object {
        val NONE = SubagentLeases { _, _ -> {} }
    }
}

internal class ExecutionServiceLeases(private val context: Context) : SubagentLeases {
    override fun acquire(id: String, onStop: () -> Unit): () -> Unit {
        runCatching { AgentExecutionService.acquire(context, id, allowBoundFallback = true) { onStop() } }
        return { runCatching { AgentExecutionService.release(id) } }
    }
}

/**
 * 会话级子 Agent（AG-19，AG-27 按 Claude Code 的机制返工）：派出后立即返回，子 Agent 在自己的线程上跑完一个回合
 * （接到一件任务到写出不再调用工具的回复）；之后可以继续发消息，以保存的上下文开始新回合。
 * - 运行中收到的消息在它下一次请求模型前插入当前回合；它已写完最终回复时，这一回合先结束、交出结论，
 *   消息作为下一回合的开头。
 * - 每个回合结束（完成、失败、中断）写一条通知：主 run 正在运行则并入它的下一轮；主 run 已结束则留在库里，
 *   下一个 run 开始时交出，完成或失败的通知还会由 [wakeListener] 唤醒主 Agent。
 * - 主 run 被停止时，本会话运行中的子 Agent 一起停（已中断，可继续）；进程重启后，上一个进程里运行中的
 *   子 Agent 改为已中断并补发中断通知。
 */
internal class AgentSubagentManager(
    private val store: SubagentStore,
    private val runner: SubagentTurnRunner,
    private val leases: SubagentLeases = SubagentLeases.NONE,
    private val ownerInstanceId: String = AgentRuntimeProcessIdentity.id,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class LiveAgent(var row: ConversationSubagentEntity, var history: List<AgentModelClient.ConversationMessage>) {
        var controller: AgentRunController? = null
        /** 最近一次派出或发消息时的执行环境；排队消息开下一回合时沿用。 */
        var environment: SubagentEnvironment? = null
        var turnStartedAt = 0L
        /** 本回合的消息在 [history] 中的起点，用于统计本回合轮数与最新进展。 */
        var turnStartIndex = 0
        var lastSavedAt = 0L
        val number: Int get() = row.agentId.removePrefix(ID_PREFIX).toIntOrNull() ?: 0
    }

    private class ParentRun(val runId: String, val controller: AgentRunController)

    private class ConversationState {
        val agents = LinkedHashMap<String, LiveAgent>()
        var parent: ParentRun? = null
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val conversations = HashMap<String, ConversationState>()
    private var staleInterrupted = false

    /** 主 run 已结束、通知无处投递时调用；App 据此开一个 run 让主 Agent 处理结果（自动唤醒）。 */
    @Volatile
    var wakeListener: ((conversationId: String) -> Unit)? = null

    private val revisionState = MutableStateFlow(0L)

    /** 子 Agent 有变化（派出、进度、结束、关闭、删除）时递增；侧栏据此刷新列表。 */
    val revision: StateFlow<Long> = revisionState

    fun conversationsWithUndeliveredMail(): List<String> = store.conversationsWithUndeliveredMail()

    /** 备份恢复后库已整体替换：停掉运行中的子 Agent，丢弃缓存，之后按新库重新加载。 */
    fun forgetAll() {
        lock.withLock {
            conversations.values.forEach { state -> state.agents.values.forEach { it.controller?.cancel() } }
            conversations.clear()
        }
        revisionState.update { it + 1 }
    }

    fun attachParent(conversationId: String, runId: String, controller: AgentRunController) {
        lock.withLock {
            val state = state(conversationId)
            state.parent = ParentRun(runId, controller)
            deliverLocked(conversationId, state)
        }
    }

    /** 主 run 结束：没来得及交给模型的通知恢复为未投递，留给下一个 run 或自动唤醒。 */
    fun detachParent(conversationId: String, runId: String) {
        val wake = lock.withLock {
            val state = conversations[conversationId] ?: return
            val parent = state.parent?.takeIf { it.runId == runId } ?: return
            state.parent = null
            val leftover = parent.controller.pollNotices().flatMap { it.mailIds }
            store.markDelivered(leftover, null)
            store.undeliveredMail(conversationId).any { it.status != STATUS_INTERRUPTED }
        }
        if (wake) wakeListener?.invoke(conversationId)
    }

    /** 主 run 被停止：本会话运行中的子 Agent 一起停，状态为已中断，之后仍可继续。 */
    fun stopRunning(conversationId: String) {
        lock.withLock { conversations[conversationId]?.agents?.values?.forEach { it.controller?.cancel() } }
    }

    fun deleteConversation(conversationId: String) {
        lock.withLock {
            conversations.remove(conversationId)?.agents?.values?.forEach { agent ->
                agent.row = agent.row.copy(status = STATUS_CLOSED)
                agent.controller?.cancel()
            }
        }
        store.deleteConversation(conversationId)
        revisionState.update { it + 1 }
    }

    fun spawn(environment: SubagentEnvironment, task: String, context: String, name: String?, reason: String): JSONObject =
        lock.withLock {
            val state = state(environment.conversationId)
            limitError(state)?.let { return it }
            val number = (state.agents.values.maxOfOrNull { it.number } ?: 0) + 1
            val agentId = "$ID_PREFIX$number"
            val label = name?.trim()?.takeIf(String::isNotEmpty)
            if (label != null) {
                if (!NAME_PATTERN.matches(label)) return failure("INVALID_ARGUMENT", "name 只能用小写字母、数字和下划线，最长 32 个字符")
                if (findLocked(state, label) != null) return failure("INVALID_ARGUMENT", "会话里已有名为 $label 的子 Agent")
            }
            val time = now()
            val agent = LiveAgent(
                ConversationSubagentEntity(
                    id = ConversationSubagentEntity.rowId(environment.conversationId, agentId),
                    conversationId = environment.conversationId,
                    agentId = agentId,
                    name = label ?: agentId,
                    task = (reason.takeIf(String::isNotBlank)?.let { "$it：" }.orEmpty() + task).take(TASK_SUMMARY_CHARS),
                    status = STATUS_RUNNING,
                    turn = 0,
                    rounds = 0,
                    lastResult = "",
                    lastError = "",
                    transcriptJson = "[]",
                    modelSessionId = "${environment.parentSessionId}:sub:$agentId",
                    ownerInstanceId = ownerInstanceId,
                    createdRunId = environment.runId,
                    lastRunId = environment.runId,
                    createdAt = time,
                    updatedAt = time,
                ),
                emptyList(),
            )
            state.agents[agentId] = agent
            startTurnLocked(environment, agent, AgentSubagentTool.subagentPrompt(task, context))
            JSONObject().put("ok", true).put("agent_id", agentId).put("name", agent.row.name).put("status", STATUS_RUNNING)
        }

    /**
     * 给子 Agent 发消息（与 Claude Code 的 SendMessage 一致）：运行中时在它下一次请求模型前插入当前回合；
     * 它已写完最终回复、回合正在收尾时，等回合结束交出结论后作为下一回合的开头；空闲时直接开新回合。
     * 要改方向先 [stop] 再发。
     */
    fun send(environment: SubagentEnvironment, target: String, message: String): JSONObject =
        lock.withLock {
            val state = state(environment.conversationId)
            val agent = findLocked(state, target) ?: return notFound(target)
            if (agent.row.status == STATUS_CLOSED) return failure("SUBAGENT_CLOSED", "子 Agent ${agent.row.agentId} 已关闭，不能再发消息")
            agent.environment = environment
            agent.controller?.let { running ->
                if (running.steer(message)) return queued(agent)
                // 已封口：等这一回合交出结论；若它带着先前排队的消息开了下一回合，就插进那一回合。
                awaitIdleLocked(agent)
                agent.controller?.let { next ->
                    return if (next.steer(message)) queued(agent)
                    else failure("SUBAGENT_BUSY", "子 Agent ${agent.row.agentId} 正在收尾，稍后再试")
                }
            }
            limitError(state)?.let { return it }
            startTurnLocked(environment, agent, message)
            JSONObject().put("ok", true).put("agent_id", agent.row.agentId).put("delivery", "started")
        }

    private fun queued(agent: LiveAgent) = JSONObject().put("ok", true).put("agent_id", agent.row.agentId)
        .put("delivery", "queued").put("message", "已送达，它会在下一次请求模型前看到；如果已写完本回合结论，会作为下一回合的开头")

    /** 不阻塞的状态查询：运行中的附进度摘要；还没交给主 Agent 的结论在这里给出全文，之后不再另发通知。 */
    fun list(environment: SubagentEnvironment, targets: List<String>): JSONObject = lock.withLock {
        val state = state(environment.conversationId)
        val agents = if (targets.isEmpty()) state.agents.values.toList() else targets.map { findLocked(state, it) ?: return notFound(it) }
        val pending = store.undeliveredMail(environment.conversationId).filter { mail -> agents.any { it.row.agentId == mail.agentId } }
        store.markDelivered(pending.map { it.id }, environment.runId)
        val withResult = pending.mapTo(HashSet()) { it.agentId }
        JSONObject().put("ok", true).put("agents", JSONArray(agents.map { describe(it, includeResult = it.row.agentId in withResult) }))
    }

    fun stop(environment: SubagentEnvironment, target: String): JSONObject = lock.withLock {
        val agent = findLocked(state(environment.conversationId), target) ?: return notFound(target)
        val previous = agent.row.status
        agent.controller?.cancel()
        JSONObject().put("ok", true).put("agent_id", agent.row.agentId).put("previous_status", previous)
    }

    fun close(environment: SubagentEnvironment, target: String): JSONObject = lock.withLock {
        val agent = findLocked(state(environment.conversationId), target) ?: return notFound(target)
        agent.row = agent.row.copy(status = STATUS_CLOSED, updatedAt = now())
        agent.controller?.cancel()
        saveLocked(agent)
        JSONObject().put("ok", true).put("agent_id", agent.row.agentId).put("status", STATUS_CLOSED)
    }

    fun snapshot(conversationId: String): List<ConversationSubagentEntity> = lock.withLock {
        state(conversationId).agents.values.map { it.row }
    }

    private fun startTurnLocked(environment: SubagentEnvironment, agent: LiveAgent, prompt: String) {
        val controller = AgentRunController(AgentRunController.COORDINATOR_STEERING_LABEL, endTurnBeforeSteering = true)
        val turn = agent.row.turn + 1
        val history = AgentToolBatchRecovery.completeInterrupted(agent.history)
        agent.controller = controller
        agent.environment = environment
        agent.turnStartedAt = now()
        agent.turnStartIndex = history.size
        agent.lastSavedAt = agent.turnStartedAt
        agent.row = agent.row.copy(
            status = STATUS_RUNNING, turn = turn, lastError = "", ownerInstanceId = ownerInstanceId,
            lastRunId = environment.runId, updatedAt = now(),
        )
        saveLocked(agent)
        val agentId = agent.row.agentId
        val number = agent.number
        val modelSessionId = agent.row.modelSessionId
        val leaseId = "subagent:${environment.conversationId}:$agentId"
        thread(name = "agent-subagent-$agentId", isDaemon = true) {
            val release = leases.acquire(leaseId) { controller.cancel() }
            val result = try {
                ModelUsageScope.run(environment.usageContext?.copy(subagentIndex = number)) {
                    runner.run(
                        SubagentTurn(environment, agentId, number, turn, prompt, history, modelSessionId, controller) { progress ->
                            saveProgress(environment.conversationId, agentId, controller, progress)
                        },
                    )
                }
            } catch (throwable: Throwable) {
                SubagentTurnResult(false, throwable.message ?: throwable.javaClass.simpleName, history, 0, controller.isCancelled)
            } finally {
                release()
            }
            finishTurn(environment, agentId, controller, result)
        }
    }

    private fun saveProgress(
        conversationId: String,
        agentId: String,
        controller: AgentRunController,
        history: List<AgentModelClient.ConversationMessage>,
    ) = lock.withLock {
        val agent = conversations[conversationId]?.agents?.get(agentId)?.takeIf { it.controller === controller } ?: return@withLock
        agent.history = history
        // 每步只更新内存（list_agents 的进度摘要读它）；落盘与侧栏刷新限频，回合结束时再写完整快照。
        // 仍在锁内写，避免与回合结束的写入乱序把状态改回运行中。
        val time = now()
        if (time - agent.lastSavedAt < PROGRESS_SAVE_INTERVAL_MS) return@withLock
        agent.lastSavedAt = time
        agent.row = agent.row.copy(updatedAt = time)
        saveLocked(agent)
    }

    private fun finishTurn(
        environment: SubagentEnvironment,
        agentId: String,
        controller: AgentRunController,
        result: SubagentTurnResult,
    ) = lock.withLock {
        val state = conversations[environment.conversationId] ?: return@withLock
        val agent = state.agents[agentId]?.takeIf { it.controller === controller } ?: return@withLock
        // 封口后才到的消息：本回合照常结束交出结论，再用它们开下一回合。
        val leftovers = controller.drainSteering()
        agent.controller = null
        agent.history = result.history
        val status = when {
            agent.row.status == STATUS_CLOSED -> STATUS_CLOSED
            result.cancelled -> STATUS_INTERRUPTED
            result.ok -> STATUS_COMPLETED
            else -> STATUS_FAILED
        }
        agent.row = agent.row.copy(
            status = status,
            rounds = agent.row.rounds + result.rounds,
            lastResult = if (result.ok) result.text else agent.row.lastResult,
            lastError = if (result.ok || result.cancelled) "" else result.text,
            updatedAt = now(),
        )
        saveLocked(agent)
        if (status != STATUS_CLOSED) {
            addMailLocked(environment.conversationId, agentId, agent.row.name, agent.row.turn, status, result.text)
            deliverLocked(environment.conversationId, state)
        }
        if (leftovers.isNotEmpty() && (status == STATUS_COMPLETED || status == STATUS_FAILED)) {
            startTurnLocked(agent.environment ?: environment, agent, leftovers.joinToString("\n\n"))
        }
        changed.signalAll()
    }

    /**
     * 主 Agent 启动的后台命令结束（AG-27）：写一条通知交回它的会话，投递与唤醒规则和子 Agent 回合结束相同；
     * 被停止的命令按中断处理（不唤醒，下一次 run 再交）。
     */
    fun notifyBackgroundCommand(task: DetachedTask, outputTail: String) {
        val conversationId = task.ownerConversationId ?: return
        lock.withLock {
            val state = state(conversationId)
            val status = when (task.commandStatus) {
                "completed" -> STATUS_COMPLETED
                "stopped", "interrupted" -> STATUS_INTERRUPTED
                else -> STATUS_FAILED
            }
            val payload = JSONObject()
                .put("kind", KIND_COMMAND)
                .put("job_id", task.id)
                .put("description", task.description)
                .put("status", task.commandStatus)
                .put("exit_code", task.exitCode ?: JSONObject.NULL)
                .put("output_tail", outputTail.takeLast(COMMAND_TAIL_CHARS))
                .put("full_log", "用 terminal_job 的 read 查看完整日志")
            store.addMail(
                ConversationSubagentMailEntity(
                    conversationId = conversationId,
                    agentId = task.id,
                    turn = 0,
                    status = status,
                    payload = payload.toString(),
                    createdAt = now(),
                    deliveredRunId = null,
                ),
            )
            deliverLocked(conversationId, state)
        }
    }

    private fun addMailLocked(conversationId: String, agentId: String, name: String, turn: Int, status: String, text: String) {
        store.addMail(
            ConversationSubagentMailEntity(
                conversationId = conversationId,
                agentId = agentId,
                turn = turn,
                status = status,
                payload = mailPayload(agentId, name, status, turn, text).toString(),
                createdAt = now(),
                deliveredRunId = null,
            ),
        )
    }

    private fun deliverLocked(conversationId: String, state: ConversationState) {
        val mails = store.undeliveredMail(conversationId)
        if (mails.isEmpty()) return
        val parent = state.parent
        val hasSubagentMail = mails.any { !isCommandMail(it) }
        val notice = AgentRunController.Notice(
            modelText = mails.joinToString("\n\n") { mail ->
                val tag = if (isCommandMail(mail)) "background_command_notification" else "subagent_notification"
                "<$tag>\n${mail.payload}\n</$tag>"
            } + "\n以上是后台任务的通知，不是用户的新指令。" +
                (if (hasSubagentMail) "子 Agent 会一直保留：之后有相关的事用 send_agent 交给同一个，它记得之前的内容。" else ""),
            display = mails.joinToString("\n") { displayLine(it) },
            mailIds = mails.map { it.id },
        )
        if (parent != null && parent.controller.deliverNotice(notice)) {
            store.markDelivered(notice.mailIds, parent.runId)
        } else if (parent == null && mails.any { it.status != STATUS_INTERRUPTED }) {
            // 没有进行中的主 run：解锁后再唤醒，避免监听方回调进来时死锁。只有中断通知时不唤醒，下一次 run 再交。
            thread(name = "agent-subagent-wake", isDaemon = true) { wakeListener?.invoke(conversationId) }
        }
    }

    private fun awaitIdleLocked(agent: LiveAgent) {
        var remaining = TimeUnit.MILLISECONDS.toNanos(STOP_WAIT_MS)
        while (agent.controller != null && remaining > 0) remaining = changed.awaitNanos(remaining)
    }

    private fun state(conversationId: String): ConversationState {
        conversations[conversationId]?.let { return it }
        if (!staleInterrupted) {
            runCatching {
                store.interruptStale(ownerInstanceId, now()).forEach { row ->
                    addMailLocked(row.conversationId, row.agentId, row.name, row.turn, STATUS_INTERRUPTED, "Eta 进程结束，这一回合被中断")
                }
            }
            staleInterrupted = true
        }
        return ConversationState().also { state ->
            store.agents(conversationId).forEach { row ->
                state.agents[row.agentId] = LiveAgent(row, AgentConversationCodec.decodeTranscript(row.transcriptJson))
            }
            conversations[conversationId] = state
        }
    }

    private fun findLocked(state: ConversationState, target: String): LiveAgent? {
        val key = target.trim()
        return state.agents[key] ?: state.agents.values.firstOrNull { it.row.name == key }
    }

    private fun limitError(state: ConversationState): JSONObject? {
        val running = state.agents.values.count { it.controller != null }
        val total = conversations.values.sumOf { conversation -> conversation.agents.values.count { it.controller != null } }
        if (running < MAX_RUNNING_PER_CONVERSATION && total < MAX_RUNNING_TOTAL) return null
        return failure(
            "SUBAGENT_LIMIT",
            "同时运行的子 Agent 已达上限（本会话 $MAX_RUNNING_PER_CONVERSATION 个、全部 $MAX_RUNNING_TOTAL 个），先等它们结束或停止一个",
        ).put("running", running).put("limit", MAX_RUNNING_PER_CONVERSATION)
    }

    private fun saveLocked(agent: LiveAgent) {
        runCatching {
            store.save(agent.row.copy(transcriptJson = AgentConversationCodec.encodeConversationCheckpoint(agent.history)))
        }
        revisionState.update { it + 1 }
    }

    private fun describe(agent: LiveAgent, includeResult: Boolean): JSONObject = JSONObject()
        .put("agent_id", agent.row.agentId)
        .put("name", agent.row.name)
        .put("status", if (agent.controller != null) STATUS_RUNNING else agent.row.status)
        .put("task", agent.row.task.take(TASK_ECHO_CHARS))
        .put("turn", agent.row.turn)
        .put("rounds", agent.row.rounds)
        .apply {
            agent.controller?.let { put("progress", progress(agent, it)) }
            val text = agent.row.lastError.ifBlank { agent.row.lastResult }
            if (includeResult) {
                if (agent.row.lastError.isNotBlank()) put("error", agent.row.lastError) else put("result", agent.row.lastResult)
            } else if (text.isNotBlank()) {
                // 运行中时这是上一回合的结论，标明以免当成本回合的结果。
                put(if (agent.controller != null) "previous_result_preview" else "result_preview", text.take(PREVIEW_CHARS))
            }
        }

    /** 运行中子 Agent 的进度摘要：只给概况和最新一段中间说明，不给完整过程，以免撑满主 Agent 的上下文。 */
    private fun progress(agent: LiveAgent, controller: AgentRunController): JSONObject {
        val turnMessages = agent.history.drop(agent.turnStartIndex)
        val assistants = turnMessages.filter { it.role == "assistant" }
        val lastCall = assistants.lastOrNull { it.toolCallsJson.isNotBlank() }?.let { message ->
            runCatching {
                AgentConversationCodec.parseToolCalls(JSONObject().put("tool_calls", JSONArray(message.toolCallsJson))).lastOrNull()
            }.getOrNull()
        }
        val latestText = assistants.lastOrNull { it.content.isNotBlank() }?.content?.trim()
        return JSONObject()
            .put("elapsed_seconds", ((now() - agent.turnStartedAt) / 1000).coerceAtLeast(0))
            .put("rounds_this_turn", assistants.size)
            .put("queued_messages", controller.pendingSteeringCount)
            .apply {
                lastCall?.let { put("latest_tool", "${it.name} ${PROGRESS_FORMATTER.summarizeArguments(it)}".trim().take(PROGRESS_TOOL_CHARS)) }
                latestText?.let { put("latest_note", it.take(PROGRESS_TEXT_CHARS)) }
            }
    }

    private fun mailPayload(agentId: String, name: String, status: String, turn: Int, text: String): JSONObject = JSONObject()
        .put("agent_id", agentId)
        .put("name", name)
        .put("status", status)
        .put("turn", turn)
        .put(if (status == STATUS_COMPLETED) "result" else "error", text)

    private fun isCommandMail(mail: ConversationSubagentMailEntity): Boolean =
        runCatching { JSONObject(mail.payload).optString("kind") == KIND_COMMAND }.getOrDefault(false)

    private fun displayLine(mail: ConversationSubagentMailEntity): String {
        val payload = runCatching { JSONObject(mail.payload) }.getOrNull()
        if (payload?.optString("kind") == KIND_COMMAND) {
            val label = payload.optString("description").ifBlank { mail.agentId }
            val exit = payload.opt("exit_code").takeIf { it != null && it != JSONObject.NULL }?.let { "（退出码 $it）" }.orEmpty()
            val outcome = when (payload.optString("status")) {
                "completed" -> "完成$exit"
                "timed_out" -> "超时"
                "stopped", "interrupted" -> "已停止"
                else -> "失败$exit"
            }
            return "后台命令 $label（${mail.agentId}） $outcome"
        }
        val name = payload?.optString("name")?.takeIf(String::isNotBlank) ?: mail.agentId
        val label = if (name == mail.agentId) name else "$name（${mail.agentId}）"
        val text = payload?.optString("result")?.takeIf(String::isNotBlank) ?: payload?.optString("error").orEmpty()
        val verb = when (mail.status) {
            STATUS_COMPLETED -> "完成"
            STATUS_INTERRUPTED -> "已中断"
            else -> "失败"
        }
        // 主对话里只放一行摘要（AG-27）：不露出 Markdown 标记、不在句子中间截断；全文在过程窗口里看。
        return "$label $verb：${BackgroundNoticeText.summary(text)}"
    }

    private fun notFound(target: String) = failure("SUBAGENT_NOT_FOUND", "本会话没有子 Agent $target")

    private fun failure(code: String, message: String) = JSONObject().put("ok", false).put("code", code).put("message", message)

    internal companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_INTERRUPTED = "interrupted"
        const val STATUS_CLOSED = "closed"
        const val MAX_RUNNING_PER_CONVERSATION = 4
        const val MAX_RUNNING_TOTAL = 6
        private const val ID_PREFIX = "sub_"
        private val NAME_PATTERN = Regex("^[a-z0-9_]{1,32}$")
        private const val TASK_SUMMARY_CHARS = 400
        private const val TASK_ECHO_CHARS = 200
        private const val PREVIEW_CHARS = 300
        private const val STOP_WAIT_MS = 10_000L
        private const val PROGRESS_SAVE_INTERVAL_MS = 2_000L
        private const val KIND_COMMAND = "command"
        private const val COMMAND_TAIL_CHARS = 2_000
        private const val PROGRESS_TEXT_CHARS = 300
        private const val PROGRESS_TOOL_CHARS = 160
        private val PROGRESS_FORMATTER = AgentTraceFormatter()

        @Volatile
        private var shared: AgentSubagentManager? = null

        fun get(context: Context): AgentSubagentManager = shared ?: synchronized(this) {
            shared ?: AgentSubagentManager(
                store = RoomSubagentStore(context),
                runner = ModelSubagentTurnRunner,
                leases = ExecutionServiceLeases(context.applicationContext),
            ).also { shared = it }
        }
    }
}

/** 用当前主模型跑子 Agent 的一个回合；不能操作界面、不能提问、不能再派子 Agent。 */
internal object ModelSubagentTurnRunner : SubagentTurnRunner {
    override fun run(turn: SubagentTurn): SubagentTurnResult {
        val environment = turn.environment
        val excluded = AgentSubagentTool.excludedTools(AgentSubagentTool.catalogNames(environment.capabilitiesProvider())) +
            AgentSubagentTool.COORDINATOR_TOOLS
        val executor = environment.newExecutor("${environment.conversationId}-sub-${turn.agentId}")
        val userMessage = AgentModelClient.buildUserHistoryMessage(turn.prompt, emptyList())
        var latest = turn.history + userMessage
        var rounds = 0
        return try {
            val response = AgentModelClient.complete(
                config = environment.config,
                prompt = turn.prompt,
                toolExecutor = AgentModelClient.ToolExecutor { toolCall ->
                    if (toolCall.name in excluded || executor == null) {
                        AgentModelClient.ToolResult(
                            JSONObject().put("ok", false).put("code", "SUBAGENT_TOOL_UNAVAILABLE")
                                .put("message", "子 Agent 不能使用此工具；需要界面操作或用户决定的步骤请写进结论，由主 Agent 处理").toString(),
                        )
                    } else {
                        executor.execute(toolCall)
                    }
                },
                runController = turn.controller,
                additionalTools = environment.additionalTools,
                executionContext = environment.executionContext,
                capabilitiesProvider = environment.capabilitiesProvider,
                disabledToolsProvider = { environment.disabledTools() + excluded },
                sessionId = turn.modelSessionId,
                operationId = "${environment.runId}-${turn.agentId}-${turn.turn}",
                history = turn.history,
                onTranscript = { transcript ->
                    latest = turn.history + userMessage + transcript
                    runCatching { turn.onProgress(latest) }
                },
                fallback = environment.fallback,
            ) { event ->
                // 与 Claude Code 一致，回合不设轮数上限，由子 Agent 自然结束，或由用户、主 Agent 停止（AG-27）。
                if (event is AgentEvent.RoundStarted) rounds++
            }
            val history = response.contextSnapshot?.messages ?: (turn.history + userMessage + response.transcript)
            SubagentTurnResult(true, response.content.trim().take(AgentSubagentTool.MAX_RESULT_CHARS), history, rounds)
        } catch (throwable: Throwable) {
            val history = (throwable as? AgentModelExecutionException)?.transcript?.takeIf { it.isNotEmpty() }
                ?.let { turn.history + userMessage + it } ?: latest
            when {
                turn.controller.isCancelled -> SubagentTurnResult(false, "已停止", history, rounds, cancelled = true)
                else -> SubagentTurnResult(false, (throwable.cause ?: throwable).message ?: throwable.javaClass.simpleName, history, rounds)
            }
        } finally {
            runCatching { executor?.close() }
        }
    }
}
