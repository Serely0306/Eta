package io.github.mangi.eta.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsDevTest {
    // 结构仿 https://models.dev/api.json，数值只为测试，不代表实际定价。
    private val fixture = """
        {
          "openai": {
            "id": "openai", "name": "OpenAI",
            "models": {
              "gpt-6-astra": {
                "id": "gpt-6-astra", "name": "GPT-6 Astra", "release_date": "2026-08-01",
                "modalities": {"input": ["text", "image"], "output": ["text"]},
                "cost": {"input": 10, "output": 50, "cache_read": 1,
                  "tiers": [{"input": 20, "output": 75, "cache_read": 2, "tier": {"type": "context", "size": 272000}}]}
              },
              "gpt-image-3": {"name": "GPT Image 3", "modalities": {"input": ["text"], "output": ["image"]}, "cost": {"input": 5, "output": 40}},
              "gpt-6-realtime": {"name": "GPT-6 Realtime", "cost": {"input": 5, "output": 20}},
              "gpt-old": {"name": "Old", "status": "deprecated", "cost": {"input": 1, "output": 2}},
              "text-embedding-4": {"name": "Embedding 4", "cost": {"input": 0.1, "output": 0}},
              "gpt-free": {"name": "No cost"}
            }
          },
          "openrouter": {
            "name": "OpenRouter",
            "models": {
              "openai/gpt-6-astra": {"name": "GPT-6 Astra", "release_date": "2026-08-02", "cost": {"input": 11, "output": 55}}
            }
          },
          "moonshotai": {
            "name": "Moonshot AI",
            "models": {
              "kimi-k3": {"name": "Kimi K3", "release_date": "2026-09-01", "cost": {"input": 0.6, "output": 2.5, "cache_read": 0.15}}
            }
          }
        }
    """.trimIndent()

    private val prices = ModelsDev.parse(fixture.byteInputStream())
    private val snapshot = ModelsDevSnapshot("2026-10-18", prices)

    @Test
    fun parseKeepsPricedTextModelsWithTiersNewestFirst() {
        assertEquals(listOf("moonshotai/kimi-k3", "openrouter/openai/gpt-6-astra", "openai/gpt-6-astra"), prices.map { it.key })
        val astra = prices.first { it.key == "openai/gpt-6-astra" }
        assertEquals("gpt-6-astra", astra.normalizedId)
        assertEquals(1.0, astra.cacheRead!!, 0.0)
        assertEquals(272_000L, astra.longThreshold)
        assertEquals(20.0, astra.longInput!!, 0.0)
        assertEquals(2.0, astra.longCacheRead!!, 0.0)
        assertEquals("gpt-6-astra", prices.first { it.providerId == "openrouter" }.normalizedId)
    }

    @Test
    fun bestMatchPrefersTheOriginalVendorOverAggregators() {
        assertEquals("openai/gpt-6-astra", ModelsDev.bestMatch(prices, "relay/GPT-6-Astra-2026-08-01")!!.key)
        assertNull(ModelsDev.bestMatch(prices, "mystery-1"))
    }

    @Test
    fun importedPriceKeepsItsSourceAndLongContextTier() {
        val price = prices.first { it.key == "openai/gpt-6-astra" }.toModelPrice("2026-10-18")
        assertEquals(ModelPrice.USD, price.currency)
        assertEquals("openai/gpt-6-astra", price.importedFrom)
        assertEquals("OpenAI", price.importedProvider)
        assertEquals("models.dev · OpenAI · gpt-6-astra · 2026-10-18", price.source)
        assertEquals(75.0, price.longOutput!!, 0.0)
    }

    @Test
    fun matchUnpricedAndRefreshOnlyTouchImportedPrices() {
        val (matched, missing) = PriceCatalog.matchUnpriced(snapshot, listOf("openai/gpt-6-astra", "mystery-1"))
        assertEquals(listOf("openai/gpt-6-astra"), matched.map { it.key })
        assertEquals(listOf("mystery-1"), missing)

        val manualKimi = ModelPrice(ModelPrice.USD, input = 1.0, output = 2.0)
        val stale = ModelPrice(ModelPrice.USD, input = 9.0, output = 45.0, importedFrom = "openai/gpt-6-astra", importedProvider = "OpenAI")
        val settings = UsagePricingSettings(priceOverrides = mapOf("kimi-k3" to manualKimi, "gpt-6-astra" to stale))
        val (refreshed, changed) = PriceCatalog.applyImport(settings, snapshot, refreshImported = true)
        assertEquals(1, changed)
        assertEquals(10.0, refreshed.priceOverrides.getValue("gpt-6-astra").input, 0.0)
        assertEquals(manualKimi, refreshed.priceOverrides.getValue("kimi-k3"))

        // 用户在导入弹窗里明确选中的条目会覆盖手动价格。
        val kimi = prices.first { it.key == "moonshotai/kimi-k3" }
        val (imported, count) = PriceCatalog.applyImport(settings, snapshot, selected = listOf(kimi))
        assertEquals(1, count)
        assertEquals("moonshotai/kimi-k3", imported.priceOverrides.getValue("kimi-k3").importedFrom)
    }
}
