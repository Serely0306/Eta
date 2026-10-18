package io.github.mangi.eta.data.usage

/** 价格页的一行：规整后的模型 ID 与当前生效的价格；[custom] 表示用户填写过（新增或改了预置价）。 */
internal data class PriceRow(val modelId: String, val price: ModelPrice?, val custom: Boolean, val builtin: Boolean)

/** 价格页的一组：在用但未定价、自定义、各厂商的预置价格（[key] 为厂商的服务商来源类型）。 */
internal data class PriceGroup(val key: String, val rows: List<PriceRow>)

/** 价格页的分组，参照 CC Switch 的定价表：按模型 ID 一行，可搜索，按组收纳。 */
internal object PriceCatalog {
    const val UNPRICED = "unpriced"
    const val CUSTOM = "custom"

    /**
     * [unpricedModels] 是在用但查不到价格的模型；预置条目按厂商分组，用户改过的显示改后的价格。
     * [query] 非空时只留模型 ID 含有它的行，并去掉空组。
     */
    fun groups(settings: UsagePricingSettings, unpricedModels: Collection<String>, query: String = ""): List<PriceGroup> {
        val needle = ModelIds.normalize(query)
        val builtinIds = BuiltinModelPrices.entries.mapTo(mutableSetOf()) { it.modelId }
        val unpriced = unpricedModels.map(ModelIds::normalize).filter { it.isNotEmpty() && it !in settings.priceOverrides }
            .distinct().sorted().map { PriceRow(it, null, custom = false, builtin = false) }
        val custom = settings.priceOverrides.filterKeys { it !in builtinIds }.toSortedMap()
            .map { (id, price) -> PriceRow(id, price, custom = true, builtin = false) }
        val vendors = BuiltinModelPrices.entries.groupBy { it.sourceType }.map { (sourceType, entries) ->
            PriceGroup(sourceType, entries.distinctBy { it.modelId }.map { entry ->
                val override = settings.priceOverrides[entry.modelId]
                PriceRow(entry.modelId, override ?: entry.price, custom = override != null, builtin = true)
            })
        }
        return (listOf(PriceGroup(UNPRICED, unpriced), PriceGroup(CUSTOM, custom)) + vendors)
            .map { group -> group.copy(rows = group.rows.filter { needle.isEmpty() || it.modelId.contains(needle) }) }
            .filter { it.rows.isNotEmpty() }
    }

    /** 预置价格（不含用户改动）；不是预置模型时为 null。 */
    fun builtinPrice(modelId: String): ModelPrice? =
        ModelIds.normalize(modelId).let { id -> BuiltinModelPrices.entries.firstOrNull { it.modelId == id }?.price }
}
