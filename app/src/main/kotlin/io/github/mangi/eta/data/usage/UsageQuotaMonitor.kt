package io.github.mangi.eta.data.usage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.MainActivity
import java.time.ZoneId
import kotlinx.coroutines.runBlocking

/**
 * 额度提醒：账本每写入一条，检查该服务商的额度，用到 80% 和 100% 时各提醒一次，不拦截请求。
 * 固定周期（日、周、月）每个周期每档只提醒一次；5 小时滚动窗口提醒后 5 小时内不再重复。
 * 在账本的写入线程上执行；读不到设置（例如没有初始化 DataStore 的进程）时跳过。
 */
internal object UsageQuotaMonitor {
    private const val CHANNEL = "eta_usage_quota"
    private const val PREFS = "eta_usage_quota_notified"
    private const val MAX_ROWS = 50_000
    private const val ROLLING_COOLDOWN_MS = 5 * 60 * 60 * 1000L

    fun check(context: Context, providerId: String, now: Long = System.currentTimeMillis()) {
        runCatching { checkInternal(context, providerId, now) }.onFailure { failure ->
            AndroidAgentLogger.warnThrottled("usage_quota_check_failed") {
                "Usage quota check failed: type=${failure.safeLogType()}"
            }
        }
    }

    private fun checkInternal(context: Context, providerId: String, now: Long) {
        val pricing = runCatching { runBlocking { SettingsDataStore.usagePricing() } }.getOrNull() ?: return
        val quota = pricing.quotas.firstOrNull { it.providerId == providerId } ?: return
        if (!quota.notifyAt80 && !quota.notifyAt100) return
        val zone = ZoneId.systemDefault()
        val start = UsageReports.quotaPeriodStart(quota.period, now, zone)
        val records = runBlocking { EtaDatabase.get(context).modelUsageDao().between(start, now + 1, MAX_ROWS) }
        val providers = runCatching { runBlocking { ProviderRepository.allProviders() } }.getOrDefault(emptyList())
        val usage = UsageReports.quotaUsage(quota, records, now, zone, UsagePriceResolver(providers, pricing)::cost)
        val threshold = when {
            usage.ratio >= 1.0 && quota.notifyAt100 -> 100
            usage.ratio >= 0.8 && quota.notifyAt80 -> 80
            else -> return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rolling = quota.period == UsageQuota.PERIOD_FIVE_HOURS
        val key = if (rolling) "$providerId:${quota.period}" else "$providerId:${quota.period}:$start"
        val notified = prefs.getInt("$key:threshold", 0)
        val notifiedAt = prefs.getLong("$key:at", 0)
        val stillCooling = rolling && now - notifiedAt < ROLLING_COOLDOWN_MS
        if (notified >= threshold && (!rolling || stillCooling)) return
        prefs.edit().putInt("$key:threshold", threshold).putLong("$key:at", now).apply()
        val name = providers.firstOrNull { it.id == providerId }?.name ?: providerId
        notify(context, providerId, name, threshold, usage)
    }

    private fun notify(context: Context, providerId: String, providerName: String, percent: Int, usage: QuotaUsage) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.usage_quota_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val quota = usage.quota
        val used = if (quota.limitUsd != null) {
            "$" + "%.2f".format(usage.usedUsd) + " / $" + "%.2f".format(quota.limitUsd)
        } else {
            "${usage.usedTokens} / ${quota.limitTokens} tokens"
        }
        val open = PendingIntent.getActivity(
            context,
            providerId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.usage_quota_notification_title, providerName, percent))
            .setContentText(context.getString(R.string.usage_quota_notification_text, used))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(CHANNEL.hashCode() + providerId.hashCode(), notification)
    }
}
