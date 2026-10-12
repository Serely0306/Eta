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
)

@Immutable
enum class ConversationEntrySource {
    XiaoAi,
    Breeno,
}

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}
