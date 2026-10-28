package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentUserAnswer
import io.github.mangi.eta.agent.model.AgentUserQuestion
import java.util.concurrent.ConcurrentHashMap

/**
 * 界面操作前“由谁来做”的交接问题（AG-11 返工，用户 2026-10-27）：
 * - 只在 App 会话里问；语音、自动任务、电脑端委托没人盯着屏幕，直接由 Agent 操作；
 * - 30 秒没回复由 Agent 操作，或用户选了“你来操作”（用户 2026-10-28），之后同一会话 3 分钟内不再问。
 *   冷却只记在内存里，进程重启后清零。
 */
internal object GuiHandoffPolicy {
    private enum class Reason { TIMED_OUT, AGREED }
    private data class Cooldown(val until: Long, val reason: Reason)

    private val cooldowns = ConcurrentHashMap<String, Cooldown>()

    /** 不该问时返回（status, message）；该问时返回 null。 */
    fun skipReason(handoffSource: String?, conversationId: String, now: Long = System.currentTimeMillis()): Pair<String, String>? {
        if (handoffSource != null && handoffSource != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            return STATUS_NOT_APPLICABLE to "这个入口没有人看着屏幕，直接由你操作"
        }
        val cooldown = cooldowns[conversationId] ?: return null
        if (now >= cooldown.until) {
            cooldowns.remove(conversationId, cooldown)
            return null
        }
        return STATUS_COOLDOWN to when (cooldown.reason) {
            Reason.TIMED_OUT -> "刚问过且 30 秒内没有回复，3 分钟内不再询问，直接由你操作"
            Reason.AGREED -> "用户刚同意由你操作，3 分钟内不再询问，直接由你操作"
        }
    }

    /** 回答后决定是否进入冷却：没回复，或选了“你来操作”。 */
    fun onAnswered(conversationId: String, answer: AgentUserAnswer, now: Long = System.currentTimeMillis()) {
        val reason = when {
            answer == AgentUserAnswer.TimedOut -> Reason.TIMED_OUT
            answer is AgentUserAnswer.Selected &&
                answer.selections.firstOrNull()?.contains(AgentUserQuestion.GUI_HANDOFF_AGENT_OPTION) == true -> Reason.AGREED
            else -> return
        }
        cooldowns[conversationId] = Cooldown(now + AgentUserQuestion.GUI_HANDOFF_COOLDOWN_MS, reason)
    }

    internal fun clearForTest() = cooldowns.clear()

    const val STATUS_COOLDOWN = "cooldown"
    const val STATUS_NOT_APPLICABLE = "not_applicable"
}
