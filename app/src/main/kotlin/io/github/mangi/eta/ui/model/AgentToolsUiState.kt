package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentToolsUiState(
    val groups: List<ToolGroupUi>,
    /** 用户单独关闭的工具名（未展开依赖）。 */
    val disabledTools: Set<String> = emptySet(),
)

@Immutable
data class ToolGroupUi(
    val id: String,
    val title: String,
    val tools: List<ToolItemUi>,
)

@Immutable
data class ToolItemUi(
    val id: String,
    val title: String,
    val summary: String,
)
