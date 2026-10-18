package io.github.mangi.eta.data.usage

import io.github.mangi.eta.data.db.ModelUsageRecordEntity
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsagePricingTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    // 测试用的价格，数值只为验证计算，不代表任何服务商的实际定价。
    private val astra = ModelPrice(
        ModelPrice.USD, input = 10.0, cachedInput = 1.0, output = 50.0,
        longContextThreshold = 272_000, longInput = 20.0, longOutput = 75.0,
    )
    private val claude = ModelPrice(ModelPrice.USD, input = 4.0, cachedInput = 0.2, cacheWrite = 5.0, output = 20.0)
    private val kimi = ModelPrice(ModelPrice.CNY, input = 20.0, output = 80.0)

    @Test
    fun costSplitsUncachedCachedAndOutputAndAppliesLongContextTier() {
        // 22.7K 输入中 22.5K 命中缓存，输出 300：0.2K × 10 + 22.5K × 1 + 0.3K × 50（每百万）。
        val short = record(prompt = 22_700, cached = 22_500, output = 300)
        assertEquals((200 * 10.0 + 22_500 * 1.0 + 300 * 50.0) / 1e6, ModelCostCalculator.costUsd(short, astra, 1.0, 7.1)!!, 1e-12)
        val long = record(prompt = 300_000, cached = 0, output = 1_000)
        assertEquals((300_000 * 20.0 + 1_000 * 75.0) / 1e6, ModelCostCalculator.costUsd(long, astra, 1.0, 7.1)!!, 1e-12)
        // 中转站倍率。
        assertEquals((200 * 10.0 + 22_500 * 1.0 + 300 * 50.0) / 1e6 * 0.3, ModelCostCalculator.costUsd(short, astra, 0.3, 7.1)!!, 1e-12)
    }

    @Test
    fun cacheWritesAreChargedAndCnyPricesAreConverted() {
        val withWrite = record(prompt = 1_100, cached = 900, cacheWrite = 100, output = 50)
        // 未缓存 100 × 4 + 缓存读 900 × 0.2 + 缓存写 100 × 5 + 输出 50 × 20。
        assertEquals((100 * 4.0 + 900 * 0.2 + 100 * 5.0 + 50 * 20.0) / 1e6, ModelCostCalculator.costUsd(withWrite, claude, 1.0, 7.1)!!, 1e-12)

        val cny = record(prompt = 1_000_000, cached = 0, output = 0)
        assertEquals(20.0 / 7.1, ModelCostCalculator.costUsd(cny, kimi, 1.0, 7.1)!!, 1e-9)
        assertNull(ModelCostCalculator.costUsd(cny, kimi, 1.0, 0.0))
        assertNull(ModelCostCalculator.costUsd(record(prompt = 0, cached = 0, output = 0, hasUsage = false), kimi, 1.0, 7.1))
        assertEquals(1.0 / 7.1, ModelCostCalculator.rechargeMultiplier(1.0, 1.0, 7.1)!!, 1e-12)
    }

    @Test
    fun pricesApplyToEveryProviderByNormalizedModelId() {
        val settings = UsagePricingSettings(
            providerMultipliers = mapOf("relay" to 0.5),
            priceOverrides = mapOf(ModelIds.normalize("gpt-6.1-sol") to astra),
        )
        val resolver = UsagePriceResolver(settings)
        // 价格按模型 ID 对所有服务商生效，中转站的模型名带前缀或日期也能对上；已删除的服务商同样计价。
        assertEquals(astra, resolver.price(record(providerId = "relay", model = "openai/GPT-6.1-Sol-2026-09-30")))
        assertEquals(astra, resolver.price(record(providerId = "builtin-openai", model = "gpt-6.1-sol")))
        assertEquals(astra, resolver.price(record(providerId = "deleted", model = "gpt-6.1-sol")))
        assertNull(resolver.price(record(model = "gpt-6-sol")))
        assertEquals(1_000_000 * 10.0 / 1e6 * 0.5, resolver.cost(record(providerId = "relay", model = "gpt-6.1-sol", prompt = 1_000_000))!!, 1e-12)
    }

    @Test
    fun priceCatalogGroupsUnpricedManualAndImportedSourcesAndFilters() {
        val settings = UsagePricingSettings(priceOverrides = mapOf(
            "my-model" to ModelPrice(ModelPrice.USD, input = 1.0, output = 2.0),
            "gpt-6-sol" to astra.copy(importedFrom = "openai/gpt-6-sol", importedProvider = "OpenAI"),
            "kimi-k3" to kimi.copy(importedFrom = "moonshotai/kimi-k3", importedProvider = "Moonshot AI"),
        ))
        val groups = PriceCatalog.groups(settings, unpricedModels = listOf("relay/Mystery-1", "my-model"))
        assertEquals(
            listOf(PriceCatalog.UNPRICED, PriceCatalog.MANUAL, PriceCatalog.IMPORTED_PREFIX + "Moonshot AI", PriceCatalog.IMPORTED_PREFIX + "OpenAI"),
            groups.map { it.key },
        )
        assertEquals(listOf("mystery-1"), groups[0].rows.map { it.modelId })
        assertEquals(listOf("my-model"), groups[1].rows.map { it.modelId })
        assertEquals("OpenAI", groups[3].label)

        val searched = PriceCatalog.groups(settings, emptyList(), query = "SOL")
        assertEquals(listOf(PriceCatalog.IMPORTED_PREFIX + "OpenAI"), searched.map { it.key })
    }

    @Test
    fun editingAnImportedPriceMakesItManualAndKeepsTheLongContextTier() {
        val imported = astra.copy(source = "models.dev · OpenAI · gpt-6-astra · 2026-10-18", importedFrom = "openai/gpt-6-astra", importedProvider = "OpenAI")
        assertSame(imported, PriceCatalog.edited(imported, imported.copy()))

        val changed = PriceCatalog.edited(imported, imported.copy(input = 12.0))
        assertEquals("", changed.importedFrom)
        assertEquals("", changed.source)
        assertEquals(272_000L, changed.longContextThreshold)
        assertEquals(12.0, changed.input, 0.0)

        val cny = PriceCatalog.edited(imported, imported.copy(currency = ModelPrice.CNY))
        assertNull(cny.longContextThreshold)
        assertNull(cny.longInput)
    }

    @Test
    fun modelIdsAreNormalizedLikeCcSwitch() {
        assertEquals("step-3.5-flash", ModelIds.normalize("stepfun-ai/step-3.5-flash"))
        assertEquals("kimi-k2-0905", ModelIds.normalize("moonshotai/kimi-k2-0905:exa"))
        assertEquals("gpt-5.2-codex-low", ModelIds.normalize("gpt-5.2-codex@low"))
        assertEquals("gpt-5.5", ModelIds.normalize("OpenAI/GPT-5.5-2026-05-14"))
        assertEquals("claude-haiku-4-5", ModelIds.normalize("claude-haiku-4-5-20251001"))
        assertEquals("claude-opus-4-8", ModelIds.normalize("claude-opus-4-8[1m]"))
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

    @Test
    fun conversationSummaryCountsEveryRequestButOnlyPricesKnownModels() {
        val records = listOf(
            record(prompt = 1_000, cached = 800, output = 100),
            record(model = "unknown-model", prompt = 1_000, cached = 0, output = 100, subagentIndex = 0),
            record(hasUsage = false),
        )
        val resolver = UsagePriceResolver(UsagePricingSettings(priceOverrides = mapOf("gpt-6-astra" to astra)))
        val total = UsageReports.summarize(records, resolver::cost)
        assertEquals(3, total.requests)
        assertEquals(1, total.requestsWithoutUsage)
        assertEquals(2_000L, total.promptTokens)
        assertEquals(0.4, total.cachedRatio!!, 1e-12)
        assertEquals(ModelCostCalculator.costUsd(records.first(), astra, 1.0, 7.1)!!, total.costUsd!!, 1e-12)
        assertTrue(total.costUsd!! > 0)
    }

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
