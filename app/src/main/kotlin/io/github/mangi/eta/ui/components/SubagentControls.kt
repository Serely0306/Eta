package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
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

/** 执行用户对某个会话里子 Agent 的操作；由应用根部提供，侧栏和聊天页的过程窗口共用。 */
internal val LocalSubagentActions = staticCompositionLocalOf<(String, SubagentPanelAction) -> Unit> { { _, _ -> } }

/** 当前聊天会话的子 Agent（最新状态），主对话里的通知卡片据此打开过程窗口时带上实时状态。 */
internal val LocalConversationSubagents = staticCompositionLocalOf<List<ConversationSubagentUi>> { emptyList() }

/** 过程窗口底部操作的对象：哪个会话的哪个子 Agent，以及它现在是否在运行、是否已关闭。 */
internal data class SubagentControlsTarget(
    val conversationId: String,
    val agentId: String,
    val running: Boolean,
    val closed: Boolean,
)

/** 会话级子 Agent 才有操作；旧版 run_subagents 的子 Agent 没有 agentId，返回 null。 */
internal fun ConversationSubagentUi.controlsTarget(): SubagentControlsTarget? =
    agentId.takeIf { it.isNotBlank() && conversationId.isNotBlank() }?.let {
        SubagentControlsTarget(
            conversationId = conversationId,
            agentId = it,
            running = progress.status == ToolActivityStatusUi.Running,
            closed = progress.closed,
        )
    }

/**
 * 子 Agent 过程窗口底部的操作（AG-27，用户 2026-10-28 从输入栏上方挪到这里）：
 * 发消息（运行中在它下一次请求前插入，空闲时开新回合）、停止当前回合、关闭（关闭只留给用户）。
 */
@Composable
internal fun SubagentControlsBar(target: SubagentControlsTarget, modifier: Modifier = Modifier) {
    val onAction = LocalSubagentActions.current
    var composing by remember(target.agentId) { mutableStateOf(false) }
    var draft by remember(target.agentId) { mutableStateOf("") }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!target.closed) PanelLink(stringResource(R.string.background_tasks_message)) { composing = !composing }
            if (target.running) {
                PanelLink(stringResource(R.string.background_tasks_stop)) {
                    onAction(target.conversationId, SubagentPanelAction.Stop(target.agentId))
                }
            }
            if (!target.closed) {
                PanelLink(stringResource(R.string.background_tasks_close)) {
                    onAction(target.conversationId, SubagentPanelAction.Close(target.agentId))
                }
            }
        }
        if (composing && !target.closed) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                label = stringResource(R.string.background_tasks_message_hint),
                modifier = Modifier.fillMaxWidth(),
            )
            PanelLink(stringResource(R.string.background_tasks_send), enabled = draft.isNotBlank()) {
                onAction(target.conversationId, SubagentPanelAction.Send(target.agentId, draft.trim()))
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
