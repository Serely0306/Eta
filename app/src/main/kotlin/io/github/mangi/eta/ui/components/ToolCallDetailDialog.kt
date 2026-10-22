package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.OpenAiResponsesProvider
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.agent.runtime.SubagentTranscriptStore
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SubagentDetails
import io.github.mangi.eta.ui.model.SubagentProgressUi
import io.github.mangi.eta.ui.model.TerminalOutput
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolCallDetail
import io.github.mangi.eta.ui.model.ToolCallDetails
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 工具详情的数据来源：当前会话的完整脱敏记录与消息列表。只有展开的工具行和打开的详情读取它。 */
internal class ToolCallDetailSource(
    private val journal: List<AgentModelClient.ConversationMessage>,
    private val messages: List<AgentChatMessageUi>,
    /** 本轮的工具结果要等 run 结束才写入记录。 */
    val runInProgress: Boolean,
) {
    fun find(message: ToolActivityMessageUi): ToolCallDetail? = ToolCallDetails.find(journal, messages, message)
}

internal val LocalToolCallDetailSource = compositionLocalOf<ToolCallDetailSource?>(referentialEqualityPolicy()) { null }

@Composable
internal fun rememberToolCallDetail(message: ToolActivityMessageUi): ToolCallDetail? {
    val source = LocalToolCallDetailSource.current
    return remember(source, message) { source?.find(message) }
}

/** 展开的终端行：输出默认显示前几行，可就地展开；退出码、耗时与截断说明在下方一行。 */
@Composable
internal fun TerminalOutputBlock(output: TerminalOutput, modifier: Modifier = Modifier) {
    val text = remember(output) {
        listOf(output.stdout, output.stderr).map(String::trimEnd).filter(String::isNotEmpty).joinToString("\n")
    }
    val lines = remember(text) { if (text.isEmpty()) emptyList() else text.lines() }
    var showAll by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (lines.isNotEmpty()) {
            CodeBlock(
                text = if (showAll) lines.take(MAX_INLINE_LINES).joinToString("\n")
                    else lines.take(PREVIEW_LINES).joinToString("\n"),
                background = MiuixTheme.colorScheme.surfaceContainer,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = terminalStatusLine(output),
                style = MiuixTheme.textStyles.footnote2,
                color = if (output.timedOut || (output.exitCode ?: 0) != 0) StatusError
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f),
            )
            if (lines.size > PREVIEW_LINES) {
                LinkButton(
                    text = if (showAll) stringResource(R.string.tool_output_collapse)
                        else pluralStringResource(R.plurals.tool_output_show_all, lines.size, lines.size),
                    onClick = { showAll = !showAll },
                )
            }
        }
    }
}

@Composable
private fun terminalStatusLine(output: TerminalOutput): String = buildList {
    when {
        output.timedOut -> add(stringResource(R.string.tool_detail_timed_out))
        output.exitCode != null -> add(stringResource(R.string.tool_detail_exit_code, output.exitCode))
    }
    output.durationMs?.let { add(stringResource(R.string.tool_detail_seconds, formatSeconds(it))) }
    if (output.truncated) add(stringResource(R.string.tool_detail_truncated_short))
}.joinToString(" · ")

/**
 * 工具详情：完整调用参数与结果。内容取自会话记录，敏感工具与本轮未结束时给出说明，并退回到摘要。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ToolCallDetailDialog(
    message: ToolActivityMessageUi,
    show: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    val source = LocalToolCallDetailSource.current
    val detail = rememberToolCallDetail(message)
    val terminal = remember(detail) {
        detail?.result?.takeIf { message.toolName in TERMINAL_TOOLS }?.let(ToolCallDetails::terminalOutput)
    }
    EtaWindowDialog(
        show = show,
        title = message.argumentsSummary.ifBlank { toolDisplayName(message.toolName) },
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(stringResource(message.status.labelRes()))
                terminal?.let { output ->
                    when {
                        output.timedOut -> Pill(stringResource(R.string.tool_detail_timed_out))
                        output.exitCode != null -> Pill(stringResource(R.string.tool_detail_exit_code, output.exitCode))
                    }
                    output.durationMs?.let { Pill(stringResource(R.string.tool_detail_seconds, formatSeconds(it))) }
                }
                Pill(message.toolName)
            }
            when {
                detail == null && message.toolName == OpenAiResponsesProvider.HOSTED_WEB_SEARCH -> {
                    Note(stringResource(R.string.tool_detail_hosted_search))
                    message.argumentsSummary.takeIf(String::isNotBlank)?.let {
                        DetailSection(stringResource(R.string.tool_detail_search_query), it, monospace = false)
                    }
                    val sources = remember(message.resultSummary) { ToolCallDetails.hostedSources(message.resultSummary) }
                    if (sources.isNotEmpty()) {
                        SectionTitle(pluralStringResource(R.plurals.tool_detail_sources, sources.size, sources.size))
                        sources.forEach { url -> LinkCard(hostOf(url), url, "") }
                    }
                }
                detail == null -> {
                    Note(stringResource(
                        if (source?.runInProgress == true) R.string.tool_detail_pending else R.string.tool_detail_unavailable,
                    ))
                    message.command?.takeIf(String::isNotBlank)?.let { DetailSection(stringResource(R.string.shell_command), it) }
                    message.resultSummary?.takeIf(String::isNotBlank)?.let {
                        DetailSection(stringResource(R.string.tool_detail_result), it)
                    }
                }
                detail.sensitive -> Note(stringResource(R.string.tool_detail_sensitive))
                else -> {
                    detail.argumentsJson?.let { arguments ->
                        DetailSection(stringResource(R.string.tool_detail_arguments), ToolCallDetails.pretty(arguments))
                    }
                    ToolResultSections(message, detail, terminal)
                    Note(stringResource(R.string.tool_detail_source_note))
                }
            }
        }
    }
}

@Composable
private fun ToolResultSections(message: ToolActivityMessageUi, detail: ToolCallDetail, terminal: TerminalOutput?) {
    val result = detail.result ?: return
    if (terminal != null) {
        val stdoutLines = remember(terminal) { terminal.stdout.trimEnd().takeIf(String::isNotEmpty)?.lines()?.size ?: 0 }
        DetailSection(
            title = pluralStringResource(R.plurals.tool_detail_stdout, stdoutLines, stdoutLines),
            text = terminal.stdout.trimEnd().ifEmpty { stringResource(R.string.tool_detail_empty) },
            copyText = terminal.stdout.takeIf(String::isNotBlank),
        )
        if (terminal.stderr.isNotBlank()) {
            DetailSection(stringResource(R.string.tool_detail_stderr), terminal.stderr.trimEnd())
        }
        if (terminal.truncated) Note(stringResource(R.string.tool_detail_truncated))
        return
    }
    when (message.toolName) {
        "web_search" -> {
            ToolCallDetails.searchQuery(detail.argumentsJson)?.let {
                DetailSection(stringResource(R.string.tool_detail_search_query), it, monospace = false)
            }
            val results = remember(result) { ToolCallDetails.searchResults(result) }
            if (results.isNotEmpty()) {
                SectionTitle(pluralStringResource(R.plurals.tool_detail_search_results, results.size, results.size))
                results.forEach { item -> LinkCard(item.title, item.url, item.snippet) }
                return
            }
        }
        "fetch_url" -> {
            val page = remember(result) { ToolCallDetails.webPage(result) }
            if (page != null) {
                LinkCard(page.title, page.url, "")
                LimitedSection(stringResource(R.string.tool_detail_page), page.text, monospace = false)
                if (page.hasMore) Note(stringResource(R.string.tool_detail_page_more))
                return
            }
        }
    }
    LimitedSection(stringResource(R.string.tool_detail_result), remember(result) { ToolCallDetails.pretty(result) })
}

@Composable
private fun LimitedSection(title: String, text: String, monospace: Boolean = true) {
    val limited = text.length > MAX_DETAIL_CHARS
    DetailSection(title, if (limited) text.take(MAX_DETAIL_CHARS) else text, monospace = monospace, copyText = text)
    if (limited) Note(stringResource(R.string.tool_detail_text_limited, MAX_DETAIL_CHARS))
}

@Composable
private fun DetailSection(title: String, text: String, monospace: Boolean = true, copyText: String? = text) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(title, Modifier.weight(1f))
            copyText?.let { CopyButton(it) }
        }
        CodeBlock(text, monospace = monospace)
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier,
    )
}

@Composable
private fun CopyButton(text: String) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    LinkButton(
        text = stringResource(if (copied) R.string.copy_copied else R.string.ui_copy_4edd1d),
        onClick = {
            @Suppress("DEPRECATION")
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
    )
}

/** 行内的次要操作（展开全部、详情、查看过程、复制）用文字链接，不用实心按钮。 */
@Composable
internal fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.primary,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

@Composable
private fun CodeBlock(
    text: String,
    monospace: Boolean = true,
    background: Color = MiuixTheme.colorScheme.surface,
) {
    SelectionContainer {
        Text(
            text = text,
            style = if (monospace) MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace)
                else MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .squircleSurface(color = background, cornerRadius = 10.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun LinkCard(title: String, url: String, snippet: String) {
    val uriHandler = LocalUriHandler.current
    val openable = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = 10.dp)
            .then(if (openable) Modifier.clickable { runCatching { uriHandler.openUri(url) } } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body2,
                color = if (openable) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (url.isNotBlank()) {
            Text(
                text = url,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (snippet.isNotBlank()) {
            Text(
                text = snippet,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = 8.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
    )
}

/** run_subagents 展开后的子 Agent 列表：运行中显示当前步骤，结束后可查看每个子 Agent 的完整过程。 */
@Composable
internal fun SubagentList(message: ToolActivityMessageUi, detail: ToolCallDetail?) {
    val restored = remember(detail) { SubagentDetails.fromDetail(detail) }
    val subagents = message.subagents.ifEmpty { restored }
    val ids = remember(message.id) { SubagentDetails.runAndCallId(message.id) }
    // 记序号而不是进度快照，弹窗打开期间的状态与当前步骤随进度更新。
    var openedIndex by remember(message.id) { mutableStateOf<Int?>(null) }
    var show by remember(message.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        subagents.forEach { subagent ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleSurface(color = MiuixTheme.colorScheme.surfaceContainer, cornerRadius = 10.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.subagent_title, subagent.index + 1, subagent.total) + " · " +
                            stringResource(subagent.status.labelRes()),
                        style = MiuixTheme.textStyles.body2,
                        color = if (subagent.status == ToolActivityStatusUi.Failed) StatusError else MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (ids != null) {
                        LinkButton(
                            text = stringResource(R.string.subagent_view_process),
                            onClick = {
                                openedIndex = subagent.index
                                show = true
                            },
                        )
                    }
                }
                if (subagent.task.isNotBlank()) {
                    Text(
                        text = subagent.task,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val currentTool = toolDisplayName(subagent.currentTool)
                val step = if (subagent.status == ToolActivityStatusUi.Running && subagent.currentTool.isNotBlank()) {
                    stringResource(R.string.subagent_current_tool, subagent.currentDetail.ifBlank { currentTool })
                } else {
                    subagent.conclusion
                }
                if (step.isNotBlank()) {
                    Text(
                        text = step,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    val target = openedIndex?.let { index -> subagents.firstOrNull { it.index == index } }
    if (target != null && ids != null) {
        SubagentTranscriptDialog(
            runId = ids.first,
            toolCallId = ids.second,
            progress = target,
            show = show,
            onDismissRequest = { show = false },
            onDismissFinished = { openedIndex = null },
        )
    }
}

/** 运行中的子 Agent 计数，例如“完成 1 · 运行中 1 · 失败 1”。 */
@Composable
internal fun subagentCountsLine(subagents: List<SubagentProgressUi>): String = buildList {
    val done = subagents.count { it.status == ToolActivityStatusUi.Success }
    val running = subagents.count { it.status == ToolActivityStatusUi.Running }
    val failed = subagents.count { it.status == ToolActivityStatusUi.Failed }
    if (done > 0) add(stringResource(R.string.subagent_count_done, done))
    if (running > 0) add(stringResource(R.string.subagent_count_running, running))
    if (failed > 0) add(stringResource(R.string.subagent_count_failed, failed))
}.joinToString(" · ")

/**
 * 一个子 Agent 的过程：任务、思考、正文与工具行（工具同样可展开、看详情），最后是结论。
 * 运行中按轮读取快照，每隔一会儿刷新，并在看到末尾时跟随到最新一轮。
 */
@Composable
internal fun SubagentTranscriptDialog(
    runId: String,
    toolCallId: String,
    progress: SubagentProgressUi,
    show: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    val context = LocalContext.current
    var record by remember { mutableStateOf<SubagentTranscriptStore.Record?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val liveRun = progress.status == ToolActivityStatusUi.Running
    LaunchedEffect(runId, toolCallId, progress.index, liveRun) {
        while (true) {
            val next = withContext(Dispatchers.IO) {
                SubagentTranscriptStore.load(context, runId, toolCallId, progress.index)
            }
            record = next
            loaded = true
            // 结束时最终结果先写入、再通知界面，所以界面认为已结束时文件里已是最终结果。
            if (!liveRun || next?.running == false) break
            delay(LIVE_REFRESH_MS)
        }
    }
    val current = record
    val running = liveRun && current?.running != false
    val timeline = remember(current, running) {
        current?.let { SubagentDetails.timeline(it.transcript, running = running) }.orEmpty()
    }
    val source = remember(current, timeline) { current?.let { ToolCallDetailSource(it.transcript, timeline, runInProgress = false) } }
    val listState = rememberLazyListState()
    var followedSize by remember { mutableIntStateOf(0) }
    LaunchedEffect(timeline.size) {
        val previous = followedSize
        followedSize = timeline.size
        if (!running || timeline.size <= previous) return@LaunchedEffect
        // 第 0 项是任务；用户已经看到上次的最后一条时才跟随到底，往回翻看时不打断。
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (previous == 0 || lastVisible >= previous) listState.animateScrollToItem(timeline.size)
    }
    EtaWindowDialog(
        show = show,
        title = stringResource(R.string.subagent_title, progress.index + 1, progress.total),
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
        CompositionLocalProvider(LocalToolCallDetailSource provides source) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "task") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val status = when {
                            running -> ToolActivityStatusUi.Running
                            current == null -> progress.status
                            // 进程中断时文件停在“运行中”，而界面上这一轮已经结束。
                            current.running -> ToolActivityStatusUi.Unknown
                            current.ok -> ToolActivityStatusUi.Success
                            else -> ToolActivityStatusUi.Failed
                        }
                        Pill(stringResource(status.labelRes()))
                        DetailSection(
                            stringResource(R.string.subagent_task),
                            current?.task ?: progress.task,
                            monospace = false,
                        )
                    }
                }
                when {
                    !loaded -> item(key = "loading") { Note(stringResource(R.string.subagent_transcript_loading)) }
                    current == null && running -> item(key = "waiting") { Note(stringResource(R.string.subagent_live_waiting)) }
                    current == null -> item(key = "missing") { Note(stringResource(R.string.subagent_transcript_unavailable)) }
                    else -> items(timeline, key = { it.id }) { message ->
                        when (message) {
                            is ThinkingMessageUi -> ThinkingRow(message = message, retainedStreamingState = null, compact = true)
                            is AgentMessageUi -> StaticMarkdown(
                                content = message.content,
                                tone = MarkdownTone.Answer,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                            is ToolActivityMessageUi -> ToolActivityRow(
                                message = message,
                                onOpenBrowser = {},
                                showBrowserShortcut = false,
                            )
                            else -> Unit
                        }
                    }
                }
                if (running) {
                    item(key = "live") {
                        val toolName = toolDisplayName(progress.currentTool)
                        val step = progress.currentDetail.ifBlank { toolName }
                        Note(
                            listOfNotNull(
                                step.takeIf(String::isNotBlank)?.let { stringResource(R.string.subagent_current_tool, it) },
                                stringResource(R.string.subagent_live_note),
                            ).joinToString("\n"),
                        )
                    }
                }
                val conclusion = current?.result?.takeIf(String::isNotBlank) ?: progress.conclusion
                if (conclusion.isNotBlank()) {
                    item(key = "conclusion") {
                        DetailSection(stringResource(R.string.subagent_conclusion), conclusion, monospace = false)
                    }
                }
            }
        }
    }
}

internal fun ToolActivityStatusUi.labelRes(): Int = when (this) {
    ToolActivityStatusUi.Running -> R.string.tool_status_running
    ToolActivityStatusUi.Success -> R.string.tool_status_success
    ToolActivityStatusUi.Failed -> R.string.tool_status_failed
    ToolActivityStatusUi.Unknown -> R.string.tool_status_unknown
}

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

private fun formatSeconds(durationMs: Long): String =
    if (durationMs < 10_000) "%.1f".format(Locale.ROOT, durationMs / 1_000.0) else (durationMs / 1_000).toString()

internal val TERMINAL_TOOLS = setOf("terminal", "terminal_job", "terminal_daemon", "run_command")
private const val LIVE_REFRESH_MS = 2_000L
private const val PREVIEW_LINES = 6
private const val MAX_INLINE_LINES = 200
private const val MAX_DETAIL_CHARS = 20_000
