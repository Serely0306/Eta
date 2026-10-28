package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentUserAnswer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * @param steeringLabel 运行中插入的消息在模型上下文里的标注；子 Agent 收到的是主 Agent 的补充，不是用户的话。
 * @param endTurnBeforeSteering 为真时（子 Agent，AG-27），模型写出最终回复后不再消费排队消息继续本回合：
 *   回合照常结束、交出结论，排队消息由 [drainSteering] 取走作为下一回合的开头（与 Claude Code 一致）。
 */
internal class AgentRunController(
    val steeringLabel: String = DEFAULT_STEERING_LABEL,
    private val endTurnBeforeSteering: Boolean = false,
) {
    private val resources = CopyOnWriteArraySet<CancellableResource>()

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    private val questionCondition = lock.newCondition()
    private val steeringMessages = ArrayDeque<Steering>()
    /** 取消时还没插入的消息（见 [cancel]、[drainSteering]）。 */
    private val cancelledSteering = mutableListOf<Steering>()
    /** 子 Agent 的完成通知；与用户补充分开，进入模型时标明是系统通知，界面显示为系统提示。 */
    private val notices = ArrayDeque<Notice>()
    private var acceptingSteering = true
    @Volatile
    private var paused = false
    private var pendingQuestionId: String? = null
    private var pendingAnswer: AgentUserAnswer? = null

    val isPaused: Boolean
        get() = paused

    fun cancel() {
        lock.withLock {
            cancelled = true
            acceptingSteering = false
            // 不再插入当前回合；另存一份，子 Agent 管理器回合结束时经 drainSteering 取回，写进中断通知。
            cancelledSteering.addAll(steeringMessages)
            steeringMessages.clear()
            // 通知不清：主 run 结束时由子 Agent 管理器取回，恢复为未投递。
            paused = false
            pauseCondition.signalAll()
            questionCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /** 一条运行中插入的消息与它在模型上下文里的标注（谁说的）。 */
    data class Steering(val text: String, val label: String)

    /**
     * 将补充指令排入下一个 turn。steering 不取消当前模型请求或工具批次。[label] 默认是本 run 的标注；
     * 用户在界面上直接发给子 Agent 的消息另标“用户补充指令”（AG-27）。
     */
    fun steer(text: String, label: String = steeringLabel): Boolean {
        val prompt = text.trim()
        if (prompt.isBlank()) return false
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            // 等待提问回答时，用户直接输入的文字就是对该问题的回答，不再排进下一轮。
            if (pendingQuestionId != null && pendingAnswer == null) {
                pendingAnswer = AgentUserAnswer.text(prompt)
                questionCondition.signalAll()
                return true
            }
            steeringMessages.addLast(Steering(prompt, label))
        }
        return true
    }

    /**
     * 阻塞当前工具直到用户回答、跳过、超时或任务取消。取消时抛出 [AgentRunCancelledException]；
     * 同一时刻只有一个待回答问题。
     */
    fun awaitUserAnswer(questionId: String, timeoutMs: Long): AgentUserAnswer {
        lock.withLock {
            if (cancelled) throw AgentRunCancelledException()
            pendingQuestionId = questionId
            pendingAnswer = null
            try {
                var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                while (pendingAnswer == null && !cancelled && remaining > 0) {
                    remaining = try {
                        questionCondition.awaitNanos(remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        cancelled = true
                        0L
                    }
                }
                if (cancelled) throw AgentRunCancelledException()
                return pendingAnswer ?: AgentUserAnswer.TimedOut
            } finally {
                pendingQuestionId = null
                pendingAnswer = null
            }
        }
    }

    /** 回答当前问题；问题已结束、编号不匹配或任务已取消时返回 false。 */
    fun answerQuestion(questionId: String, answer: AgentUserAnswer): Boolean = lock.withLock {
        if (cancelled || pendingQuestionId != questionId || pendingAnswer != null) return@withLock false
        pendingAnswer = answer
        questionCondition.signalAll()
        true
    }

    val pendingQuestion: String?
        get() = lock.withLock { pendingQuestionId?.takeIf { pendingAnswer == null } }

    /**
     * 把子 Agent 的完成通知排进下一个 turn。run 已封口（最终回答之后）或已取消时返回 false，
     * 由调用方留到下一个 run 或自动唤醒。
     */
    fun deliverNotice(notice: Notice): Boolean = lock.withLock {
        if (cancelled || !acceptingSteering) return@withLock false
        notices.addLast(notice)
        true
    }

    /** 一次取走全部待处理的通知，合并成一条交给模型。 */
    fun pollNotices(): List<Notice> = lock.withLock {
        notices.toList().also { notices.clear() }
    }

    val hasPendingNotices: Boolean
        get() = lock.withLock { notices.isNotEmpty() }

    /** [modelText] 进入模型上下文；[display] 只给界面的系统提示；[mailIds] 是对应的待投递通知。 */
    data class Notice(val modelText: String, val display: String, val mailIds: List<Long> = emptyList())

    /** 默认逐条消费，避免后来的补充指令越过前一条的模型回合。 */
    fun pollSteering(): Steering? =
        lock.withLock { steeringMessages.pollFirst() }

    fun pollSteeringMessage(): String? = pollSteering()?.text

    /**
     * 自然结束前原子地消费最后一条 steering；若队列为空则永久关闭本 run 的接收入口。
     * 这样 Service 不会在 loop 已返回后仍把补充指令误报为已接收。
     */
    fun pollSteeringEntryOrSeal(): Steering? =
        lock.withLock {
            if (!endTurnBeforeSteering) steeringMessages.pollFirst()?.let { return it }
            // 封口前刚到的子 Agent 通知：不封口，由主循环取走后继续下一轮（见 AgentLoop）。
            if (notices.isNotEmpty()) return null
            acceptingSteering = false
            null
        }

    fun pollSteeringOrSeal(): String? = pollSteeringEntryOrSeal()?.text

    /** 回合结束后还没插入的消息（[endTurnBeforeSteering] 时封口留下的），一次取走。 */
    fun drainSteering(): List<Steering> = lock.withLock {
        (cancelledSteering + steeringMessages).also {
            cancelledSteering.clear()
            steeringMessages.clear()
        }
    }

    val hasPendingSteering: Boolean
        get() = lock.withLock { steeringMessages.isNotEmpty() }

    val pendingSteeringCount: Int
        get() = lock.withLock { steeringMessages.size }

    /**
     * 暂停执行：后续 [throwIfCancelled] 调用会阻塞挂起，直到 [resume] 或 [cancel]。
     * 在工作线程的检查点调用，不会阻塞调用方线程。
     */
    fun pause() {
        lock.withLock { paused = true }
    }

    /**
     * 恢复执行：唤醒被 [throwIfCancelled] 阻塞的工作线程，从挂起点继续。
     */
    fun resume() {
        lock.withLock {
            paused = false
            pauseCondition.signalAll()
        }
    }

    /**
     * 检查点：若已取消则抛异常；若已暂停则阻塞挂起直到恢复或取消。
     * 在 agent 循环的每轮/每步调用，实现暂停可恢复、取消即终止。
     */
    fun throwIfCancelled() {
        lock.withLock {
            while (paused && !cancelled) {
                try {
                    pauseCondition.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelled = true
                }
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    fun awaitRetryDelay(delayMs: Long) {
        throwIfCancelled()
        val cancelledLatch = CountDownLatch(1)
        val binding = register { cancelledLatch.countDown() }
        try {
            cancelledLatch.await(delayMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            binding.close()
        }
        throwIfCancelled()
    }

    fun register(cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(private val cancelBlock: () -> Unit) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancelBlock()
        }
    }

    companion object {
        const val DEFAULT_STEERING_LABEL = "用户补充指令"
        const val COORDINATOR_STEERING_LABEL = "主 Agent 补充"
    }
}

internal class AgentRunCancelledException : RuntimeException("Agent run cancelled")
