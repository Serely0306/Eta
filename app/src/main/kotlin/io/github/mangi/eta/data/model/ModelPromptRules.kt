package io.github.mangi.eta.data.model

import io.github.mangi.eta.data.usage.ModelIds

/**
 * 按模型的提示词（AG-40）：规则是“匹配模式 → 提示词”，存成以规整后的模式为键的表，对所有服务商生效。
 * 模式按 [ModelIds.normalize] 规整（与价格匹配同一套规则），末尾可以有一个 [WILDCARD] 作前缀通配。
 * 同时命中多条时，精确匹配优先，其次取最长的前缀，只用一条。
 */
internal object ModelPromptRules {
    const val WILDCARD = "*"

    /** 规整后的模式；为空，或 [WILDCARD] 不止一个、不在末尾时返回 null。 */
    fun normalizePattern(raw: String): String? {
        val text = raw.trim()
        val prefix = text.removeSuffix(WILDCARD)
        if (WILDCARD in prefix) return null
        val normalized = ModelIds.normalize(prefix)
        return when {
            prefix.length < text.length -> normalized + WILDCARD
            normalized.isEmpty() -> null
            else -> normalized
        }
    }

    /** 命中 [modelId] 的那条规则的模式；没有命中时为 null。 */
    fun match(rules: Map<String, String>, modelId: String): String? {
        val id = ModelIds.normalize(modelId)
        if (id in rules) return id
        return rules.keys
            .filter { it.endsWith(WILDCARD) && id.startsWith(it.removeSuffix(WILDCARD)) }
            .maxByOrNull { it.length }
    }
}
