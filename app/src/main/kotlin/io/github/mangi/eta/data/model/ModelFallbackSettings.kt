package io.github.mangi.eta.data.model

/**
 * 模型回退链设置：按顺序保存的备用模型（[Model.id]），以及内容被拦截时是否也换模型。
 * [efforts] 按模型 id 保存单独设置的思考强度；没有条目时跟随主模型本次的强度。
 */
data class ModelFallbackSettings(
    val modelIds: List<String> = emptyList(),
    val onContentFilter: Boolean = false,
    val efforts: Map<String, ReasoningEffort> = emptyMap(),
) {
    companion object {
        const val MAX_MODELS = 3
    }
}
