package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelProviderGroupUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页与 MCP 页共用的模型列表：与聊天输入框的模型选择一样按服务商分组、可折叠，
 * 只列出已启用且填写了 API Key 的服务商下的已启用模型。
 */
internal fun modelChoiceGroups(providers: List<ProviderSetting>): List<AgentModelProviderGroupUi> =
    AgentModelPickerProjector.project(providers, selectedProviderId = null, selectedModelId = null).providerGroups

internal fun List<AgentModelProviderGroupUi>.findModel(id: String?): AgentModelOptionUi? =
    id?.let { target -> asSequence().flatMap { it.models.asSequence() }.firstOrNull { it.id == target } }

/** 已保存但当前不在列表中的模型：服务商或模型被停用、API Key 被清空，或已删除。 */
internal fun unavailableModelLabel(providers: List<ProviderSetting>, id: String): String? =
    providers.firstNotNullOfOrNull { provider ->
        provider.models.firstOrNull { it.id == id }?.let { "${provider.name} / ${it.displayName.ifBlank { it.modelId }}" }
    }

/** 设置行摘要：可用时为“服务商 / 模型”；不可用时注明，避免看起来像没有设置。 */
@Composable
internal fun modelSelectionSummary(
    providers: List<ProviderSetting>,
    groups: List<AgentModelProviderGroupUi>,
    id: String?,
    emptyLabel: String,
): String {
    id ?: return emptyLabel
    groups.findModel(id)?.let { return "${it.providerName} / ${it.displayName}" }
    return unavailableModelLabel(providers, id)
        ?.let { stringResource(R.string.model_selection_unavailable, it) }
        ?: stringResource(R.string.model_selection_deleted)
}

/**
 * 选模型时一并选强度。[capabilitiesFor] 给出模型的思考能力，参数为 null 表示空选项
 * （如“跟随默认模型”对应默认模型）；返回 null 时不显示强度。[selected] 为 null 表示跟随，显示为 [followLabel]。
 */
internal class ModelEffortSelection(
    val selected: ReasoningEffort?,
    val followLabel: String,
    val capabilitiesFor: (modelId: String?) -> ModelReasoningCapabilities?,
)

/**
 * 外观同下拉偏好项：右侧显示当前模型（带 [effort] 时附强度），点击打开 [GroupedModelSelectDialog]。
 * 回调的强度在不带 [effort] 时总是 null。
 */
@Composable
internal fun EtaModelSelectPreference(
    title: String,
    providers: List<ProviderSetting>,
    groups: List<AgentModelProviderGroupUi>,
    selectedId: String?,
    emptyOptionLabel: String,
    onSelect: (modelId: String?, effort: ReasoningEffort?) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    effort: ModelEffortSelection? = null,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val modelLabel = modelSelectionSummary(providers, groups, selectedId, emptyOptionLabel)
    val effortLabel = effort?.let { selection ->
        selection.capabilitiesFor(selectedId)?.let { capabilities ->
            selection.selected?.let { reasoningEffortLabel(it, capabilities.defaultEffort) } ?: selection.followLabel
        }
    }
    val value = effortLabel?.let { "$modelLabel · $it" } ?: modelLabel
    EtaArrowPreference(
        title = title,
        summary = summary,
        modifier = modifier,
        startAction = startAction,
        enabled = enabled,
        onClick = { showDialog = true },
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = if (enabled) {
                    MiuixTheme.colorScheme.onSurfaceVariantActions
                } else {
                    MiuixTheme.colorScheme.disabledOnSurface
                },
                textAlign = TextAlign.End,
            )
        },
    )
    if (showDialog && enabled) {
        GroupedModelSelectDialog(
            title = title,
            groups = groups,
            selectedId = selectedId,
            emptyOptionLabel = emptyOptionLabel,
            effort = effort,
            onSelect = { id, chosenEffort ->
                showDialog = false
                onSelect(id, chosenEffort)
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * 单选弹窗：顶部是“无”或“跟随默认模型”这类空选项，其下按服务商折叠；
 * 默认只展开当前所选模型所在的服务商。不带 [effort] 时选中即回调并关闭；
 * 带 [effort] 时在列表下方选强度，按确定后回调。
 */
@Composable
internal fun GroupedModelSelectDialog(
    title: String,
    groups: List<AgentModelProviderGroupUi>,
    selectedId: String?,
    emptyOptionLabel: String,
    onSelect: (modelId: String?, effort: ReasoningEffort?) -> Unit,
    onDismiss: () -> Unit,
    effort: ModelEffortSelection? = null,
) {
    var expanded by remember {
        mutableStateOf(setOfNotNull(groups.findModel(selectedId)?.providerId))
    }
    var pendingId by remember { mutableStateOf(selectedId) }
    var pendingEffort by remember { mutableStateOf(effort?.selected) }
    fun pick(id: String?) {
        if (effort == null) {
            onSelect(id, null)
        } else if (id != pendingId) {
            // 换模型后强度回到“跟随”，避免沿用上一个模型才有的档位。
            pendingId = id
            pendingEffort = null
        }
    }
    val capabilities = effort?.capabilitiesFor?.invoke(pendingId)
    EtaWindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (effort == null) ModelDialogListMaxHeight else ModelDialogListWithEffortMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                EtaRadioButtonPreference(
                    title = emptyOptionLabel,
                    selected = pendingId == null,
                    onClick = { pick(null) },
                )
                groups.forEach { group ->
                    EtaPreferenceDivider(hasLeading = false)
                    val open = group.providerId in expanded
                    ModelGroupHeader(
                        name = group.providerName,
                        summary = pluralStringResource(R.plurals.model_group_count, group.models.size, group.models.size),
                        expanded = open,
                        onToggle = { expanded = if (open) expanded - group.providerId else expanded + group.providerId },
                    )
                    if (open) {
                        group.models.forEach { model ->
                            EtaRadioButtonPreference(
                                title = model.displayName,
                                selected = model.id == pendingId,
                                onClick = { pick(model.id) },
                                modifier = Modifier.padding(start = ModelRowIndent),
                            )
                        }
                    }
                }
            }
            if (effort != null && capabilities != null) {
                EffortChips(
                    title = groups.findModel(pendingId)?.displayName ?: emptyOptionLabel,
                    capabilities = capabilities,
                    selected = pendingEffort,
                    followLabel = effort.followLabel,
                    onSelect = { pendingEffort = it },
                )
            }
            if (effort == null) {
                EtaTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            } else {
                MiuixDialogActions(
                    confirmText = stringResource(android.R.string.ok),
                    onCancel = onDismiss,
                    onConfirm = { onSelect(pendingId, pendingEffort.takeIf { capabilities != null }) },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** 强度选择：第一项为“跟随”，其后是模型可选的档位；只有服务商默认一档时说明原因。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffortChips(
    title: String,
    capabilities: ModelReasoningCapabilities,
    selected: ReasoningEffort?,
    followLabel: String,
    onSelect: (ReasoningEffort?) -> Unit,
) {
    val options = entryEffortOptions(capabilities)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = stringResource(R.string.reasoning_effort_for_model, title),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val on = option == selected
                Text(
                    text = option?.let { reasoningEffortLabel(it, capabilities.defaultEffort) } ?: followLabel,
                    style = MiuixTheme.textStyles.body2,
                    color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surface)
                        .border(0.5.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable(role = Role.RadioButton) { onSelect(option) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        if (capabilities.selectableEfforts == listOf(ReasoningEffort.DEFAULT)) {
            Text(
                text = stringResource(R.string.reasoning_effort_only_default),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * 多选列表，内嵌在设置分组里：每个服务商一个可折叠标题，显示已选数量；
 * 有勾选的服务商默认展开。[unavailable] 是已勾选但当前不可用的模型，单列在最后，可以取消。
 */
@Composable
internal fun ColumnScope.GroupedModelChecklist(
    groups: List<AgentModelProviderGroupUi>,
    checkedIds: List<String>,
    unavailable: List<Pair<String, String>>,
    onCheckedChange: (id: String, checked: Boolean) -> Unit,
) {
    var expanded by remember {
        mutableStateOf(
            groups.filter { group -> group.models.any { it.id in checkedIds } }.map { it.providerId }.toSet()
        )
    }
    groups.forEach { group ->
        EtaPreferenceDivider(hasLeading = false)
        val open = group.providerId in expanded
        val checkedCount = group.models.count { it.id in checkedIds }
        ModelGroupHeader(
            name = group.providerName,
            summary = stringResource(R.string.model_group_checked, checkedCount, group.models.size),
            expanded = open,
            onToggle = { expanded = if (open) expanded - group.providerId else expanded + group.providerId },
        )
        if (open) {
            group.models.forEach { model ->
                EtaCheckboxPreference(
                    title = model.displayName,
                    checked = model.id in checkedIds,
                    onCheckedChange = { onCheckedChange(model.id, it) },
                    checkboxLocation = CheckboxLocation.Start,
                    modifier = Modifier.padding(start = ModelRowIndent),
                )
            }
        }
    }
    unavailable.forEach { (id, label) ->
        EtaPreferenceDivider(hasLeading = false)
        EtaCheckboxPreference(
            title = label,
            summary = stringResource(R.string.model_selection_unavailable_summary),
            checked = true,
            onCheckedChange = { checked -> onCheckedChange(id, checked) },
            checkboxLocation = CheckboxLocation.Start,
        )
    }
}

@Composable
private fun ModelGroupHeader(
    name: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    EtaPreference(
        title = name,
        summary = summary,
        onClick = onToggle,
        onClickLabel = if (expanded) {
            stringResource(R.string.model_collapse_provider, name)
        } else {
            stringResource(R.string.model_expand_provider, name)
        },
        endActions = {
            ExpandChevron(expanded = expanded, contentDescription = null)
        },
    )
}

private val ModelDialogListMaxHeight = 440.dp
private val ModelDialogListWithEffortMaxHeight = 320.dp
private val ModelRowIndent = 12.dp
