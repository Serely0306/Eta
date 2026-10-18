package io.github.mangi.eta.data.usage

import io.github.mangi.eta.data.db.ModelUsageRecordEntity
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** 价格解析：用户填写的价格优先，其次内置价格；服务商已删除时按自定义服务商匹配原厂价。 */
internal class UsagePriceResolver(
    providers: List<ProviderSetting>,
    private val settings: UsagePricingSettings,
) {
    private val sourceTypes = providers.associate { it.id to ProviderSourceRegistry.resolve(it) }

    fun price(record: ModelUsageRecordEntity): ModelPrice? =
        settings.override(record.model)
            ?: BuiltinModelPrices.find(sourceTypes[record.providerId] ?: ProviderSourceTypes.CUSTOM, record.model)

    fun cost(record: ModelUsageRecordEntity): Double? =
        ModelCostCalculator.costUsd(record, price(record), settings.multiplier(record.providerId), settings.cnyPerUsd)
}

internal enum class UsageRange { TODAY, DAYS_7, DAYS_30, MONTH }

/** 柱状图的一根柱子：[start] 起的一段时间内的费用与 token（输入 + 输出）。 */
internal data class UsageBucket(val start: Long, val costUsd: Double, val tokens: Long)

/** 按模型、入口或用途分组的合计；[costUsd] 为 null 表示这一组没有任何可计价的请求。 */
internal data class UsageGroup(
    val key: String,
    val label: String,
    val requests: Int,
    val requestsWithoutUsage: Int,
    val promptTokens: Long,
    val cachedTokens: Long,
    val outputTokens: Long,
    val costUsd: Double?,
) {
    val tokens: Long get() = promptTokens + outputTokens
    val cachedRatio: Double? get() = promptTokens.takeIf { it > 0 }?.let { cachedTokens.toDouble() / it }
}

internal data class UsageReport(
    val from: Long,
    val to: Long,
    val total: UsageGroup,
    val reasoningTokens: Long,
    /** 有用量但没有价格、因而没有计入费用的请求数。 */
    val unpricedRequests: Int,
    val buckets: List<UsageBucket>,
    val byModel: List<UsageGroup>,
    val bySource: List<UsageGroup>,
    val byPurpose: List<UsageGroup>,
)

internal object UsageReports {
    /** 时间范围 [from, to)：今天、最近 7 天、最近 30 天、本月，都以本地零点为界。 */
    fun bounds(range: UsageRange, now: Long, zone: ZoneId): Pair<Long, Long> {
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).truncatedTo(ChronoUnit.DAYS)
        val start = when (range) {
            UsageRange.TODAY -> today
            UsageRange.DAYS_7 -> today.minusDays(6)
            UsageRange.DAYS_30 -> today.minusDays(29)
            UsageRange.MONTH -> today.withDayOfMonth(1)
        }
        return start.toInstant().toEpochMilli() to today.plusDays(1).toInstant().toEpochMilli()
    }

    /** 今天按 3 小时分段，其余按天分段。 */
    fun bucketStarts(range: UsageRange, now: Long, zone: ZoneId): List<Long> {
        val (from, to) = bounds(range, now, zone)
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(from), zone)
        return generateSequence(start) { if (range == UsageRange.TODAY) it.plusHours(3) else it.plusDays(1) }
            .map { it.toInstant().toEpochMilli() }
            .takeWhile { it < to }
            .toList()
    }

    fun build(
        records: List<ModelUsageRecordEntity>,
        range: UsageRange,
        now: Long,
        zone: ZoneId,
        cost: (ModelUsageRecordEntity) -> Double?,
    ): UsageReport {
        val (from, to) = bounds(range, now, zone)
        val inRange = records.filter { it.createdAt in from until to }
        val costs = inRange.associateWith(cost)
        val starts = bucketStarts(range, now, zone)
        val buckets = starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: to
            val slice = inRange.filter { it.createdAt in start until end }
            UsageBucket(start, slice.sumOf { costs[it] ?: 0.0 }, slice.sumOf { it.promptTokens + it.outputTokens })
        }
        fun groupBy(key: (ModelUsageRecordEntity) -> String, label: (ModelUsageRecordEntity) -> String) =
            inRange.groupBy(key).map { (groupKey, rows) -> group(groupKey, label(rows.first()), rows, costs) }
                .sortedWith(compareByDescending<UsageGroup> { it.costUsd ?: -1.0 }.thenByDescending { it.requests })
        return UsageReport(
            from = from,
            to = to,
            total = group("", "", inRange, costs),
            reasoningTokens = inRange.sumOf { it.reasoningTokens },
            unpricedRequests = inRange.count { it.hasUsage && costs[it] == null },
            buckets = buckets,
            byModel = groupBy({ "${it.providerId}/${it.model}" }, { "${it.providerName} / ${it.modelDisplayName}" }),
            bySource = groupBy({ it.source }, { it.source }),
            byPurpose = groupBy({ purposeKey(it) }, { purposeKey(it) }),
        )
    }

    /** 子 Agent 的请求单独归为一类，其余按请求用途。 */
    fun purposeKey(record: ModelUsageRecordEntity): String =
        if (record.subagentIndex != null) PURPOSE_SUBAGENT else record.purpose

    const val PURPOSE_SUBAGENT = "subagent"

    /** 一组记录的合计（例如一个会话），不按时间范围截取。 */
    fun summarize(records: List<ModelUsageRecordEntity>, cost: (ModelUsageRecordEntity) -> Double?): UsageGroup =
        group("", "", records, records.associateWith(cost))

    private fun group(
        key: String,
        label: String,
        rows: List<ModelUsageRecordEntity>,
        costs: Map<ModelUsageRecordEntity, Double?>,
    ): UsageGroup {
        val priced = rows.mapNotNull { costs[it] }
        return UsageGroup(
            key = key,
            label = label,
            requests = rows.size,
            requestsWithoutUsage = rows.count { !it.hasUsage },
            promptTokens = rows.sumOf { it.promptTokens },
            cachedTokens = rows.sumOf { it.cachedTokens },
            outputTokens = rows.sumOf { it.outputTokens },
            costUsd = priced.takeIf { it.isNotEmpty() }?.sum(),
        )
    }

    /** 额度周期的起点：5 小时为滚动窗口，其余以本地零点、周一、每月 1 日为界。 */
    fun quotaPeriodStart(period: String, now: Long, zone: ZoneId): Long {
        val current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val today = current.truncatedTo(ChronoUnit.DAYS)
        return when (period) {
            UsageQuota.PERIOD_FIVE_HOURS -> now - FIVE_HOURS_MS
            UsageQuota.PERIOD_DAY -> today.toInstant().toEpochMilli()
            UsageQuota.PERIOD_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toInstant().toEpochMilli()
            else -> today.withDayOfMonth(1).toInstant().toEpochMilli()
        }
    }

    /** 额度已用比例；按金额时只计可计价的请求，按 token 时计输入 + 输出。 */
    fun quotaUsage(
        quota: UsageQuota,
        records: List<ModelUsageRecordEntity>,
        now: Long,
        zone: ZoneId,
        cost: (ModelUsageRecordEntity) -> Double?,
    ): QuotaUsage {
        val start = quotaPeriodStart(quota.period, now, zone)
        val rows = records.filter { it.providerId == quota.providerId && it.createdAt >= start && it.createdAt <= now }
        val usedUsd = rows.sumOf { cost(it) ?: 0.0 }
        val usedTokens = rows.sumOf { it.promptTokens + it.outputTokens }
        val ratio = when {
            quota.limitUsd != null && quota.limitUsd > 0 -> usedUsd / quota.limitUsd
            quota.limitTokens != null && quota.limitTokens > 0 -> usedTokens.toDouble() / quota.limitTokens
            else -> 0.0
        }
        return QuotaUsage(quota, start, usedUsd, usedTokens, ratio)
    }

    private const val FIVE_HOURS_MS = 5 * 60 * 60 * 1000L
}

internal data class QuotaUsage(
    val quota: UsageQuota,
    val periodStart: Long,
    val usedUsd: Double,
    val usedTokens: Long,
    val ratio: Double,
)
