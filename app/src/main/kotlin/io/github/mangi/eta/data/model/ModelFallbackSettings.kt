package io.github.mangi.eta.data.model

/** 模型回退链设置：按顺序保存的备用模型（[Model.id]），以及内容被拦截时是否也换模型。 */
data class ModelFallbackSettings(
    val modelIds: List<String> = emptyList(),
    val onContentFilter: Boolean = false,
) {
    companion object {
        const val MAX_MODELS = 3
    }
}
