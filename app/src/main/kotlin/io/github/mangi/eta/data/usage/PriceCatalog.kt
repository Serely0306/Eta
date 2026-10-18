package io.github.mangi.eta.data.usage

/** 价格页的一行：规整后的模型 ID 与它的价格（未定价时为空）。 */
internal data class PriceRow(val modelId: String, val price: ModelPrice?)

/** 价格页的一组：在用但未定价、手动填写，以及按 models.dev 来源服务商分组的导入价格。 */
internal data class PriceGroup(val key: String, val label: String, val rows: List<PriceRow>)

/** 价格页的分组，参照 CC Switch 的定价表：按模型 ID 一行，可搜索，按组收纳。 */
internal object PriceCatalog {
    const val UNPRICED = "unpriced"
    const val MANUAL = "manual"
    const val IMPORTED_PREFIX = "imported:"

    /**
     * [unpricedModels] 是在用但查不到价格的模型；导入的价格按来源服务商分组，手动填写的单独一组。
     * [query] 非空时只留模型 ID 含有它的行，并去掉空组。组的 [PriceGroup.label] 为空时由界面给出文字。
     */
    fun groups(settings: UsagePricingSettings, unpricedModels: Collection<String>, query: String = ""): List<PriceGroup> {
        val needle = ModelIds.normalize(query)
        val unpriced = unpricedModels.map(ModelIds::normalize).filter { it.isNotEmpty() && it !in settings.priceOverrides }
            .distinct().sorted().map { PriceRow(it, null) }
        val (imported, manual) = settings.priceOverrides.toSortedMap().entries.partition { it.value.importedFrom.isNotEmpty() }
        val importedGroups = imported.groupBy { it.value.importedProvider.ifBlank { it.value.importedFrom.substringBefore('/') } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (provider, entries) -> PriceGroup(IMPORTED_PREFIX + provider, provider, entries.map { PriceRow(it.key, it.value) }) }
        return (listOf(PriceGroup(UNPRICED, "", unpriced), PriceGroup(MANUAL, "", manual.map { PriceRow(it.key, it.value) })) + importedGroups)
            .map { group -> group.copy(rows = group.rows.filter { needle.isEmpty() || it.modelId.contains(needle) }) }
            .filter { it.rows.isNotEmpty() }
    }

    /**
     * 用 models.dev 的价格更新价格表：[selected] 写入或覆盖；[refreshImported] 为真时，已导入的条目按原来源刷新。
     * 返回新的设置和实际写入的条数；手动填写的价格不会被覆盖。
     */
    fun applyImport(
        settings: UsagePricingSettings,
        snapshot: ModelsDevSnapshot,
        selected: Collection<ModelsDevPrice> = emptyList(),
        refreshImported: Boolean = false,
    ): Pair<UsagePricingSettings, Int> {
        val overrides = settings.priceOverrides.toMutableMap()
        var changed = 0
        selected.forEach { price ->
            overrides[price.normalizedId] = price.toModelPrice(snapshot.fetchedOn)
            changed++
        }
        if (refreshImported) {
            val byKey = snapshot.prices.associateBy { it.key }
            settings.priceOverrides.forEach { (id, price) ->
                val fresh = byKey[price.importedFrom] ?: return@forEach
                if (selected.none { it.normalizedId == id }) {
                    overrides[id] = fresh.toModelPrice(snapshot.fetchedOn)
                    changed++
                }
            }
        }
        return settings.copy(priceOverrides = overrides) to changed
    }

    /**
     * 在价格对话框里改过的价格：没改动时原样保留；改了就变为手动填写，更新导入时不再覆盖。
     * 长上下文档不在对话框里编辑，保留原值；换了币种时去掉，免得两种币种混在一条价格里。
     */
    fun edited(original: ModelPrice, edited: ModelPrice): ModelPrice {
        if (edited == original) return original
        val manual = edited.copy(source = "", importedFrom = "", importedProvider = "")
        return if (edited.currency == original.currency) manual else manual.copy(
            longContextThreshold = null, longInput = null, longCachedInput = null, longCacheWrite = null, longOutput = null,
        )
    }

    /** 给在用但未定价的模型按模型 ID 匹配 models.dev 的价格；返回匹配到的条目与没找到的模型。 */
    fun matchUnpriced(snapshot: ModelsDevSnapshot, unpricedModels: Collection<String>): Pair<List<ModelsDevPrice>, List<String>> {
        val ids = unpricedModels.map(ModelIds::normalize).filter(String::isNotEmpty).distinct()
        val matched = ids.mapNotNull { ModelsDev.bestMatch(snapshot.prices, it) }
        val missing = ids.filter { id -> matched.none { it.normalizedId == id } }
        return matched to missing
    }
}
