package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import top.yukonga.miuix.kmp.basic.DropdownItem

/**
 * 强度的显示名。Default 不是某个具体档位，而是“不发送强度参数、由服务商决定”，显示为“服务商默认”；
 * 内置目录标注了服务商默认档位时一并注明。
 */
@Composable
internal fun reasoningEffortLabel(effort: ReasoningEffort, providerDefault: ReasoningEffort? = null): String =
    when (effort) {
        ReasoningEffort.DEFAULT -> providerDefault
            ?.takeIf { it != ReasoningEffort.DEFAULT && it != ReasoningEffort.OFF }
            ?.let { stringResource(R.string.reasoning_effort_provider_default_known, it.displayName) }
            ?: stringResource(R.string.reasoning_effort_provider_default)
        else -> effort.displayName
    }

/** 模型的思考能力；模型不存在或不支持思考时为 null。 */
internal fun reasoningCapabilitiesFor(providers: List<ProviderSetting>, modelId: String?): ModelReasoningCapabilities? =
    modelId?.let { id ->
        providers.firstNotNullOfOrNull { provider ->
            provider.models.firstOrNull { it.id == id }?.let { RuntimeConfigRepository.reasoningCapabilities(provider, it) }
        }
    }

/**
 * 某个入口的强度选项：第一项是“跟随”（null），其后是模型可选的档位。
 * [capabilities] 为 null 时只有“跟随”。
 */
internal fun entryEffortOptions(capabilities: ModelReasoningCapabilities?): List<ReasoningEffort?> =
    listOf<ReasoningEffort?>(null) + capabilities?.selectableEfforts.orEmpty()

/** 强度下拉项：[followLabel] 对应 null。 */
@Composable
internal fun effortDropdownItems(
    options: List<ReasoningEffort?>,
    followLabel: String,
    providerDefault: ReasoningEffort?,
): List<DropdownItem> = options.map { effort ->
    DropdownItem(text = effort?.let { reasoningEffortLabel(it, providerDefault) } ?: followLabel)
}
