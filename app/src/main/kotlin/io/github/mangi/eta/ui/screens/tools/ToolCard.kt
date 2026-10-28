package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import io.github.mangi.eta.config.Prefs
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolToggles
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.ui.components.EtaFeatureCard
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.actualToolName
import io.github.mangi.eta.ui.model.toolCardAction
import io.github.mangi.eta.ui.model.toolCardRequirement
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DISABLED_CARD_ALPHA = 0.6f

@Composable
internal fun ToolCard(
    tool: ToolItemUi,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
    disabledTools: Set<String> = emptySet(),
    titleOf: (String) -> String? = { null },
) {
    var showDescription by remember(tool.id) { mutableStateOf(false) }
    val description = if (!rootGranted && tool.id == "terminal") {
        stringResource(R.string.capability_terminal_ordinary_summary)
    } else {
        tool.summary
    }
    val toolName = actualToolName(tool.id)
    // 浏览器的说明卡共享 browser_use，只在主卡上提供开关。
    val toggleable = toolName == tool.id
    val blockingDependency = AgentToolToggles.dependencyOf(toolName)?.takeIf { it in disabledTools }
    val userDisabled = toolName in disabledTools
    val toggleText = when {
        blockingDependency != null -> stringResource(
            R.string.tools_disabled_by_dependency,
            titleOf(blockingDependency) ?: blockingDependency,
        )
        userDisabled && toggleable -> stringResource(R.string.tools_disabled_by_user)
        userDisabled -> stringResource(R.string.tools_disabled_with_browser)
        else -> null
    }
    val toggleLabel = stringResource(R.string.tools_toggle_named, tool.title)
    val requirementText = listOfNotNull(
        toggleText,
        toolRequirementText(tool.id, rootGranted, capabilities),
    ).joinToString("\n").ifBlank { null }
    val action = toolCardAction(tool.id, capabilities)
    val actionText = when (action) {
        AgentToolsAction.OpenWebToolsSettings -> stringResource(R.string.web_tools_configure_action)
        AgentToolsAction.OpenBrowser -> stringResource(R.string.action_open_browser)
        AgentToolsAction.OpenPermissions -> stringResource(R.string.tools_manage_permissions)
        AgentToolsAction.OpenEnhancements -> stringResource(R.string.tools_view_enhancements)
        else -> stringResource(R.string.ui_view_description)
    }
    EtaFeatureCard(
        title = tool.title,
        summary = description,
        modifier = modifier.alpha(if (toggleText != null) DISABLED_CARD_ALPHA else 1f),
        onClick = { if (action != null) onAction(action) else showDescription = true },
        icon = { EtaPreferenceIcon(icon = iconForTool(tool.id), tint = EtaPreferenceColors.Green) },
        action = if (action != null) {
            {
                IconButton(onClick = { showDescription = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.ui_description_named, tool.title),
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        } else null,
    ) {
        requirementText?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
        // 子 Agent 派出策略三档（AG-19）：开关关闭为“关闭”，开启后选“自动”或“仅在我要求时”。
        if (tool.id == SUBAGENT_CARD && toggleText == null) {
            Spacer(modifier = Modifier.height(10.dp))
            SubagentStrategySelector()
            Spacer(modifier = Modifier.height(6.dp))
            SubagentAutoWakeSelector()
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = actionText,
                style = MiuixTheme.textStyles.footnote1,
                color = if (action != null) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
            if (toggleable) {
                Spacer(modifier = Modifier.width(8.dp))
                EtaSwitch(
                    checked = toggleText == null,
                    onCheckedChange = { enabled -> onAction(AgentToolsAction.SetToolEnabled(tool.id, enabled)) },
                    enabled = blockingDependency == null,
                    modifier = Modifier.semantics {
                        contentDescription = toggleLabel
                    },
                )
            }
        }
    }
    if (showDescription) {
        ItemDescriptionDialog(
            title = tool.title,
            description = listOfNotNull(description, requirementText).joinToString("\n\n"),
            onDismiss = { showDescription = false },
        )
    }
}

private const val SUBAGENT_CARD = "spawn_agent"

@Composable
private fun SubagentStrategySelector() {
    LocalChoiceRow(
        key = Prefs.Keys.AGENT_SUBAGENT_ON_REQUEST,
        label = R.string.subagent_strategy_label,
        options = listOf(false to R.string.subagent_strategy_auto, true to R.string.subagent_strategy_on_request),
    )
}

/** 主对话已结束时，子 Agent 完成后是否自动开一轮让主 Agent 处理结果（AG-27）。 */
@Composable
private fun SubagentAutoWakeSelector() {
    LocalChoiceRow(
        key = Prefs.Keys.AGENT_SUBAGENT_AUTO_WAKE,
        label = R.string.subagent_auto_wake_label,
        options = listOf(true to R.string.subagent_auto_wake_on, false to R.string.subagent_auto_wake_off),
    )
}

@Composable
private fun LocalChoiceRow(key: String, label: Int, options: List<Pair<Boolean, Int>>) {
    var current by remember { mutableStateOf(Prefs.isEnabled(key)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(label),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
        options.forEach { (value, optionLabel) ->
            val selected = current == value
            Text(
                text = stringResource(optionLabel),
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) {
                        Prefs.setLocalAgentEnabled(key, value)
                        current = value
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun toolRequirementText(id: String, rootGranted: Boolean, capabilities: AgentToolCapabilities): String? {
    val requirement = toolCardRequirement(id)
    val unavailableCode = capabilities.unavailableCode(actualToolName(id))
    return when {
        !rootGranted && requirement.rootRequirement == RootRequirement.REQUIRED -> stringResource(R.string.capability_root_required)
        !capabilities.accessibilityAvailable && requirement.accessibility -> stringResource(R.string.capability_accessibility_required)
        unavailableCode == "NOTIFICATION_ACCESS_REQUIRED" -> stringResource(R.string.capability_notification_access_required)
        unavailableCode == "APP_USAGE_ACCESS_REQUIRED" -> stringResource(R.string.capability_usage_access_required)
        unavailableCode == "LOCATION_PERMISSION_REQUIRED" -> stringResource(R.string.capability_location_access_required)
        requirement.colorOs -> stringResource(R.string.capability_coloros_required)
        !rootGranted && requirement.rootRequirement == RootRequirement.PARTIAL -> stringResource(R.string.capability_root_partial)
        else -> null
    }
}
