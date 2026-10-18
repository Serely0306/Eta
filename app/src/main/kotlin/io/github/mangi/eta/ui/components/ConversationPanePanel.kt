package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.ConversationEntrySource
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSubagentUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup

private object ConversationPanelMetrics {
    val PaneHorizontalPadding = 16.dp
    val TopInset = 6.dp
    val AfterActionBar = 8.dp
    val BottomInset = 2.dp
    val ActionIconSize = 20.dp
    val SectionTopPadding = 8.dp
    val SectionBottomPadding = 10.dp
    val SectionIconSize = 14.dp
    val SectionIconGap = 8.dp
    val SectionCountGap = 12.dp
    val RowMinHeight = 48.dp
    val RowGap = 4.dp
    val RowCornerRadius = 12.dp
    val RowHorizontalPadding = 12.dp
    val RowVerticalPadding = 12.dp
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 10.dp
    val SubagentIndent = 16.dp
    val SubagentToggleSize = 32.dp
    val EmptyVerticalPadding = 28.dp
    val DockTopGap = 2.dp
    val DockEntryCornerRadius = 12.dp
    val DockEntryIconSize = 20.dp
}

/**
 * 侧栏与外层推移容器共用的背景色。
 *
 * 深色下聊天区保持 surface（纯黑/近黑），侧栏沿用同一颜色会在展开后与聊天区融为一体；
 * 抬高一档到 surfaceContainer，保证两侧始终可分。浅色维持 surface，避免改变现有观感。
 */
@Composable
internal fun conversationPaneContainerColor(): Color {
    val colors = MiuixTheme.colorScheme
    return if (colors.background.luminance() > 0.5f) colors.surface else colors.surfaceContainer
}

@Composable
internal fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    /** 初始展开子 Agent 的会话；界面预览用。 */
    initialExpandedSubagents: Set<String> = emptySet(),
) {
    // state.conversations 已由 AgentAppState 按标题、预览与消息内容过滤。
    val query = state.searchQuery.trim()
    val groups = remember(state.conversations) { state.conversations.groupForDrawer() }
    // 厂商入口分组默认收起，避免每次唤醒新建的会话挤占普通对话；搜索或选中其中的会话时自动展开。
    var expandedEntrySources by remember { mutableStateOf(emptySet<ConversationEntrySource>()) }
    // 有子 Agent 的会话可在侧栏展开，列出它派出过的子 Agent；点开查看过程。
    var expandedSubagents by remember { mutableStateOf(initialExpandedSubagents) }
    var openedSubagent by remember { mutableStateOf<ConversationSubagentUi?>(null) }
    var showSubagent by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight(),
        color = conversationPaneContainerColor(),
        contentColor = MiuixTheme.colorScheme.onSurface,
    ) {
        // 搜索与工具条占据独立布局空间，列表只在中间视口内滚动和回弹。
        Column(modifier = Modifier.fillMaxSize()) {
            PaneFixedRegion {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                ) {
                    Spacer(Modifier.height(ConversationPanelMetrics.TopInset))
                    PaneActionBar(query = state.searchQuery, onSearchChange = onSearchChange)
                    Spacer(Modifier.height(ConversationPanelMetrics.AfterActionBar))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    )
                    .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding)
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(ConversationPanelMetrics.RowGap),
                overscrollEffect = null,
            ) {
                if (state.conversations.isEmpty()) {
                    item {
                        EmptyConversations(isSearching = query.isNotBlank())
                    }
                } else {
                    groups.forEach { group ->
                        val entrySource = (group.section as? ConversationDrawerSection.Entry)?.source
                        val expanded = entrySource == null || query.isNotBlank() ||
                            entrySource in expandedEntrySources ||
                            group.items.any { it.id == state.selectedConversationId }
                        item(key = "section-${group.section}") {
                            ConversationSectionHeader(
                                group = group,
                                expanded = expanded,
                                onToggle = entrySource?.let { source ->
                                    {
                                        expandedEntrySources = if (source in expandedEntrySources) {
                                            expandedEntrySources - source
                                        } else {
                                            expandedEntrySources + source
                                        }
                                    }
                                },
                            )
                        }
                        items(
                            items = if (expanded) group.items else emptyList(),
                            key = { it.id },
                        ) { conversation ->
                            val subagentsOpen = conversation.id in expandedSubagents
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                ConversationTextRow(
                                    conversation = conversation,
                                    selected = conversation.id == state.selectedConversationId,
                                    subagentsExpanded = subagentsOpen,
                                    onToggleSubagents = {
                                        expandedSubagents = if (subagentsOpen) {
                                            expandedSubagents - conversation.id
                                        } else {
                                            expandedSubagents + conversation.id
                                        }
                                    },
                                    onClick = { onConversationSelected(conversation.id) },
                                    onRename = { onConversationRename(conversation) },
                                    onExport = { onConversationExport(conversation) },
                                    onDelete = { onConversationDelete(conversation) },
                                )
                                if (subagentsOpen) {
                                    conversation.subagents.forEach { subagent ->
                                        SubagentPaneRow(subagent = subagent, onClick = {
                                            openedSubagent = subagent
                                            showSubagent = true
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            PaneFixedRegion {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                ) {
                    Spacer(Modifier.height(ConversationPanelMetrics.DockTopGap))
                    PaneDock(
                        onOpenSettings = onOpenSettings,
                        onOpenModelProviders = onOpenModelProviders,
                        onOpenTools = onOpenTools,
                        onOpenSkills = onOpenSkills,
                        onOpenCharacters = onOpenCharacters,
                        onOpenPermissions = onOpenPermissions,
                    )
                    Spacer(Modifier.height(ConversationPanelMetrics.BottomInset))
                }
            }
        }
    }
    openedSubagent?.let { subagent ->
        SubagentTranscriptDialog(
            runId = subagent.runId,
            toolCallId = subagent.toolCallId,
            progress = subagent.progress,
            show = showSubagent,
            onDismissRequest = { showSubagent = false },
            onDismissFinished = { openedSubagent = null },
        )
    }
}

/** 侧栏里会话下的一个子 Agent：状态点、序号与状态、任务；运行中的还没有过程可看，不可点。 */
@Composable
private fun SubagentPaneRow(subagent: ConversationSubagentUi, onClick: () -> Unit) {
    val progress = subagent.progress
    val running = progress.status == ToolActivityStatusUi.Running
    val failed = progress.status == ToolActivityStatusUi.Failed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ConversationPanelMetrics.SubagentIndent)
            .clip(RoundedCornerShape(ConversationPanelMetrics.RowCornerRadius))
            .then(if (running) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = ConversationPanelMetrics.RowHorizontalPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ConversationPanelMetrics.ActiveDotSize)
                .clip(CircleShape)
                .background(
                    when {
                        running -> StatusRunning
                        failed -> StatusError
                        else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                    },
                ),
        )
        Spacer(modifier = Modifier.width(ConversationPanelMetrics.SectionIconGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.subagent_title, progress.index + 1, progress.total) + " · " +
                    stringResource(progress.status.labelRes()),
                style = MiuixTheme.textStyles.footnote1,
                color = if (failed) StatusError else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
            if (progress.task.isNotBlank()) {
                Text(
                    text = progress.task,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PaneFixedRegion(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(conversationPaneContainerColor()),
    ) {
        content()
    }
}

@Composable
private fun PaneActionBar(
    query: String,
    onSearchChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBar(
            modifier = Modifier.weight(1f),
            insideMargin = DpSize.Zero,
            expanded = false,
            onExpandedChange = {},
            inputField = {
                InputField(
                    query = query,
                    onQueryChange = onSearchChange,
                    onSearch = onSearchChange,
                    expanded = false,
                    onExpandedChange = {},
                    label = stringResource(R.string.conversation_search_hint),
                    // 深色下侧栏已抬高到 surfaceContainer，搜索框需再高一档才能显出轮廓；
                    // 浅色的 surfaceContainerHigh 与 surfaceContainerHighest 相同，观感不变。
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                )
            },
            content = {},
        )
    }
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
) {
    val label = group.localizedLabel()
    val toggleDescription = stringResource(R.string.conversation_section_toggle, label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onToggle != null) {
                    Modifier
                        .clip(RoundedCornerShape(ConversationPanelMetrics.RowCornerRadius))
                        .clickable(onClickLabel = toggleDescription, onClick = onToggle)
                } else Modifier,
            )
            .padding(
                top = ConversationPanelMetrics.SectionTopPadding,
                bottom = ConversationPanelMetrics.SectionBottomPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (onToggle != null) Icons.Rounded.Mic else Icons.Rounded.Schedule,
            contentDescription = null,
            modifier = Modifier.size(ConversationPanelMetrics.SectionIconSize),
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
        Spacer(modifier = Modifier.width(ConversationPanelMetrics.SectionIconGap))
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(ConversationPanelMetrics.SectionCountGap))
        Text(
            text = group.items.size.toString(),
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
        )
        if (onToggle != null) {
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(ConversationPanelMetrics.SectionIconSize),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTextRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    subagentsExpanded: Boolean,
    onToggleSubagents: () -> Unit,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ConversationPanelMetrics.RowMinHeight)
                .clip(RoundedCornerShape(ConversationPanelMetrics.RowCornerRadius))
                .background(
                    if (selected) {
                        // 与侧栏背景保持一档亮度差；浅色下与 surfaceContainerHigh 相同。
                        MiuixTheme.colorScheme.surfaceContainerHighest
                    } else {
                        Color.Transparent
                    },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        showActionMenu = true
                    },
                )
                .padding(
                    horizontal = ConversationPanelMetrics.RowHorizontalPadding,
                    vertical = ConversationPanelMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
            val title = conversation.title.ifBlank { conversation.preview }
            Text(
                text = title,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 标题与角色名相同（如未改名的角色会话）时不再重复第二行。
            conversation.characterName?.takeIf { it != title }?.let { name ->
                Text(name, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            }
            if (conversation.isActiveRun) {
                Box(
                    modifier = Modifier
                        .padding(start = ConversationPanelMetrics.ActiveDotGap)
                        .size(ConversationPanelMetrics.ActiveDotSize)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                )
            }
            if (conversation.subagents.isNotEmpty()) {
                // 展开按钮单独接收点击，不切换会话。
                Row(
                    modifier = Modifier
                        .padding(start = ConversationPanelMetrics.ActiveDotGap)
                        .heightIn(min = ConversationPanelMetrics.SubagentToggleSize)
                        .clip(RoundedCornerShape(ConversationPanelMetrics.RowCornerRadius))
                        .clickable(
                            onClickLabel = stringResource(R.string.conversation_subagents_toggle),
                            onClick = onToggleSubagents,
                        )
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(ConversationPanelMetrics.SectionIconSize),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                    Text(
                        text = conversation.subagents.size.toString(),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                    Icon(
                        imageVector = if (subagentsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(R.string.conversation_subagents_toggle),
                        modifier = Modifier.size(ConversationPanelMetrics.SectionIconSize),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }

        WindowListPopup(
            show = showActionMenu,
            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
            alignment = PopupPositionProvider.Align.BottomEnd,
            onDismissRequest = { showActionMenu = false },
        ) {
            val renameText = stringResource(R.string.action_rename)
            val exportText = stringResource(R.string.action_export)
            val deleteText = stringResource(R.string.action_delete)
            val renameItem = remember(renameText) {
                DropdownItem(
                    text = renameText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val exportItem = remember(exportText) {
                DropdownItem(
                    text = exportText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val deleteItem = remember(deleteText) {
                DropdownItem(
                    text = deleteText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                            tint = MiuixTheme.colorScheme.error,
                        )
                    },
                )
            }
            val deleteColors = DropdownDefaults.dropdownColors(
                contentColor = MiuixTheme.colorScheme.error,
                selectedContentColor = MiuixTheme.colorScheme.error,
                selectedIndicatorColor = MiuixTheme.colorScheme.error,
            )
            ListPopupColumn {
                DropdownImpl(
                    item = renameItem,
                    optionSize = 3,
                    isSelected = false,
                    index = 0,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onRename()
                    },
                )
                DropdownImpl(
                    item = exportItem,
                    optionSize = 3,
                    isSelected = false,
                    index = 1,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onExport()
                    },
                )
                DropdownImpl(
                    item = deleteItem,
                    optionSize = 3,
                    isSelected = false,
                    index = 2,
                    dropdownColors = deleteColors,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean) {
    Text(
        text = stringResource(
            if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(
            horizontal = ConversationPanelMetrics.RowHorizontalPadding,
            vertical = ConversationPanelMetrics.EmptyVerticalPadding,
        ),
    )
}

@Composable
private fun PaneDock(
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DockEntry(
            icon = Icons.Rounded.Settings,
            label = "设置",
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Rounded.Cloud,
            label = "模型",
            onClick = onOpenModelProviders,
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Rounded.Dashboard,
            label = "工具",
            onClick = onOpenTools,
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Rounded.ImportContacts,
            label = "Skills",
            onClick = onOpenSkills,
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Rounded.Lock,
            label = "权限",
            onClick = onOpenPermissions,
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Rounded.SportsBar,
            label = "角色",
            onClick = onOpenCharacters,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DockEntry(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(ConversationPanelMetrics.DockEntryCornerRadius))
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(ConversationPanelMetrics.DockEntryIconSize),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
)

private sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data class Entry(val source: ConversationEntrySource) : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    is ConversationDrawerSection.Entry -> when (value.source) {
        ConversationEntrySource.XiaoAi -> stringResource(R.string.conversation_section_xiaoai)
        ConversationEntrySource.Breeno -> stringResource(R.string.conversation_section_breeno)
        ConversationEntrySource.Computer -> stringResource(R.string.conversation_section_computer)
        ConversationEntrySource.Automation -> stringResource(R.string.conversation_section_automation)
    }
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
}

/** 置顶在前，其次是按来源收拢的厂商入口会话，其余普通会话按时间分段。 */
internal fun List<ConversationSummaryUi>.drawerSectionKeys(): List<String> =
    groupForDrawer().map { it.section.toString() }

private fun List<ConversationSummaryUi>.groupForDrawer(): List<ConversationDrawerGroup> {
    if (isEmpty()) return emptyList()
    val pinned = filter { it.isPinned }
    val entries = filter { !it.isPinned && it.entrySource != null }
    val groups = mutableListOf<ConversationDrawerGroup>()
    if (pinned.isNotEmpty()) groups += ConversationDrawerGroup(ConversationDrawerSection.Pinned, pinned)
    ConversationEntrySource.entries.forEach { source ->
        entries.filter { it.entrySource == source }.takeIf { it.isNotEmpty() }?.let { items ->
            groups += ConversationDrawerGroup(ConversationDrawerSection.Entry(source), items)
        }
    }
    for (conversation in this) {
        if (conversation.isPinned || conversation.entrySource != null) continue
        val section = conversation.drawerSection()
        val last = groups.lastOrNull()
        if (last?.section == section) {
            groups[groups.lastIndex] = last.copy(items = last.items + conversation)
        } else {
            groups += ConversationDrawerGroup(section = section, items = listOf(conversation))
        }
    }
    return groups
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isActiveRun || isUpdatedToday(updatedAtMillis) -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}

private fun isUpdatedToday(timestampMillis: Long): Boolean {
    if (timestampMillis <= 0L) return true
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestampMillis }
    return now.get(java.util.Calendar.ERA) == target.get(java.util.Calendar.ERA) &&
        now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
}
