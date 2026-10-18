package io.github.mangi.eta.ui.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.ui.components.LocalToolCallDetailSource
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.SubagentList
import io.github.mangi.eta.ui.components.TerminalOutputBlock
import io.github.mangi.eta.ui.components.ThinkingRow
import io.github.mangi.eta.ui.components.ToolActivityRow
import io.github.mangi.eta.ui.components.ToolCallDetailSource
import io.github.mangi.eta.ui.model.SubagentProgressUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolCallDetails
import org.json.JSONArray
import org.json.JSONObject

/**
 * AG-18 行内界面的预览：思考块的推理 token、终端行与展开后的输出块、托管搜索行、子 Agent 列表。
 * 详情弹窗是单独的窗口，截图截不到。
 */
@Composable
internal fun AgentTimelinePreview() {
    val stdout = (1..23).joinToString("\n") { "${it * 37}M  /sdcard/Download/file-$it.bin" }
    val terminal = ToolActivityMessageUi(
        id = "run-tool-1-call_t",
        toolName = "terminal",
        status = ToolActivityStatusUi.Success,
        argumentsSummary = "查看存储占用 · Android · root",
        command = "df -h /data /sdcard && du -sh /sdcard/Download/* /sdcard/DCIM/* 2>/dev/null | sort -h | tail -n 20",
        resultSummary = "执行完成\n37M  /sdcard/Download/file-1.bin\n…",
    )
    val journal = listOf(
        ConversationMessage(
            role = "assistant",
            toolCallsJson = JSONArray().put(JSONObject().put("id", "call_t").put("type", "function").put(
                "function",
                JSONObject().put("name", "terminal").put("arguments", JSONObject().put("action", "exec")
                    .put("command", terminal.command).toString()),
            )).toString(),
        ),
        ConversationMessage(
            role = "tool",
            toolCallId = "call_t",
            content = JSONObject().put("ok", true).put("exit_code", 0).put("stdout", stdout).put("stderr", "")
                .put("duration_ms", 1_400).toString(),
        ),
    )
    val hosted = ToolActivityMessageUi(
        id = "run-tool-2-ws_1",
        toolName = "网页搜索",
        status = ToolActivityStatusUi.Success,
        argumentsSummary = "HyperOS 4 存储清理 入口",
        resultSummary = "3 个来源\nhttps://a.example/1\nhttps://b.example/2\nhttps://c.example/3",
    )
    val subagents = ToolActivityMessageUi(
        id = "run-tool-3-call_s",
        toolName = "run_subagents",
        status = ToolActivityStatusUi.Running,
        argumentsSummary = "派出 3 个子 Agent",
        subagents = listOf(
            SubagentProgressUi(0, 3, ToolActivityStatusUi.Success, "统计下载目录里超过 500 MB 的文件", conclusion = "共 4 个，合计 8.3 GB，最大的是 ide-offline.zip"),
            SubagentProgressUi(1, 3, ToolActivityStatusUi.Running, "查系统自带的清理入口", currentTool = "terminal", currentDetail = "查看设置项 · Android"),
            SubagentProgressUi(2, 3, ToolActivityStatusUi.Failed, "读取相册缩略图缓存大小", conclusion = "超过 40 轮仍未完成，已停止该子任务"),
        ),
    )
    val messages = listOf(terminal, hosted, subagents)
    CompositionLocalProvider(LocalToolCallDetailSource provides ToolCallDetailSource(journal, messages, runInProgress = false)) {
        MiuixScaffoldPage(title = "工具与思考", onBack = {}) {
            item(key = "thinking") {
                ThinkingRow(
                    message = ThinkingMessageUi("run-thinking-1-0", "先用终端看分区占用。", false, 12, true, reasoningTokens = 1_800),
                    retainedStreamingState = null,
                )
            }
            item(key = "thinking-empty") {
                ThinkingRow(
                    message = ThinkingMessageUi("run-thinking-2-usage", "", false, null, true, reasoningTokens = 400),
                    retainedStreamingState = null,
                )
            }
            item(key = "terminal") {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    ToolActivityRow(terminal, onOpenBrowser = {}, showBrowserShortcut = false)
                    TerminalOutputBlock(ToolCallDetails.terminalOutput(journal[1].content)!!)
                }
            }
            item(key = "hosted") {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    ToolActivityRow(hosted, onOpenBrowser = {}, showBrowserShortcut = false)
                }
            }
            item(key = "subagents") {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    ToolActivityRow(subagents, onOpenBrowser = {}, showBrowserShortcut = false)
                    SubagentList(subagents, detail = null)
                }
            }
        }
    }
}
