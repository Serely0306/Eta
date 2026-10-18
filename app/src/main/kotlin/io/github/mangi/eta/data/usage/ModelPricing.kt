package io.github.mangi.eta.data.usage

import io.github.mangi.eta.data.db.ModelUsageRecordEntity
import kotlinx.serialization.Serializable

/**
 * 每百万 token 的价格。[cachedInput] 为空时缓存读按输入价计，[cacheWrite] 为空时缓存写按输入价计。
 * [longContextThreshold] 非空时，单次请求的输入超过它就整次按 long 价格计（OpenAI、百炼的长上下文档）。
 */
@Serializable
internal data class ModelPrice(
    val currency: String,
    val input: Double,
    val cachedInput: Double? = null,
    val cacheWrite: Double? = null,
    val output: Double,
    val longContextThreshold: Long? = null,
    val longInput: Double? = null,
    val longCachedInput: Double? = null,
    val longCacheWrite: Double? = null,
    val longOutput: Double? = null,
    /** 价格来源与日期，显示在价格页。用户自填的价格为空。 */
    val source: String = "",
    /** 从 models.dev 导入时的条目（`服务商/模型`），用于“更新已导入的价格”；自填的为空。 */
    val importedFrom: String = "",
    /** 导入来源的服务商名，价格页按它分组。 */
    val importedProvider: String = "",
) {
    companion object {
        const val USD = "USD"
        const val CNY = "CNY"
    }
}

/**
 * 匹配价格前先规整模型 ID，规则与 CC Switch 相同：小写；去掉最后一个 `/` 之前的前缀、`:` 之后的后缀
 * 与末尾的 `[1m]`；`@` 换成 `-`；去掉 `-YYYY-MM-DD`、`-YYYYMMDD` 日期后缀。
 * 中转站常给模型名加厂商前缀或日期，规整后才能对上价格。
 */
internal object ModelIds {
    private val DATE_SUFFIX = Regex("-(\\d{4}-\\d{2}-\\d{2}|\\d{8})$")

    fun normalize(raw: String): String = raw.trim().lowercase()
        .substringAfterLast('/')
        .substringBefore(':')
        .removeSuffix("[1m]")
        .replace('@', '-')
        .replace(DATE_SUFFIX, "")
        .trim()
}

/** 费用换算：统一折算成美元，人民币价按 [cnyPerUsd] 换算，再乘服务商倍率。 */
internal object ModelCostCalculator {
    /** 一次请求的费用（美元）；没有价格或没有用量时为 null。 */
    fun costUsd(record: ModelUsageRecordEntity, price: ModelPrice?, multiplier: Double, cnyPerUsd: Double): Double? {
        price ?: return null
        if (!record.hasUsage) return null
        val long = price.longContextThreshold?.let { record.promptTokens > it } == true && price.longInput != null
        val input = if (long) price.longInput!! else price.input
        val cached = (if (long) price.longCachedInput else price.cachedInput) ?: input
        val write = (if (long) price.longCacheWrite else price.cacheWrite) ?: input
        val output = (if (long) price.longOutput else null) ?: price.output
        val uncached = (record.promptTokens - record.cachedTokens - record.cacheWriteTokens).coerceAtLeast(0)
        val raw = (uncached * input + record.cachedTokens * cached + record.cacheWriteTokens * write +
            record.outputTokens * output) / 1_000_000.0
        val usd = if (price.currency == ModelPrice.CNY) {
            if (cnyPerUsd <= 0) return null
            raw / cnyPerUsd
        } else {
            raw
        }
        return usd * multiplier.coerceAtLeast(0.0)
    }

    /** 中转站按充值比例换算的倍率：花 [paidCny] 元得到 [creditUsd] 美元额度时，实际花费 / 官方美元价。 */
    fun rechargeMultiplier(paidCny: Double, creditUsd: Double, cnyPerUsd: Double): Double? {
        if (paidCny <= 0 || creditUsd <= 0 || cnyPerUsd <= 0) return null
        return (paidCny / cnyPerUsd) / creditUsd
    }
}
