package io.github.mangi.eta.ui.preview

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.ConversationPanePanel
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSubagentUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.SubagentProgressUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi

/** 侧栏预览：第一个会话展开了它派出的 3 个子 Agent（完成、运行中、失败各一）。 */
@Composable
internal fun ConversationPanePreview() {
    val now = System.currentTimeMillis()
    fun subagent(index: Int, status: ToolActivityStatusUi, task: String) =
        ConversationSubagentUi("run-1", "call_s", SubagentProgressUi(index, 3, status, task))
    val state = ConversationPaneUiState(
        conversations = listOf(
            ConversationSummaryUi(
                id = "c1", title = "清理手机存储", preview = "", timeLabel = "10:42", updatedAtMillis = now,
                mode = ConversationModeUi.Chat, isActiveRun = true,
                subagents = listOf(
                    subagent(0, ToolActivityStatusUi.Success, "统计下载目录里超过 500 MB 的文件"),
                    subagent(1, ToolActivityStatusUi.Running, "查系统自带的清理入口"),
                    subagent(2, ToolActivityStatusUi.Failed, "读取相册缩略图缓存大小"),
                ),
            ),
            ConversationSummaryUi(
                id = "c2", title = "整理本周的会议纪要", preview = "", timeLabel = "09:15", updatedAtMillis = now - 3_600_000,
                mode = ConversationModeUi.Chat,
                subagents = listOf(
                    ConversationSubagentUi("run-0", "call_m", SubagentProgressUi(0, 2, ToolActivityStatusUi.Success, "汇总周一的会议")),
                    ConversationSubagentUi("run-0", "call_m", SubagentProgressUi(1, 2, ToolActivityStatusUi.Success, "汇总周三的会议")),
                ),
            ),
            ConversationSummaryUi(
                id = "c3", title = "明天的天气", preview = "", timeLabel = "08:01", updatedAtMillis = now - 7_200_000,
                mode = ConversationModeUi.Chat,
            ),
        ),
        selectedConversationId = "c1",
        searchQuery = "",
    )
    ConversationPanePanel(
        state = state,
        width = 360.dp,
        listState = rememberLazyListState(),
        onSearchChange = {},
        onConversationSelected = {},
        onConversationRename = {},
        onConversationExport = {},
        onConversationDelete = {},
        onOpenSettings = {},
        onOpenModelProviders = {},
        onOpenTools = {},
        onOpenSkills = {},
        onOpenCharacters = {},
        onOpenPermissions = {},
        initialExpandedSubagents = setOf("c1"),
    )
}
