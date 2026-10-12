package io.github.mangi.eta.agent.tool

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 屏幕与共享浏览器的跨 run 互斥。不同会话的 run 可以并行，但同一时刻只有一个 run
 * 操作前台界面或离屏浏览器；持有者用完工具后空闲超过 [IDLE_RELEASE_MS] 自动让出，
 * run 结束时全部释放。其他 run 请求时有界等待，超时由调用方返回明确的忙碌错误。
 */
internal object ForegroundResourceLease {
    enum class Resource(val busyCode: String, val busyMessage: String) {
        SCREEN(
            "SCREEN_BUSY",
            "另一个 Agent 任务正在操作屏幕，等待后仍未让出，本次未执行。" +
                "可先完成不需要界面的步骤，或用 ask_user 询问用户是否继续等待；不要连续重试。",
        ),
        BROWSER(
            "BROWSER_BUSY",
            "另一个 Agent 任务正在使用 Agent 浏览器，等待后仍未让出，本次未执行。" +
                "可改用 fetch_url 读取静态页面，或稍后再试；不要连续重试。",
        ),
    }

    const val IDLE_RELEASE_MS = 45_000L
    const val DEFAULT_WAIT_MS = 60_000L
    private const val POLL_MS = 250L

    private val SCREEN_ENTRY_TOOLS = setOf("launch_app", "open_uri", "set_alarm", "set_timer")

    private class Holder(val runId: String) {
        var inUse = 0
        var lastUsedAt = 0L
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val holders = mutableMapOf<Resource, Holder>()

    /** 需要独占的资源：操作或读取前台界面、拉起界面的工具占用屏幕，browser_use 占用浏览器。 */
    fun resourceFor(toolName: String): Resource? = when {
        toolName == "browser_use" -> Resource.BROWSER
        AgentToolRequirements.requiresAccessibility(toolName) || toolName in SCREEN_ENTRY_TOOLS -> Resource.SCREEN
        else -> null
    }

    /** 屏幕租约变化后在锁外回调；接管期间的通知屏蔽据此重新判断。 */
    @Volatile var onScreenLeaseChanged: (() -> Unit)? = null

    /** 有 run 正在操作屏幕，或刚用完、仍在空闲让出窗口内。 */
    fun screenTakeoverActive(now: () -> Long = ::monotonicMillis): Boolean = lock.withLock {
        val holder = holders[Resource.SCREEN] ?: return@withLock false
        holder.inUse > 0 || now() - holder.lastUsedAt < IDLE_RELEASE_MS
    }

    /** 取得资源；同一 run 可重入。返回 false 表示等待超时或已取消。 */
    fun acquire(
        resource: Resource,
        runId: String,
        waitMs: Long = DEFAULT_WAIT_MS,
        isCancelled: () -> Boolean = { false },
        now: () -> Long = ::monotonicMillis,
    ): Boolean = acquireLocked(resource, runId, waitMs, isCancelled, now).also { acquired ->
        if (acquired && resource == Resource.SCREEN) notifyScreenChanged()
    }

    private fun acquireLocked(
        resource: Resource,
        runId: String,
        waitMs: Long,
        isCancelled: () -> Boolean,
        now: () -> Long,
    ): Boolean = lock.withLock {
        val deadline = now() + waitMs
        var acquired = false
        while (!acquired) {
            val holder = holders[resource]
            val available = holder == null || holder.runId == runId ||
                (holder.inUse == 0 && now() - holder.lastUsedAt >= IDLE_RELEASE_MS)
            if (available) {
                val current = holder?.takeIf { it.runId == runId } ?: Holder(runId).also { holders[resource] = it }
                current.inUse += 1
                acquired = true
                continue
            }
            val remaining = deadline - now()
            if (isCancelled() || remaining <= 0) break
            try {
                changed.await(minOf(remaining, POLL_MS), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        acquired
    }

    /** 一次工具调用结束；资源仍归该 run，空闲计时从此刻开始。 */
    fun finish(resource: Resource, runId: String, now: () -> Long = ::monotonicMillis) {
        lock.withLock {
            holders[resource]?.takeIf { it.runId == runId }?.let { holder ->
                holder.inUse = (holder.inUse - 1).coerceAtLeast(0)
                holder.lastUsedAt = now()
            }
            changed.signalAll()
        }
        if (resource == Resource.SCREEN) notifyScreenChanged()
    }

    fun releaseAll(runId: String) {
        val releasedScreen = lock.withLock {
            val screen = holders[Resource.SCREEN]?.runId == runId
            holders.entries.removeAll { it.value.runId == runId }
            changed.signalAll()
            screen
        }
        if (releasedScreen) notifyScreenChanged()
    }

    private fun notifyScreenChanged() {
        runCatching { onScreenLeaseChanged?.invoke() }
    }

    fun holderOf(resource: Resource): String? = lock.withLock { holders[resource]?.runId }

    private fun monotonicMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
}
