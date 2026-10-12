package io.github.mangi.eta.agent.automation

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 主动触发的 Agent 任务：到点或收到匹配的通知时，以“自动任务”入口把 [prompt] 交给 Runtime 执行，
 * 结果归档进同一会话并发通知。
 */
@Serializable
internal data class AgentAutomation(
    val id: String,
    val name: String,
    val prompt: String,
    val trigger: AutomationTrigger,
    val enabled: Boolean = true,
    val createdAt: Long,
    val lastRunAt: Long? = null,
    val lastStatus: String? = null,
    val lastSummary: String? = null,
) {
    companion object {
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val MAX_AUTOMATIONS = 30
        const val MAX_NAME_CHARS = 40
        const val MAX_PROMPT_CHARS = 4_000
    }
}

@Serializable
internal sealed interface AutomationTrigger {
    /** 每天（或每周指定几天）的固定时间；[daysOfWeek] 用 ISO 编号 1=周一…7=周日，为空表示每天。 */
    @Serializable
    @SerialName("daily")
    data class Daily(val hour: Int, val minute: Int, val daysOfWeek: Set<Int> = emptySet()) : AutomationTrigger

    /** 固定间隔，从创建或上次运行起算；系统可能为省电推迟。 */
    @Serializable
    @SerialName("interval")
    data class Interval(val minutes: Int) : AutomationTrigger

    /** 单次，触发后不再执行。 */
    @Serializable
    @SerialName("once")
    data class Once(val atMillis: Long) : AutomationTrigger

    /**
     * 收到匹配的通知时触发：[packageName] 与 [keyword] 至少填一项，都填时需同时满足；
     * 关键词在标题或正文中匹配，不区分大小写。两次触发至少间隔 [cooldownMinutes] 分钟。
     */
    @Serializable
    @SerialName("notification")
    data class OnNotification(
        val packageName: String = "",
        val keyword: String = "",
        val cooldownMinutes: Int = DEFAULT_COOLDOWN_MINUTES,
    ) : AutomationTrigger

    companion object {
        const val MIN_INTERVAL_MINUTES = 15
        const val DEFAULT_COOLDOWN_MINUTES = 10
    }
}

internal object AutomationSchedule {
    /** 下一次定时触发的时间；通知触发、已执行过的单次任务与无效配置返回 null。 */
    fun nextFireAt(automation: AgentAutomation, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        when (val trigger = automation.trigger) {
            is AutomationTrigger.Daily -> nextDaily(trigger, now, zone)
            is AutomationTrigger.Interval -> {
                if (trigger.minutes < AutomationTrigger.MIN_INTERVAL_MINUTES) {
                    null
                } else {
                    val step = trigger.minutes * 60_000L
                    var next = (automation.lastRunAt ?: automation.createdAt) + step
                    // 错过的周期（关机、休眠）不补跑，从当前时间之后的下一个周期继续。
                    if (next <= now) next += ((now - next) / step + 1) * step
                    next
                }
            }
            is AutomationTrigger.Once -> trigger.atMillis.takeIf { automation.lastRunAt == null && it > now }
            is AutomationTrigger.OnNotification -> null
        }

    private fun nextDaily(trigger: AutomationTrigger.Daily, now: Long, zone: ZoneId): Long? {
        if (trigger.hour !in 0..23 || trigger.minute !in 0..59) return null
        if (trigger.daysOfWeek.any { it !in 1..7 }) return null
        val current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        var candidate = current.truncatedTo(ChronoUnit.DAYS)
            .with(LocalTime.of(trigger.hour, trigger.minute))
        repeat(8) {
            val dayOk = trigger.daysOfWeek.isEmpty() || candidate.dayOfWeek.value in trigger.daysOfWeek
            if (dayOk && candidate.toInstant().toEpochMilli() > now) return candidate.toInstant().toEpochMilli()
            candidate = candidate.plusDays(1).truncatedTo(ChronoUnit.DAYS).with(LocalTime.of(trigger.hour, trigger.minute))
        }
        return null
    }

    /** 通知是否命中该触发器；包名与关键词都为空的触发器不匹配任何通知，避免对每条通知都运行。 */
    fun matches(trigger: AutomationTrigger.OnNotification, packageName: String, title: String, text: String): Boolean {
        val pkg = trigger.packageName.trim()
        val keyword = trigger.keyword.trim()
        if (pkg.isEmpty() && keyword.isEmpty()) return false
        if (pkg.isNotEmpty() && pkg != packageName) return false
        if (keyword.isNotEmpty() && !title.contains(keyword, ignoreCase = true) && !text.contains(keyword, ignoreCase = true)) {
            return false
        }
        return true
    }

    fun cooledDown(automation: AgentAutomation, trigger: AutomationTrigger.OnNotification, now: Long): Boolean {
        val last = automation.lastRunAt ?: return true
        return now - last >= trigger.cooldownMinutes.coerceAtLeast(1) * 60_000L
    }

    /** 解析本地时间 `yyyy-MM-ddTHH:mm` 或 `yyyy-MM-dd HH:mm`。 */
    fun parseLocalDateTime(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? = runCatching {
        LocalDateTime.parse(raw.trim().replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    fun parseTime(raw: String): Pair<Int, Int>? {
        val parts = raw.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return hour to minute
    }

    fun dayName(day: Int): String = when (DayOfWeek.of(day)) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }
}
