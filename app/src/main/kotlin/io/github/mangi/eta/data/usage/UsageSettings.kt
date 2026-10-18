package io.github.mangi.eta.data.usage

import kotlinx.serialization.Serializable

/**
 * 用量统计的计价设置。费用统一折算成美元：人民币价按 [cnyPerUsd] 换算，再乘服务商倍率。
 * 汇率由用户手动填写，不联网获取；默认值只是起点。
 */
@Serializable
internal data class UsagePricingSettings(
    val cnyPerUsd: Double = DEFAULT_CNY_PER_USD,
    /** 服务商 id → 倍率；没有条目时为 1。 */
    val providerMultipliers: Map<String, Double> = emptyMap(),
    /** [priceKey] → 用户填写的价格，优先于内置价格。 */
    val priceOverrides: Map<String, ModelPrice> = emptyMap(),
    val quotas: List<UsageQuota> = emptyList(),
) {
    fun multiplier(providerId: String): Double = providerMultipliers[providerId] ?: 1.0

    companion object {
        const val DEFAULT_CNY_PER_USD = 7.10

        fun priceKey(providerId: String, model: String): String = "$providerId/${model.trim().lowercase()}"
    }
}

/**
 * 服务商的额度：周期内的估算费用（美元）或 token 用量达到 80%、100% 时提醒，不拦截请求。
 * [limitUsd] 与 [limitTokens] 二选一。
 */
@Serializable
internal data class UsageQuota(
    val providerId: String,
    val period: String = PERIOD_MONTH,
    val limitUsd: Double? = null,
    val limitTokens: Long? = null,
    val notifyAt80: Boolean = true,
    val notifyAt100: Boolean = true,
) {
    companion object {
        const val PERIOD_FIVE_HOURS = "five_hours"
        const val PERIOD_DAY = "day"
        const val PERIOD_WEEK = "week"
        const val PERIOD_MONTH = "month"
        val PERIODS = listOf(PERIOD_FIVE_HOURS, PERIOD_DAY, PERIOD_WEEK, PERIOD_MONTH)
    }
}
