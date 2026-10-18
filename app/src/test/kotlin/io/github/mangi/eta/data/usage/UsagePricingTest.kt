package io.github.mangi.eta.data.usage

import io.github.mangi.eta.data.db.ModelUsageRecordEntity
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UsagePricingTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun officialProvidersOnlyMatchTheirOwnPricesWhileRelaysMatchByModelName() {
        assertEquals(10.0, BuiltinModelPrices.find(ProviderSourceTypes.OPENAI, "GPT-6-Astra")!!.input, 0.0)
        assertEquals(10.0, BuiltinModelPrices.find(ProviderSourceTypes.CUSTOM, "gpt-6-astra")!!.input, 0.0)
        // 同名模型在百炼上的价格与 Kimi 官方不同，没有核对过就不套用。
        assertNull(BuiltinModelPrices.find(ProviderSourceTypes.BAILIAN, "kimi-k3"))
        assertNull(BuiltinModelPrices.find(ProviderSourceTypes.MINIMAX, "MiniMax-M3"))
        assertNull(BuiltinModelPrices.find(ProviderSourceTypes.CUSTOM, "gpt-6.1-sol"))
    }

    @Test
    fun costSplitsUncachedCachedAndOutputAndAppliesLongContextTier() {
        val price = BuiltinModelPrices.find(ProviderSourceTypes.OPENAI, "gpt-6-astra")!!
        // 22.7K 输入中 22.5K 命中缓存，输出 300：0.2K × 10 + 22.5K × 1 + 0.3K × 50（每百万）。
        val short = record(prompt = 22_700, cached = 22_500, output = 300)
        assertEquals((200 * 10.0 + 22_500 * 1.0 + 300 * 50.0) / 1e6, ModelCostCalculator.costUsd(short, price, 1.0, 7.1)!!, 1e-12)
        val long = record(prompt = 300_000, cached = 0, output = 1_000)
        assertEquals((300_000 * 20.0 + 1_000 * 75.0) / 1e6, ModelCostCalculator.costUsd(long, price, 1.0, 7.1)!!, 1e-12)
        // 中转站倍率。
        assertEquals((200 * 10.0 + 22_500 * 1.0 + 300 * 50.0) / 1e6 * 0.3, ModelCostCalculator.costUsd(short, price, 0.3, 7.1)!!, 1e-12)
    }

    @Test
    fun anthropicCacheWritesAreChargedAndCnyPricesAreConverted() {
        val claude = BuiltinModelPrices.find(ProviderSourceTypes.ANTHROPIC, "claude-opus-5-5")!!
        val withWrite = record(prompt = 1_100, cached = 900, cacheWrite = 100, output = 50)
        // 未缓存 100 × 4 + 缓存读 900 × 0.2 + 缓存写 100 × 5 + 输出 50 × 20。
        assertEquals((100 * 4.0 + 900 * 0.2 + 100 * 5.0 + 50 * 20.0) / 1e6, ModelCostCalculator.costUsd(withWrite, claude, 1.0, 7.1)!!, 1e-12)

        val kimi = BuiltinModelPrices.find(ProviderSourceTypes.MOONSHOT, "kimi-k3")!!
        val cny = record(prompt = 1_000_000, cached = 0, output = 0)
        assertEquals(20.0 / 7.1, ModelCostCalculator.costUsd(cny, kimi, 1.0, 7.1)!!, 1e-9)
        assertNull(ModelCostCalculator.costUsd(cny, kimi, 1.0, 0.0))
        assertNull(ModelCostCalculator.costUsd(record(prompt = 0, cached = 0, output = 0, hasUsage = false), kimi, 1.0, 7.1))
        assertEquals(1.0 / 7.1, ModelCostCalculator.rechargeMultiplier(1.0, 1.0, 7.1)!!, 1e-12)
    }

    @Test
    fun overridesWinAndDeletedProvidersFallBackToRelayMatching() {
        val relay = CustomProviderSetting(id = "relay", name = "Relay", baseUrl = "https://relay.example.com/v1")
        val official = OpenAiCompatibleProviderSetting(
            id = "builtin-openai", name = "OpenAI", baseUrl = "https://api.openai.com/v1", sourceType = ProviderSourceTypes.OPENAI,
        )
        val override = ModelPrice(ModelPrice.USD, input = 1.0, output = 2.0)
        val settings = UsagePricingSettings(
            providerMultipliers = mapOf("relay" to 0.5),
            priceOverrides = mapOf(UsagePricingSettings.priceKey("relay", "gpt-6.1-sol") to override),
        )
        val resolver = UsagePriceResolver(listOf(relay, official), settings)
        assertEquals(override, resolver.price(record(providerId = "relay", model = "GPT-6.1-Sol")))
        assertNotNull(resolver.price(record(providerId = "deleted", model = "gpt-6-sol")))
        assertEquals(1_000_000 * 1.0 / 1e6 * 0.5, resolver.cost(record(providerId = "relay", model = "gpt-6.1-sol", prompt = 1_000_000))!!, 1e-12)
    }

    @Test
    fun reportBucketsGroupsAndQuotaUseLocalCalendar() {
        val now = millis("2026-10-17T15:00")
        val records = listOf(
            record(createdAt = millis("2026-10-17T09:30"), prompt = 1_000, cached = 500, output = 100, source = "pc_mcp"),
            record(createdAt = millis("2026-10-17T10:00"), prompt = 2_000, output = 200, subagentIndex = 1, source = "pc_mcp"),
            record(createdAt = millis("2026-10-16T23:59"), prompt = 4_000, output = 0, source = "agent_ui"),
            record(createdAt = millis("2026-10-17T11:00"), hasUsage = false, source = "agent_ui"),
        )
        val today = UsageReports.build(records, UsageRange.TODAY, now, zone) { 0.01 }
        assertEquals(3, today.total.requests)
        assertEquals(1, today.total.requestsWithoutUsage)
        assertEquals(8, today.buckets.size)
        assertEquals(3_300L, today.buckets[3].tokens)
        assertEquals(setOf("chat", UsageReports.PURPOSE_SUBAGENT), today.byPurpose.map { it.key }.toSet())

        val week = UsageReports.build(records, UsageRange.DAYS_7, now, zone) { null }
        assertEquals(7, week.buckets.size)
        assertEquals(3, week.unpricedRequests)
        assertNull(week.total.costUsd)

        val quota = UsageQuota(providerId = "relay", period = UsageQuota.PERIOD_DAY, limitTokens = 4_000)
        val usage = UsageReports.quotaUsage(quota, records, now, zone) { null }
        assertEquals(3_300L, usage.usedTokens)
        assertEquals(3_300.0 / 4_000, usage.ratio, 1e-12)
        assertEquals(millis("2026-10-12T00:00"), UsageReports.quotaPeriodStart(UsageQuota.PERIOD_WEEK, now, zone))
    }

    private fun millis(local: String) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun record(
        providerId: String = "relay",
        model: String = "gpt-6-astra",
        prompt: Long = 0,
        cached: Long = 0,
        cacheWrite: Long = 0,
        output: Long = 0,
        hasUsage: Boolean = true,
        createdAt: Long = 0,
        subagentIndex: Int? = null,
        source: String = "agent_ui",
    ) = ModelUsageRecordEntity(
        createdAt = createdAt, durationMs = 0, source = source, runId = "run", purpose = "chat", subagentIndex = subagentIndex,
        providerId = providerId, providerName = "Relay", model = model, modelDisplayName = model, endpoint = "responses",
        reasoningEffort = "default", status = ModelUsageRecordEntity.STATUS_OK, hasUsage = hasUsage,
        promptTokens = prompt, cachedTokens = cached, cacheWriteTokens = cacheWrite, outputTokens = output, reasoningTokens = 0,
    )
}
