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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.BackgroundNoticeText
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import io.github.mangi.eta.data.db.ConversationSubagentEntity
import io.github.mangi.eta.ui.model.SubagentProgressUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ToolCallDetail
import io.github.mangi.eta.ui.model.ToolCallDetails
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
    val jobId: String? = null,
) {
    companion object {
        private val SUBAGENT_LINE = Regex("^(.+?) (完成|失败|已中断)：(.*)$")
        private val AGENT_IN_LABEL = Regex("（(sub_\\d+)）$")
        private val BARE_AGENT = Regex("^sub_\\d+$")

        /** 解析管理器写的显示行；旧记录里的长原文也按同一规则压成一句摘要。 */
        fun parse(detail: String, jobIds: List<String> = emptyList()): List<BackgroundNoticeEntry> {
            var commandIndex = 0
            return detail.lines().filter(String::isNotBlank).map { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith(COMMAND_PREFIX)) {
                    return@map BackgroundNoticeEntry(
                        title = trimmed.removePrefix(COMMAND_PREFIX),
                        summary = "",
                        agentId = null,
                        isCommand = true,
                        jobId = jobIds.getOrNull(commandIndex++),
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
    val entries = remember(message.detail, message.jobIds) {
        BackgroundNoticeEntry.parse(message.detail.orEmpty(), message.jobIds)
    }
    var opened by remember(message.id) { mutableStateOf<BackgroundNoticeEntry?>(null) }
    var show by remember(message.id) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.system_notice_subagent_notified),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        entries.forEach { entry ->
            val openable = entry.jobId != null || entry.agentId != null && conversationId != null
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
    if (entry?.jobId != null) {
        BackgroundCommandDialog(entry.jobId, show, { show = false }, { opened = null })
    }
    val agentId = entry?.agentId
    if (entry != null && agentId != null && conversationId != null) {
        // 通知里的状态是交出那一刻的；窗口里用侧栏同一份最新状态，发消息后能看到它重新运行。
        val live = LocalConversationSubagents.current.firstOrNull { it.agentId == agentId }
        SubagentTranscriptDialog(
            runId = "",
            toolCallId = agentId,
            progress = live?.progress
                ?: SubagentProgressUi(index = 0, total = 1, status = entry.status, task = entry.title, conclusion = entry.summary),
            agentRowId = ConversationSubagentEntity.rowId(conversationId, agentId),
            show = show,
            onDismissRequest = { show = false },
            onDismissFinished = { opened = null },
            controls = live?.controlsTarget() ?: SubagentControlsTarget(conversationId, agentId, running = false, closed = false),
        )
    }
}

@Composable
internal fun BackgroundCommandDialog(
    jobId: String,
    show: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    originalDetail: ToolCallDetail? = null,
) {
    val context = LocalContext.current
    val jobs = remember(context) { TerminalRuntime.backgroundJobs(context) }
    val scope = rememberCoroutineScope()
    var result by remember(jobId) { mutableStateOf<JSONObject?>(null) }
    var expanded by remember(jobId) { mutableStateOf(false) }
    var showOriginal by remember(jobId) { mutableStateOf(false) }
    var actionError by remember(jobId) { mutableStateOf<String?>(null) }
    LaunchedEffect(jobId, show) {
        if (!show) return@LaunchedEffect
        do {
            result = withContext(Dispatchers.IO) { JSONObject(jobs.read(jobId, 65_536)) }
            if (result?.optBoolean("running") != true) break
            delay(1_000)
        } while (true)
    }
    EtaWindowDialog(show = show, title = result?.optString("description")?.ifBlank { jobId } ?: jobId,
        onDismissRequest = onDismiss, onDismissFinished = onDismissFinished) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            val current = result
            if (current == null) {
                Text(stringResource(R.string.background_command_loading))
            } else {
                current.optString("command").takeIf(String::isNotBlank)?.let { command ->
                    SelectionContainer { Text(command) }
                }
                if (current.optBoolean("ok")) {
                    val status = when (current.optString("status")) {
                        "running" -> R.string.background_command_status_running
                        "completed" -> R.string.background_command_status_completed
                        "failed" -> R.string.background_command_status_failed
                        "timed_out" -> R.string.background_command_status_timed_out
                        "stopped" -> R.string.background_command_status_stopped
                        "interrupted" -> R.string.background_command_status_interrupted
                        else -> R.string.background_command_status_unknown
                    }
                    Text(stringResource(R.string.background_command_status, stringResource(status)))
                    if (!current.isNull("exit_code")) {
                        Text(stringResource(R.string.tool_detail_exit_code, current.getInt("exit_code")))
                    }
                    val ended = if (current.isNull("completed_at")) System.currentTimeMillis() else current.getLong("completed_at")
                    Text(stringResource(
                        R.string.background_command_elapsed_seconds,
                        (ended - current.optLong("started_at")).coerceAtLeast(0) / 1000,
                    ))
                }
                if (!current.optBoolean("ok")) Text(current.optString("message"), color = MiuixTheme.colorScheme.error)
                actionError?.let { Text(it, color = MiuixTheme.colorScheme.error) }
                val output = current.optString("output")
                if (current.optBoolean("ok")) {
                    SelectionContainer { Text(if (expanded) output else output.lines().takeLast(6).joinToString("\n")) }
                    EtaTextButton(
                        text = stringResource(
                            if (expanded) R.string.background_command_collapse_log
                            else R.string.background_command_expand_log,
                        ),
                        onClick = { expanded = !expanded },
                    )
                }
                if (originalDetail != null && !originalDetail.sensitive) {
                    EtaTextButton(
                        text = stringResource(R.string.tool_detail_original_arguments_and_result),
                        onClick = { showOriginal = !showOriginal },
                    )
                    if (showOriginal || current.optString("code") == "JOB_NOT_FOUND") {
                        originalDetail.argumentsJson?.let { arguments ->
                            Text(stringResource(R.string.tool_detail_arguments))
                            SelectionContainer { Text(ToolCallDetails.pretty(arguments)) }
                        }
                        originalDetail.result?.let { toolResult ->
                            Text(stringResource(R.string.tool_detail_result))
                            SelectionContainer { Text(ToolCallDetails.pretty(toolResult)) }
                        }
                    }
                }
                if (current.optBoolean("running")) {
                    EtaTextButton(text = stringResource(R.string.background_tasks_stop), onClick = {
                        scope.launch {
                            val stopped = withContext(Dispatchers.IO) { JSONObject(jobs.stop(jobId)) }
                            actionError = if (stopped.optBoolean("ok")) null else stopped.optString("message")
                            result = withContext(Dispatchers.IO) { JSONObject(jobs.read(jobId, 65_536)) }
                        }
                    })
                }
            }
            EtaTextButton(text = stringResource(R.string.action_close), onClick = onDismiss)
        }
    }
}
