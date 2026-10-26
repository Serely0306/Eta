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
    val runStatus: String? = null,
    val characterName: String? = null,
    /** 厂商助手入口归档的会话在侧栏单独归组；null 表示普通会话。 */
    val entrySource: ConversationEntrySource? = null,
    /** 会话里派出过的子 Agent，侧栏展开该会话时列出，可点开查看过程。 */
    val subagents: List<ConversationSubagentUi> = emptyList(),
    val characterAvatarPath: String? = null,
    /** 会话尚未加载、但存储里有 run_subagents 调用：侧栏照常显示入口，展开时再加载。 */
    val hasUnloadedSubagents: Boolean = false,
)

/**
 * 侧栏里的一个子 Agent。旧版 `run_subagents` 的按所属 run 与调用定位；AG-19 起的会话级子 Agent
 * 带 [conversationId] 与 [agentId]（[toolCallId] 同为 agentId），过程从库里的上下文读取。
 */
@Immutable
data class ConversationSubagentUi(
    val runId: String,
    val toolCallId: String,
    val progress: SubagentProgressUi,
    val conversationId: String = "",
    val agentId: String = "",
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
