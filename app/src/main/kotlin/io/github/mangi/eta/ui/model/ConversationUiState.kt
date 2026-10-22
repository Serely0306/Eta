package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val updatedAtMillis: Long = 0L,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
    val characterName: String? = null,
    /** 厂商助手入口归档的会话在侧栏单独归组；null 表示普通会话。 */
    val entrySource: ConversationEntrySource? = null,
    /** 会话里派出过的子 Agent，侧栏展开该会话时列出，可点开查看过程。 */
    val subagents: List<ConversationSubagentUi> = emptyList(),
    val characterAvatarPath: String? = null,
)

/** 侧栏里的一个子 Agent：所属 run 与 run_subagents 调用，以及它的任务与状态。 */
@Immutable
data class ConversationSubagentUi(
    val runId: String,
    val toolCallId: String,
    val progress: SubagentProgressUi,
)

@Immutable
enum class ConversationEntrySource {
    XiaoAi,
    Breeno,
    /** 电脑端经设备端 MCP 服务委托的任务。 */
    Computer,
    /** 定时或通知触发的自动任务。 */
    Automation,
}

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}
