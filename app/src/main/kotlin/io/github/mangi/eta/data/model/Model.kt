package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Model(
    val id: String,
    val modelId: String,
    val displayName: String,
    val ownedBy: String? = null,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val contextWindow: Int? = null,
    val contextWindowOverride: Int? = null,
    val inputModalities: List<String> = listOf(TEXT_MODALITY),
    val outputModalities: List<String> = listOf(TEXT_MODALITY),
    val attachment: Boolean? = null,
    val toolCall: Boolean? = null,
    val reasoning: Boolean? = null,
    val reasoningCapabilities: ModelReasoningCapabilities? = null,
    val reasoningOverride: Boolean? = null,
    val reasoningCapabilitiesOverride: ModelReasoningCapabilities? = null,
    val structuredOutput: Boolean? = null,
    val supportsTemperature: Boolean? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    val source: ModelSource = ModelSource.MANUAL,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Eta 默认对这个模型发送的思考强度（AG-16 返工）：新会话、电脑端不指定强度、各入口选“跟随”时都用它。
     * 为空表示服务商默认（不发送强度参数）。与能力里的 defaultEffort（服务商不传参时大约的档位）不同。
     */
    val defaultReasoningEffort: ReasoningEffort? = null,
) {
    val effectiveContextWindow: Int?
        get() = contextWindowOverride

    val effectiveReasoning: Boolean?
        get() = reasoningOverride ?: reasoning

    val effectiveReasoningCapabilities: ModelReasoningCapabilities?
        get() = when (reasoningOverride) {
            false -> null
            true -> reasoningCapabilitiesOverride ?: ModelReasoningCapabilities()
            null -> reasoningCapabilities
        }

    val supportsVision: Boolean
        get() = attachment == true || inputModalities.any { it.equals(IMAGE_MODALITY, ignoreCase = true) }

    val supportsTools: Boolean
        get() = toolCall == true

    val supportsReasoning: Boolean
        get() = effectiveReasoning == true

    companion object {
        const val TEXT_MODALITY = "text"
        const val IMAGE_MODALITY = "image"
    }
}

@Serializable
enum class ModelSource {
    MANUAL,
    REMOTE,
    CATALOG,
}
