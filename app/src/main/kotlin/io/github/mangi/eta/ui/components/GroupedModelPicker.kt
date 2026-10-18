package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderSetting
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

/** 外观同下拉偏好项：右侧显示当前模型，点击打开 [GroupedModelSelectDialog]。 */
@Composable
internal fun EtaModelSelectPreference(
    title: String,
    providers: List<ProviderSetting>,
    groups: List<AgentModelProviderGroupUi>,
    selectedId: String?,
    emptyOptionLabel: String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val value = modelSelectionSummary(providers, groups, selectedId, emptyOptionLabel)
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
            onSelect = { id ->
                showDialog = false
                onSelect(id)
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * 单选弹窗：顶部是“无”或“跟随默认模型”这类空选项，其下按服务商折叠；
 * 默认只展开当前所选模型所在的服务商。选中后立即回调并关闭。
 */
@Composable
internal fun GroupedModelSelectDialog(
    title: String,
    groups: List<AgentModelProviderGroupUi>,
    selectedId: String?,
    emptyOptionLabel: String,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember {
        mutableStateOf(setOfNotNull(groups.findModel(selectedId)?.providerId))
    }
    EtaWindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ModelDialogListMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                EtaRadioButtonPreference(
                    title = emptyOptionLabel,
                    selected = selectedId == null,
                    onClick = { onSelect(null) },
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
                                selected = model.id == selectedId,
                                onClick = { onSelect(model.id) },
                                modifier = Modifier.padding(start = ModelRowIndent),
                            )
                        }
                    }
                }
            }
            EtaTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

/**
 * 多选列表，内嵌在设置分组里：每个服务商一个可折叠标题，显示已选数量；
 * 有勾选的服务商默认展开。[unavailableIds] 是已勾选但当前不可用的模型，单列在最后，可以取消。
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
                    onCheckedChange = { checked -> onCheckedChange(model.id, checked) },
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
private val ModelRowIndent = 12.dp
