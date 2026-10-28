package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.ConversationSubagentEntity
import io.github.mangi.eta.ui.model.ConversationSubagentUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 用户对子 Agent 的操作（AG-27）：直接发消息（标注为用户）、停止当前回合、关闭。 */
internal sealed interface SubagentPanelAction {
    val agentId: String
    data class Send(override val agentId: String, val text: String) : SubagentPanelAction
    data class Stop(override val agentId: String) : SubagentPanelAction
    data class Close(override val agentId: String) : SubagentPanelAction
}

/** 当前会话的会话级子 Agent 与操作入口，由聊天页提供给输入栏上方的后台任务面板。 */
internal data class BackgroundTasksUi(
    val subagents: List<ConversationSubagentUi> = emptyList(),
    val onAction: (SubagentPanelAction) -> Unit = {},
)

internal val LocalBackgroundTasks = staticCompositionLocalOf { BackgroundTasksUi() }

/**
 * 输入栏上方的后台任务入口（AG-27）：有子 Agent 运行时显示“n 个子 Agent 运行中 · 查看”；
 * 点开列出本会话的子 Agent，可以看过程、直接给它发消息、停止或关闭（关闭只留给用户）。
 */
@Composable
internal fun BackgroundTaskPanel(modifier: Modifier = Modifier) {
    val tasks = LocalBackgroundTasks.current
    val conversationId = LocalChatConversationId.current
    val agents = tasks.subagents.filter { it.agentId.isNotBlank() }
    val running = agents.count { it.progress.status == ToolActivityStatusUi.Running }
    var showPanel by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<ConversationSubagentUi?>(null) }
    var showProcess by remember { mutableStateOf(false) }
    if (running > 0 || showPanel) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .clickable { showPanel = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(StatusRunning))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.background_tasks_running, running),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.background_tasks_view),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
    EtaWindowDialog(
        show = showPanel,
        title = stringResource(R.string.background_tasks_title),
        onDismissRequest = { showPanel = false },
    ) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(agents, key = { it.agentId }) { agent ->
                SubagentPanelRow(
                    agent = agent,
                    onAction = tasks.onAction,
                    onOpenProcess = {
                        opened = agent
                        showProcess = true
                    },
                )
            }
        }
    }
    val agent = opened
    if (agent != null && conversationId != null) {
        SubagentTranscriptDialog(
            runId = "",
            toolCallId = agent.agentId,
            progress = agent.progress,
            agentRowId = ConversationSubagentEntity.rowId(conversationId, agent.agentId),
            show = showProcess,
            onDismissRequest = { showProcess = false },
            onDismissFinished = { opened = null },
        )
    }
}

@Composable
private fun SubagentPanelRow(
    agent: ConversationSubagentUi,
    onAction: (SubagentPanelAction) -> Unit,
    onOpenProcess: () -> Unit,
) {
    val progress = agent.progress
    val running = progress.status == ToolActivityStatusUi.Running
    var composing by remember(agent.agentId) { mutableStateOf(false) }
    var draft by remember(agent.agentId) { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = progress.task.ifBlank { agent.agentId },
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(
                agent.agentId,
                if (progress.interrupted) stringResource(R.string.subagent_tag_interrupted) else stringResource(progress.status.labelRes()),
                stringResource(R.string.subagent_tag_closed).takeIf { progress.closed },
                stringResource(R.string.subagent_tag_turn, progress.turn, progress.rounds).takeIf { progress.turn > 1 },
            ).joinToString(" · "),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelLink(stringResource(R.string.background_tasks_process), onOpenProcess)
            if (!progress.closed) PanelLink(stringResource(R.string.background_tasks_message)) { composing = !composing }
            if (running) PanelLink(stringResource(R.string.background_tasks_stop)) { onAction(SubagentPanelAction.Stop(agent.agentId)) }
            if (!progress.closed) PanelLink(stringResource(R.string.background_tasks_close)) { onAction(SubagentPanelAction.Close(agent.agentId)) }
        }
        if (composing) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                label = stringResource(R.string.background_tasks_message_hint),
                modifier = Modifier.fillMaxWidth(),
            )
            PanelLink(stringResource(R.string.background_tasks_send), enabled = draft.isNotBlank()) {
                onAction(SubagentPanelAction.Send(agent.agentId, draft.trim()))
                draft = ""
                composing = false
            }
        }
    }
}

@Composable
private fun PanelLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.Medium,
        color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledOnSurface,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
    )
}
