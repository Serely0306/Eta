package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.BackgroundNoticeText
import io.github.mangi.eta.data.db.ConversationSubagentEntity
import io.github.mangi.eta.ui.model.SubagentProgressUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 当前聊天所属会话；后台任务通知据此打开子 Agent 的过程窗口（AG-27）。 */
internal val LocalChatConversationId = staticCompositionLocalOf<String?> { null }

/** 通知里的一行：子 Agent 回合结束或后台命令结束。 */
internal data class BackgroundNoticeEntry(
    val title: String,
    val summary: String,
    val agentId: String?,
    val isCommand: Boolean,
    val status: ToolActivityStatusUi,
) {
    companion object {
        private val SUBAGENT_LINE = Regex("^(.+?) (完成|失败|已中断)：(.*)$")
        private val AGENT_IN_LABEL = Regex("（(sub_\\d+)）$")
        private val BARE_AGENT = Regex("^sub_\\d+$")

        /** 解析管理器写的显示行；旧记录里的长原文也按同一规则压成一句摘要。 */
        fun parse(detail: String): List<BackgroundNoticeEntry> = detail.lines().filter(String::isNotBlank).map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith(COMMAND_PREFIX)) {
                return@map BackgroundNoticeEntry(
                    title = trimmed.removePrefix(COMMAND_PREFIX),
                    summary = "",
                    agentId = null,
                    isCommand = true,
                    status = when {
                        trimmed.contains(" 完成") -> ToolActivityStatusUi.Success
                        trimmed.contains(" 已停止") -> ToolActivityStatusUi.Unknown
                        else -> ToolActivityStatusUi.Failed
                    },
                )
            }
            val match = SUBAGENT_LINE.find(trimmed)
                ?: return@map BackgroundNoticeEntry(trimmed, "", null, false, ToolActivityStatusUi.Unknown)
            val (label, verb, text) = match.destructured
            BackgroundNoticeEntry(
                title = "$label · $verb",
                summary = BackgroundNoticeText.summary(text),
                agentId = AGENT_IN_LABEL.find(label)?.groupValues?.get(1) ?: label.takeIf(BARE_AGENT::matches),
                isCommand = false,
                status = when (verb) {
                    "完成" -> ToolActivityStatusUi.Success
                    "失败" -> ToolActivityStatusUi.Failed
                    else -> ToolActivityStatusUi.Unknown
                },
            )
        }

        private const val COMMAND_PREFIX = "后台命令 "
    }
}

/**
 * 子 Agent 与后台命令的完成通知（AG-27）：一张紧凑卡片，每项一行标题、一行摘要，不露出 Markdown 标记；
 * 点子 Agent 那一行打开它的过程窗口，结论在那里按 Markdown 完整显示。
 */
@Composable
internal fun BackgroundTaskNotice(message: SystemNoticeMessageUi, modifier: Modifier = Modifier) {
    val conversationId = LocalChatConversationId.current
    val entries = remember(message.detail) { BackgroundNoticeEntry.parse(message.detail.orEmpty()) }
    var opened by remember(message.id) { mutableStateOf<BackgroundNoticeEntry?>(null) }
    var show by remember(message.id) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.system_notice_subagent_notified),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        entries.forEach { entry ->
            val openable = entry.agentId != null && conversationId != null
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .clickable(enabled = openable) {
                        opened = entry
                        show = true
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (entry.isCommand) Icons.Rounded.Terminal else Icons.Rounded.Layers,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = when (entry.status) {
                        ToolActivityStatusUi.Failed -> MiuixTheme.colorScheme.error
                        ToolActivityStatusUi.Success -> MiuixTheme.colorScheme.primary
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.summary.isNotBlank()) {
                        Text(
                            text = entry.summary,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (openable) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
    val entry = opened
    val agentId = entry?.agentId
    if (entry != null && agentId != null && conversationId != null) {
        SubagentTranscriptDialog(
            runId = "",
            toolCallId = agentId,
            progress = SubagentProgressUi(index = 0, total = 1, status = entry.status, task = entry.title, conclusion = entry.summary),
            agentRowId = ConversationSubagentEntity.rowId(conversationId, agentId),
            show = show,
            onDismissRequest = { show = false },
            onDismissFinished = { opened = null },
        )
    }
}
