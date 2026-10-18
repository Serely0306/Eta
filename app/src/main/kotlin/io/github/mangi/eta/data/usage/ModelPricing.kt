package io.github.mangi.eta.data.usage

import io.github.mangi.eta.data.db.ModelUsageRecordEntity
import io.github.mangi.eta.data.model.ProviderSourceTypes
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
    /** 价格来源与核对日期，显示在价格页。用户自填的价格为空。 */
    val source: String = "",
) {
    companion object {
        const val USD = "USD"
        const val CNY = "CNY"
    }
}

/**
 * 内置价格：只收录在官方价格页核对过的模型，每条记来源和日期，查不到的不预置。
 * 官方服务商只匹配自己的条目；自定义服务商（中转站）按模型名匹配原厂价格，再乘服务商倍率。
 */
internal object BuiltinModelPrices {
    private data class Entry(val sourceType: String, val modelId: String, val price: ModelPrice)

    private const val OPENAI_SOURCE = "OpenAI 价格页 developers.openai.com/api/docs/pricing（2026-10-17 核对）"
    private const val ANTHROPIC_SOURCE = "Anthropic 一方 API 价格（Claude API 资料 2026-10-06；缓存写为 5 分钟档）"
    private const val DEEPSEEK_SOURCE = "DeepSeek 价格页 api-docs.deepseek.com（2026-10-17 核对；按高峰时段价，非高峰为半价）"
    private const val KIMI_SOURCE = "Kimi 开放平台价格页 platform.kimi.com/docs/pricing/chat（2026-10-17 核对）"
    private const val STEPFUN_SOURCE = "阶跃星辰定价页 platform.stepfun.com/docs/zh/guides/pricing/details（2026-10-17 核对）"
    private const val BAILIAN_SOURCE = "阿里云百炼模型价格 help.aliyun.com/zh/model-studio/model-pricing（2026-10-17 核对）"

    private fun openAi(
        id: String, input: Double, cached: Double, write: Double?, output: Double,
        longInput: Double, longCached: Double, longWrite: Double?, longOutput: Double,
    ) = Entry(
        ProviderSourceTypes.OPENAI, id,
        ModelPrice(ModelPrice.USD, input, cached, write, output, 272_000, longInput, longCached, longWrite, longOutput, OPENAI_SOURCE),
    )

    private fun anthropic(id: String, input: Double, cached: Double, output: Double) = Entry(
        ProviderSourceTypes.ANTHROPIC, id,
        ModelPrice(ModelPrice.USD, input, cached, input * 1.25, output, source = ANTHROPIC_SOURCE),
    )

    private val entries = listOf(
        openAi("gpt-6-astra", 10.0, 1.0, 12.5, 50.0, 20.0, 2.0, 25.0, 75.0),
        openAi("gpt-6-sol", 2.0, 0.2, 2.5, 10.0, 4.0, 0.4, 5.0, 15.0),
        openAi("gpt-6-luna", 0.1, 0.01, 0.125, 0.5, 0.2, 0.02, 0.25, 0.75),
        openAi("gpt-5.6-sol", 4.0, 0.4, 5.0, 20.0, 8.0, 0.8, 10.0, 30.0),
        openAi("gpt-5.6-terra", 2.0, 0.2, 2.5, 12.0, 4.0, 0.4, 5.0, 18.0),
        openAi("gpt-5.6-luna", 0.2, 0.02, 0.25, 1.2, 0.4, 0.04, 0.5, 1.8),
        openAi("gpt-5.5", 5.0, 0.5, null, 30.0, 10.0, 1.0, null, 45.0),
        anthropic("claude-fable-5-1", 10.0, 0.25, 50.0),
        anthropic("claude-fable-5", 10.0, 1.0, 50.0),
        anthropic("claude-opus-5-5", 4.0, 0.2, 20.0),
        anthropic("claude-opus-4-8", 5.0, 0.5, 25.0),
        anthropic("claude-sonnet-5", 2.0, 0.2, 10.0),
        Entry(ProviderSourceTypes.DEEPSEEK, "deepseek-flash", ModelPrice(ModelPrice.USD, 0.3, 0.006, null, 1.2, source = DEEPSEEK_SOURCE)),
        // 旧名 deepseek-v4-flash 按 Flash 价计费（价格页说明）。
        Entry(ProviderSourceTypes.DEEPSEEK, "deepseek-v4-flash", ModelPrice(ModelPrice.USD, 0.3, 0.006, null, 1.2, source = DEEPSEEK_SOURCE)),
        Entry(ProviderSourceTypes.DEEPSEEK, "deepseek-v4-pro", ModelPrice(ModelPrice.USD, 1.32, 0.044, null, 3.96, source = DEEPSEEK_SOURCE)),
        Entry(ProviderSourceTypes.MOONSHOT, "kimi-k3", ModelPrice(ModelPrice.CNY, 20.0, 2.0, null, 100.0, source = KIMI_SOURCE)),
        Entry(ProviderSourceTypes.MOONSHOT, "kimi-k2.7-code", ModelPrice(ModelPrice.CNY, 6.5, 1.3, null, 27.0, source = KIMI_SOURCE)),
        Entry(ProviderSourceTypes.MOONSHOT, "kimi-k2.7-code-highspeed", ModelPrice(ModelPrice.CNY, 13.0, 2.6, null, 54.0, source = KIMI_SOURCE)),
        Entry(ProviderSourceTypes.MOONSHOT, "kimi-k2.6", ModelPrice(ModelPrice.CNY, 6.5, 1.1, null, 27.0, source = KIMI_SOURCE)),
        Entry(ProviderSourceTypes.STEPFUN, "step-5-preview", ModelPrice(ModelPrice.CNY, 7.0, 0.7, null, 20.0, source = STEPFUN_SOURCE)),
        Entry(ProviderSourceTypes.STEPFUN, "step-3.7-flash", ModelPrice(ModelPrice.CNY, 1.35, 0.27, null, 8.1, source = STEPFUN_SOURCE)),
        // 百炼按地域定价，这里是北京地区；超过 256K 输入按长上下文档。
        Entry(
            ProviderSourceTypes.BAILIAN, "qwen3.7-plus",
            ModelPrice(ModelPrice.CNY, 2.0, 0.4, null, 8.0, 256_000, 6.0, 1.2, null, 24.0, source = "$BAILIAN_SOURCE；北京地区"),
        ),
        Entry(ProviderSourceTypes.BAILIAN, "qwen3.8-max", ModelPrice(ModelPrice.CNY, 12.0, null, null, 36.0, source = "$BAILIAN_SOURCE；全球部署，缓存价未列出，按输入价计")),
    )

    private val officialSources = entries.map { it.sourceType }.toSet()

    /**
     * 官方服务商只找自己的条目（同名模型在不同平台价格不同，例如百炼上的 Kimi）；
     * 自定义服务商按模型名找原厂条目。
     */
    fun find(sourceType: String, modelId: String): ModelPrice? {
        val model = modelId.trim().lowercase()
        if (sourceType in officialSources) {
            return entries.firstOrNull { it.sourceType == sourceType && it.modelId == model }?.price
        }
        if (sourceType != ProviderSourceTypes.CUSTOM) return null
        return entries.firstOrNull { it.modelId == model }?.price
    }
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
