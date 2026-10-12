package io.github.mangi.eta.agent.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

/**
 * 用 AlarmManager 为定时自动任务排期。允许精确闹钟时按时触发（触发时系统放宽后台前台服务限制）；
 * 否则退回可被系统推迟的闹钟。每次变更或执行后整体重排，重启、换时区后由 [AgentAutomationReceiver] 重排。
 */
internal object AgentAutomationScheduler {
    const val ACTION_FIRE = "io.github.mangi.eta.action.AUTOMATION_FIRE"
    const val EXTRA_ID = "automation_id"

    fun reschedule(context: Context, now: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        val alarms = appContext.getSystemService(AlarmManager::class.java) ?: return
        AgentAutomationStore.all().forEach { automation ->
            val pending = pendingIntent(appContext, automation.id)
            alarms.cancel(pending)
            if (!automation.enabled) return@forEach
            val at = AutomationSchedule.nextFireAt(automation, now) ?: return@forEach
            runCatching {
                if (alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                }
            }.onFailure { failure ->
                AndroidAgentLogger.warn("Automation alarm failed: type=${failure.safeLogType()}")
            }
        }
    }

    fun cancel(context: Context, id: String) {
        val appContext = context.applicationContext
        appContext.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(appContext, id))
    }

    fun exactAlarmsAllowed(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    private fun pendingIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, AgentAutomationReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id)
            // data 让每个任务的 PendingIntent 互不覆盖，即使 requestCode 碰撞。
            .setData(android.net.Uri.parse("eta-automation://$id")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** 定时触发与开机、时间变化后的重排入口。 */
internal class AgentAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AgentAutomationScheduler.ACTION_FIRE -> {
                val id = intent.getStringExtra(AgentAutomationScheduler.EXTRA_ID) ?: return
                // 执行可能持续数分钟：这里只完成前台服务启动与派发，run 在独立线程继续。
                AgentAutomationRunner.fire(context, id, trigger = null)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> AgentAutomationScheduler.reschedule(context)
        }
    }
}
