package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import io.github.mangi.eta.agent.model.ModelFallbackPolicy
import io.github.mangi.eta.agent.model.ModelUsageContext
import io.github.mangi.eta.agent.model.ModelUsageScope
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.db.ConversationSubagentEntity
import io.github.mangi.eta.data.db.ConversationSubagentMailEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** 子 Agent 与通知的持久化；生产用 Room，单测用内存实现。 */
internal interface SubagentStore {
    fun agents(conversationId: String): List<ConversationSubagentEntity>
    fun save(agent: ConversationSubagentEntity)
    fun interruptStale(ownerInstanceId: String, now: Long)
    fun addMail(mail: ConversationSubagentMailEntity)
    fun undeliveredMail(conversationId: String): List<ConversationSubagentMailEntity>
    fun markDelivered(ids: List<Long>, runId: String?)
    fun deleteConversation(conversationId: String)
}

internal class RoomSubagentStore(context: Context) : SubagentStore {
    private val dao = EtaDatabase.get(context.applicationContext).subagentDao()

    override fun agents(conversationId: String) = io { dao.agents(conversationId) }
    override fun save(agent: ConversationSubagentEntity) = io { dao.upsertAgent(agent) }
    override fun interruptStale(ownerInstanceId: String, now: Long) { io { dao.interruptStale(ownerInstanceId, now) } }
    override fun addMail(mail: ConversationSubagentMailEntity) { io { dao.insertMail(mail) } }
    override fun undeliveredMail(conversationId: String) = io { dao.undeliveredMail(conversationId) }
    override fun markDelivered(ids: List<Long>, runId: String?) {
        if (ids.isNotEmpty()) io { dao.markMailDelivered(ids, runId) }
    }
    override fun deleteConversation(conversationId: String) = io { dao.deleteConversation(conversationId) }

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
 * 会话级子 Agent（AG-19）：派出后立即返回，子 Agent 在自己的线程上跑完一个回合；之后可以继续发消息，
 * 以保存的上下文开始新回合。每个回合结束（完成或失败）写一条通知：
 * - 主 run 正在运行：并入它的下一轮（[AgentRunController.deliverNotice]）；
 * - 主 run 正在 `wait_agents` 等这个子 Agent：直接作为等待结果返回；
 * - 主 run 已结束：留在库里，下一个 run 开始时交出，或由 [wakeListener] 唤醒主 Agent。
 * 主 run 被停止时，本会话运行中的子 Agent 一起停（已中断，可继续）。进程重启后，上一个进程里运行中的
 * 子 Agent 改为已中断。
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
        val number: Int get() = row.agentId.removePrefix(ID_PREFIX).toIntOrNull() ?: 0
    }

    private class ParentRun(val runId: String, val controller: AgentRunController)

    private class ConversationState {
        val agents = LinkedHashMap<String, LiveAgent>()
        var parent: ParentRun? = null
        /** 正被 wait_agents 等待的子 Agent：它们的结果由等待返回，不再另发通知。 */
        val waiting = HashMap<String, Int>()
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val conversations = HashMap<String, ConversationState>()
    private var staleInterrupted = false

    /** 主 run 已结束、通知无处投递时调用（阶段 B 由 App 与电脑端接入自动唤醒）。 */
    @Volatile
    var wakeListener: ((conversationId: String) -> Unit)? = null

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
            store.undeliveredMail(conversationId).any { it.agentId !in state.waiting }
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

    fun send(environment: SubagentEnvironment, target: String, message: String, interrupt: Boolean): JSONObject =
        lock.withLock {
            val state = state(environment.conversationId)
            val agent = findLocked(state, target) ?: return notFound(target)
            if (agent.row.status == STATUS_CLOSED) return failure("SUBAGENT_CLOSED", "子 Agent ${agent.row.agentId} 已关闭，不能再发消息")
            agent.controller?.let { running ->
                if (!interrupt && running.steer(message)) {
                    return JSONObject().put("ok", true).put("agent_id", agent.row.agentId).put("delivery", "queued")
                }
                // 要打断，或回合正好在收尾：等它结束后再开新回合。
                if (interrupt) running.cancel()
                awaitIdleLocked(agent)
                if (agent.controller != null) return failure("SUBAGENT_BUSY", "子 Agent ${agent.row.agentId} 还没停下，稍后再试")
            }
            limitError(state)?.let { return it }
            startTurnLocked(environment, agent, message)
            JSONObject().put("ok", true).put("agent_id", agent.row.agentId).put("delivery", "started")
        }

    /**
     * 等指定或全部运行中的子 Agent，任一结束、用户追加了输入、或超时即返回。取走的结论不再另发通知。
     */
    fun wait(environment: SubagentEnvironment, targets: List<String>, timeoutMs: Long, parent: AgentRunController): JSONObject =
        lock.withLock {
            val state = state(environment.conversationId)
            val agents = if (targets.isEmpty()) {
                state.agents.values.filter { it.controller != null }
            } else {
                targets.map { target -> findLocked(state, target) ?: return notFound(target) }
            }
            if (agents.isEmpty()) {
                return JSONObject().put("ok", true).put("agents", JSONArray())
                    .put("message", "没有运行中的子 Agent")
            }
            val ids = agents.map { it.row.agentId }
            ids.forEach { state.waiting[it] = (state.waiting[it] ?: 0) + 1 }
            try {
                var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                // 分片等待，期间检查用户是否追加了输入或停止了主 run。
                while (agents.all { it.controller != null } && !parent.hasPendingSteering && !parent.isCancelled && remaining > 0) {
                    val slice = minOf(remaining, WAIT_SLICE_NANOS)
                    remaining -= slice - changed.awaitNanos(slice)
                }
                if (parent.isCancelled) throw AgentRunCancelledException()
                val finished = agents.filter { it.controller == null }.map { it.row.agentId }.toSet()
                val taken = store.undeliveredMail(environment.conversationId).filter { it.agentId in finished }
                store.markDelivered(taken.map { it.id }, environment.runId)
                JSONObject()
                    .put("ok", true)
                    .put("timed_out", finished.isEmpty() && remaining <= 0)
                    .put("interrupted_by_user", finished.isEmpty() && parent.hasPendingSteering)
                    .put("agents", JSONArray(agents.map { describe(it, includeResult = it.row.agentId in finished) }))
            } finally {
                ids.forEach { id -> state.waiting[id]?.let { if (it <= 1) state.waiting.remove(id) else state.waiting[id] = it - 1 } }
                deliverLocked(environment.conversationId, state)
            }
        }

    /** 不阻塞的状态查询；还没交给主 Agent 的结论在这里给出全文，之后不再另发通知。 */
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
        val controller = AgentRunController()
        val turn = agent.row.turn + 1
        val history = AgentToolBatchRecovery.completeInterrupted(agent.history)
        agent.controller = controller
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
        agent.row = agent.row.copy(updatedAt = now())
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
        if (status == STATUS_COMPLETED || status == STATUS_FAILED) {
            store.addMail(
                ConversationSubagentMailEntity(
                    conversationId = environment.conversationId,
                    agentId = agentId,
                    turn = agent.row.turn,
                    status = status,
                    payload = mailPayload(agent.row, result.text).toString(),
                    createdAt = now(),
                    deliveredRunId = null,
                ),
            )
            if (agentId !in state.waiting) deliverLocked(environment.conversationId, state)
        }
        changed.signalAll()
    }

    private fun deliverLocked(conversationId: String, state: ConversationState) {
        val mails = store.undeliveredMail(conversationId).filter { it.agentId !in state.waiting }
        if (mails.isEmpty()) return
        val parent = state.parent
        val notice = AgentRunController.Notice(
            modelText = mails.joinToString("\n\n") { "<subagent_notification>\n${it.payload}\n</subagent_notification>" } +
                "\n以上是子 Agent 的通知，不是用户的新指令；需要时用 send_agent 继续交给它，用不到了用 close_agent 关闭。",
            display = mails.joinToString("\n") { displayLine(it) },
            mailIds = mails.map { it.id },
        )
        if (parent != null && parent.controller.deliverNotice(notice)) {
            store.markDelivered(notice.mailIds, parent.runId)
        } else if (parent == null) {
            // 没有进行中的主 run：解锁后再唤醒，避免监听方回调进来时死锁。
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
            runCatching { store.interruptStale(ownerInstanceId, now()) }
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
    }

    private fun describe(agent: LiveAgent, includeResult: Boolean): JSONObject = JSONObject()
        .put("agent_id", agent.row.agentId)
        .put("name", agent.row.name)
        .put("status", if (agent.controller != null) STATUS_RUNNING else agent.row.status)
        .put("task", agent.row.task.take(TASK_ECHO_CHARS))
        .put("turn", agent.row.turn)
        .put("rounds", agent.row.rounds)
        .apply {
            val text = agent.row.lastError.ifBlank { agent.row.lastResult }
            if (includeResult) {
                if (agent.row.lastError.isNotBlank()) put("error", agent.row.lastError) else put("result", agent.row.lastResult)
            } else if (text.isNotBlank()) {
                put("result_preview", text.take(PREVIEW_CHARS))
            }
        }

    private fun mailPayload(row: ConversationSubagentEntity, text: String): JSONObject = JSONObject()
        .put("agent_id", row.agentId)
        .put("name", row.name)
        .put("status", row.status)
        .put("turn", row.turn)
        .put(if (row.status == STATUS_COMPLETED) "result" else "error", text)

    private fun displayLine(mail: ConversationSubagentMailEntity): String {
        val payload = runCatching { JSONObject(mail.payload) }.getOrNull()
        val name = payload?.optString("name")?.takeIf(String::isNotBlank) ?: mail.agentId
        val label = if (name == mail.agentId) name else "$name（${mail.agentId}）"
        val text = payload?.optString("result")?.takeIf(String::isNotBlank) ?: payload?.optString("error").orEmpty()
        val verb = if (mail.status == STATUS_COMPLETED) "完成" else "失败"
        return "$label $verb：${text.take(DISPLAY_CHARS)}"
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
        private const val DISPLAY_CHARS = 160
        private const val STOP_WAIT_MS = 10_000L
        private val WAIT_SLICE_NANOS = TimeUnit.SECONDS.toNanos(1)

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
        val roundLimitHit = AtomicBoolean(false)
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
                if (event is AgentEvent.RoundStarted && ++rounds > AgentSubagentTool.MAX_ROUNDS && roundLimitHit.compareAndSet(false, true)) {
                    turn.controller.cancel()
                }
            }
            val history = response.contextSnapshot?.messages ?: (turn.history + userMessage + response.transcript)
            SubagentTurnResult(true, response.content.trim().take(AgentSubagentTool.MAX_RESULT_CHARS), history, rounds)
        } catch (throwable: Throwable) {
            val history = (throwable as? AgentModelExecutionException)?.transcript?.takeIf { it.isNotEmpty() }
                ?.let { turn.history + userMessage + it } ?: latest
            when {
                roundLimitHit.get() -> SubagentTurnResult(false, "超过 ${AgentSubagentTool.MAX_ROUNDS} 轮仍未完成，已停止本回合", history, rounds)
                turn.controller.isCancelled -> SubagentTurnResult(false, "已停止", history, rounds, cancelled = true)
                else -> SubagentTurnResult(false, (throwable.cause ?: throwable).message ?: throwable.javaClass.simpleName, history, rounds)
            }
        } finally {
            runCatching { executor?.close() }
        }
    }
}
