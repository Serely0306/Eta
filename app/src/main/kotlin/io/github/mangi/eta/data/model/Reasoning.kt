package io.github.mangi.eta.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Responses 端点的推理摘要档位（reasoning.summary）。OpenAI 不提供完整思维链，只有摘要。 */
object ReasoningSummaryMode {
    const val AUTO = "auto"
    const val DETAILED = "detailed"
    const val CONCISE = "concise"
    val ALL = listOf(AUTO, DETAILED, CONCISE)

    fun normalize(value: String?): String = value?.takeIf { it in ALL } ?: AUTO
}

@Serializable(with = ReasoningEffortSerializer::class)
enum class ReasoningEffort(
    val wireValue: String,
    val displayName: String,
    internal val rank: Int,
) {
    @SerialName("off")
    OFF("off", "Off", 0),

    @SerialName("default")
    DEFAULT("default", "Default", 1),

    @SerialName("minimal")
    MINIMAL("minimal", "Minimal", 2),

    @SerialName("low")
    LOW("low", "Low", 3),

    @SerialName("medium")
    MEDIUM("medium", "Medium", 4),

    @SerialName("high")
    HIGH("high", "High", 5),

    @SerialName("xhigh")
    XHIGH("xhigh", "XHigh", 6),

    @SerialName("max")
    MAX("max", "Max", 7),
    ;

    val enablesReasoning: Boolean
        get() = this != OFF

    companion object {
        fun fromWireValue(value: String?): ReasoningEffort? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireValue == normalized }
                ?: when (normalized) {
                    "none" -> OFF
                    "x-high", "extra_high", "extra-high" -> XHIGH
                    else -> null
                }
        }

        fun fromLegacy(thinkingEnabled: Boolean): ReasoningEffort =
            if (thinkingEnabled) DEFAULT else OFF

        /** 入口只给出开关（如小布的快速 / 深度思考）：关闭为 Off；开启时沿用配置的强度，配置为 Off 时用 Default。 */
        fun forToggle(enabled: Boolean, configured: ReasoningEffort): ReasoningEffort =
            if (!enabled) OFF else configured.takeIf { it.enablesReasoning } ?: DEFAULT
    }
}

/**
 * 各入口的思考强度。[defaultEffort] 用于新会话和选“跟随默认强度”的入口；其余字段为 null 时跟随它。
 * 实际发送前按模型能力就近取档（[ModelReasoningCapabilities.normalize]）。
 */
data class ReasoningSettings(
    val defaultEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    /** Eta 语音入口（电源键、助理浮层）单独选择的模型；为空时跟随默认模型。 */
    val voiceModelId: String? = null,
    val voiceEffort: ReasoningEffort? = null,
    /** 厂商助手入口（小爱、小布）的强度；模型在 vendorAssistantModelId 中单独保存。 */
    val vendorEffort: ReasoningEffort? = null,
)

object ReasoningEffortSerializer : KSerializer<ReasoningEffort> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ReasoningEffort", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ReasoningEffort) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): ReasoningEffort =
        ReasoningEffort.fromWireValue(decoder.decodeString()) ?: ReasoningEffort.DEFAULT
}

@Serializable
data class ModelReasoningCapabilities(
    val supportedEfforts: List<ReasoningEffort> = emptyList(),
    val defaultEffort: ReasoningEffort? = null,
    val defaultEnabled: Boolean? = null,
    val mandatory: Boolean = false,
    val canDisable: Boolean = false,
    val supportsBudget: Boolean = false,
    val maxBudgetTokens: Int? = null,
    val supportsMaxTokens: Boolean? = null,
) {
    val selectableEfforts: List<ReasoningEffort>
        get() = buildList {
            if (canDisable && !mandatory) add(ReasoningEffort.OFF)
            add(ReasoningEffort.DEFAULT)
            supportedEfforts
                .asSequence()
                .filter { it != ReasoningEffort.OFF && it != ReasoningEffort.DEFAULT }
                .distinct()
                .sortedBy(ReasoningEffort::rank)
                .forEach(::add)
        }

    fun normalize(requested: ReasoningEffort): ReasoningEffort {
        val selectable = selectableEfforts
        if (requested in selectable) return requested
        if (requested == ReasoningEffort.OFF || requested == ReasoningEffort.DEFAULT) {
            return ReasoningEffort.DEFAULT
        }
        return selectable
            .filter { it != ReasoningEffort.OFF && it.rank <= requested.rank }
            .maxByOrNull(ReasoningEffort::rank)
            ?: ReasoningEffort.DEFAULT
    }
}
