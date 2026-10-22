package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.usage.ModelUsageLedger
import io.github.mangi.eta.data.usage.UsagePriceResolver
import io.github.mangi.eta.data.usage.UsagePricingSettings
import io.github.mangi.eta.data.usage.UsageReports
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.formatContextUsage
import io.github.mangi.eta.ui.screens.usage.compactTokens
import io.github.mangi.eta.ui.screens.usage.formatPercent
import io.github.mangi.eta.ui.screens.usage.formatUsd
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.RichTooltip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentContextUsageButton(
    usage: AgentContextUsageUi,
    onCompact: () -> Unit = {},
    canCompact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tooltipState = rememberTooltipState(isPersistent = true)
    val progress = usage.progress
    val progressColor = when {
        progress == null -> MiuixTheme.colorScheme.onSurfaceVariantActions
        progress >= 0.95f -> StatusError
        progress >= 0.80f -> StatusWarning
        else -> MiuixTheme.colorScheme.primary
    }
    val locale = LocalConfiguration.current.locales[0]
    val summary = formatContextUsage(
        usage = usage,
        noUsageText = stringResource(R.string.context_no_previous_usage),
        noLimitText = stringResource(R.string.context_no_model_limit),
        locale = locale,
    )
    val detail = when {
        usage.contextWindow == null -> summary
        usage.contextTokens == null -> stringResource(R.string.context_usage_after_response)
        else -> stringResource(R.string.context_usage_previous_response, summary)
    }
    val usageDescription = stringResource(
        R.string.context_usage_description,
        summary.replace('\n', ' '),
    )
    val tooltipColors = TooltipDefaults.richTooltipColors()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            RichTooltip(
                title = {
                    Text(
                        text = stringResource(R.string.ui_contextual_usage_d12810),
                        color = tooltipColors.titleContentColor,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                },
                action = if (canCompact) {
                    {
                        TextButton(
                            text = stringResource(R.string.context_compact_action),
                            onClick = {
                                onCompact()
                                tooltipState.dismiss()
                            },
                            minWidth = 0.dp,
                            minHeight = 34.dp,
                            cornerRadius = 17.dp,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    null
                },
                colors = tooltipColors,
            ) {
                Column {
                    Text(
                        text = detail,
                        color = tooltipColors.contentColor,
                        style = MiuixTheme.textStyles.body2,
                    )
                    usage.conversationId?.let { ConversationUsageLines(it, tooltipColors.contentColor) }
                }
            }
        },
        state = tooltipState,
        focusable = true,
        modifier = modifier,
    ) {
        IconButton(
            onClick = { scope.launch { tooltipState.show() } },
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
        ) {
            CircularProgressIndicator(
                progress = progress ?: 0f,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = progressColor,
                    disabledForegroundColor = progressColor,
                    backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                ),
                strokeWidth = 2.5.dp,
                size = ChatInputActionIconSize,
                modifier = Modifier.semantics {
                    contentDescription = usageDescription
                },
            )
        }
    }
}

/** 本会话在用量账本中的累计（含压缩与子 Agent），只在弹窗显示时订阅；还没有记录时不显示。 */
@Composable
private fun ConversationUsageLines(conversationId: String, color: Color) {
    val records by remember(conversationId) { ModelUsageLedger.sessionRecordsFlow(conversationId) }
        .collectAsState(initial = emptyList())
    val pricing by remember { SettingsDataStore.usagePricingFlow() }.collectAsState(initial = UsagePricingSettings())
    val total = remember(records, pricing) {
        UsageReports.summarize(records, UsagePriceResolver(pricing)::cost)
    }
    if (total.requests == 0) return
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text(
        text = stringResource(R.string.context_session_usage_title),
        color = color,
        style = MiuixTheme.textStyles.subtitle,
    )
    Text(
        text = stringResource(
            R.string.context_session_usage,
            total.requests,
            compactTokens(total.promptTokens),
            total.cachedRatio?.let(::formatPercent) ?: "-",
            compactTokens(total.outputTokens),
        ),
        color = color,
        style = MiuixTheme.textStyles.body2,
    )
    Text(
        text = stringResource(
            R.string.context_session_cost,
            total.costUsd?.let(::formatUsd) ?: stringResource(R.string.usage_unpriced_short),
        ),
        color = color,
        style = MiuixTheme.textStyles.body2,
    )
}
