package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentUserQuestion
import java.util.concurrent.ConcurrentHashMap

/**
 * 界面操作前“由谁来做”的交接问题（AG-11 返工，用户 2026-10-27）：
 * - 只在 App 会话里问；语音、自动任务、电脑端委托没人盯着屏幕，直接由 Agent 操作；
 * - 30 秒没回复由 Agent 操作，之后同一会话 3 分钟内不再问。冷却只记在内存里，进程重启后清零。
 */
internal object GuiHandoffPolicy {
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    /** 不该问时返回（status, message）；该问时返回 null。 */
    fun skipReason(handoffSource: String?, conversationId: String, now: Long = System.currentTimeMillis()): Pair<String, String>? {
        if (handoffSource != null && handoffSource != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            return STATUS_NOT_APPLICABLE to "这个入口没有人看着屏幕，直接由你操作"
        }
        val until = cooldownUntil[conversationId] ?: return null
        if (now >= until) {
            cooldownUntil.remove(conversationId, until)
            return null
        }
        return STATUS_COOLDOWN to "刚问过且 30 秒内没有回复，3 分钟内不再询问，直接由你操作"
    }

    fun startCooldown(conversationId: String, now: Long = System.currentTimeMillis()) {
        cooldownUntil[conversationId] = now + AgentUserQuestion.GUI_HANDOFF_COOLDOWN_MS
    }

    internal fun clearForTest() = cooldownUntil.clear()

    const val STATUS_COOLDOWN = "cooldown"
    const val STATUS_NOT_APPLICABLE = "not_applicable"
}
