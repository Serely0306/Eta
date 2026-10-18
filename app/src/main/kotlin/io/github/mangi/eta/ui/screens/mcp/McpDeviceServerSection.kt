package io.github.mangi.eta.ui.screens.mcp

import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.server.EtaMcpActivity
import io.github.mangi.eta.agent.mcp.server.EtaMcpPrivacySettings
import io.github.mangi.eta.agent.mcp.server.EtaMcpProtocol
import io.github.mangi.eta.agent.mcp.server.EtaMcpServerController
import io.github.mangi.eta.agent.mcp.server.EtaMcpServerSettings
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.GroupedModelChecklist
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.effortDropdownItems
import io.github.mangi.eta.ui.components.entryEffortOptions
import io.github.mangi.eta.ui.components.modelChoiceGroups
import io.github.mangi.eta.ui.components.reasoningCapabilitiesFor
import io.github.mangi.eta.ui.components.unavailableModelLabel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** MCP 页顶部的“让电脑端连接 Eta”：设备端 MCP 服务的开关、监听范围、端口、令牌与连接配置。 */
@Composable
internal fun McpDeviceServerSection() {
    val context = LocalContext.current
    val resources = LocalResources.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val status by EtaMcpServerController.status.collectAsState()
    val activity by EtaMcpServerController.activity.collectAsState()
    var settings by remember { mutableStateOf(EtaMcpServerSettings.load()) }
    var privacy by remember { mutableStateOf(EtaMcpPrivacySettings.load()) }
    var editingPort by remember { mutableStateOf(false) }
    // 本页每 30 秒刷新一次时间，记住 Flow 避免每次重组重新订阅。
    val providersFlow = remember { ProviderRepository.providersFlow() }
    val providers by providersFlow.collectAsState(initial = emptyList())
    // 与设置页的模型选择一致：按服务商分组，只列当前可用的模型。
    val modelGroups = remember(providers) { modelChoiceGroups(providers) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { EtaMcpServerController.refreshAddresses() }
    // 连接情况变化时立即刷新，其余时间定期刷新“N 分钟前”。
    LaunchedEffect(activity) {
        while (true) {
            now = System.currentTimeMillis()
            delay(CONNECTION_REFRESH_MS)
        }
    }

    fun update(next: EtaMcpServerSettings) {
        EtaMcpServerSettings.save(next)?.let { saved ->
            settings = saved
            EtaMcpServerController.applySettings(context)
        }
    }

    fun updatePrivacy(next: EtaMcpPrivacySettings) {
        if (EtaMcpPrivacySettings.save(next)) privacy = next
    }

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, resources.getString(R.string.mcp_device_server_copied), Toast.LENGTH_SHORT).show()
    }

    val running = status as? EtaMcpServerController.Status.Running
    val host = running?.addresses?.firstOrNull()?.takeIf { settings.lanAccess } ?: "127.0.0.1"
    val endpoint = "http://$host:${settings.port}/mcp"
    val statusText = when (val current = status) {
        EtaMcpServerController.Status.Stopped -> stringResource(R.string.mcp_device_server_stopped)
        is EtaMcpServerController.Status.Failed -> stringResource(R.string.mcp_device_server_failed, current.reason)
        is EtaMcpServerController.Status.Running -> {
            val base = when {
                !current.lanAccess -> stringResource(R.string.mcp_device_server_running_local, current.port)
                current.addresses.isEmpty() -> stringResource(R.string.mcp_device_server_running_no_address, current.port)
                else -> stringResource(
                    R.string.mcp_device_server_running_lan,
                    current.addresses.joinToString { "$it:${current.port}" },
                )
            }
            if (current.keptAlive) base else stringResource(R.string.mcp_device_server_background_risk, base)
        }
    }

    EtaPreferenceGroupTitle(stringResource(R.string.mcp_device_server_title))
    EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        EtaSwitchPreference(
            title = stringResource(R.string.mcp_device_server_enable),
            summary = statusText,
            checked = settings.enabled,
            onCheckedChange = { enabled -> update(settings.copy(enabled = enabled)) },
        )
        if (running != null) {
            EtaPreference(
                title = stringResource(R.string.mcp_device_server_connections),
                summary = connectionSummary(resources, activity, now),
            )
        }
        EtaSwitchPreference(
            title = stringResource(R.string.mcp_device_server_lan),
            summary = stringResource(R.string.mcp_device_server_lan_summary),
            checked = settings.lanAccess,
            onCheckedChange = { lan -> update(settings.copy(lanAccess = lan)) },
        )
        EtaArrowPreference(
            title = stringResource(R.string.mcp_device_server_port),
            summary = settings.port.toString(),
            onClick = { editingPort = true },
        )
        if (settings.token.isNotBlank()) {
            val adbPort = settings.port.takeUnless { settings.lanAccess }
            EtaArrowPreference(
                title = stringResource(R.string.mcp_device_server_copy_claude),
                summary = stringResource(
                    if (settings.lanAccess) R.string.mcp_device_server_copy_claude_lan else R.string.mcp_device_server_copy_claude_usb,
                ),
                onClick = { copy(claudeCodeCommand(endpoint, settings.token, adbPort)) },
            )
            EtaArrowPreference(
                title = stringResource(R.string.mcp_device_server_copy_codex),
                summary = if (settings.lanAccess) {
                    stringResource(R.string.mcp_device_server_copy_codex_lan)
                } else {
                    stringResource(R.string.mcp_device_server_copy_codex_usb, settings.port)
                },
                onClick = { copy(codexConfig(endpoint, settings.token, adbPort)) },
            )
            EtaArrowPreference(
                title = stringResource(R.string.mcp_device_server_copy_token),
                summary = "•".repeat(8) + settings.token.takeLast(4),
                onClick = { copy(settings.token) },
            )
            EtaArrowPreference(
                title = stringResource(R.string.mcp_device_server_regenerate_token),
                summary = stringResource(R.string.mcp_device_server_regenerate_summary),
                onClick = {
                    update(settings.copy(token = EtaMcpServerSettings.newToken()))
                    Toast.makeText(
                        context,
                        resources.getString(R.string.mcp_device_server_token_regenerated),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }

    EtaPreferenceGroupTitle(stringResource(R.string.mcp_device_server_privacy_title))
    EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        EtaSwitchPreference(
            title = stringResource(R.string.mcp_device_server_redact),
            summary = stringResource(R.string.mcp_device_server_redact_summary),
            checked = privacy.redactOutput,
            onCheckedChange = { redact -> updatePrivacy(privacy.copy(redactOutput = redact)) },
        )
        EtaSwitchPreference(
            title = stringResource(R.string.mcp_device_server_allow_images),
            summary = stringResource(R.string.mcp_device_server_allow_images_summary),
            checked = privacy.allowImages,
            onCheckedChange = { allow -> updatePrivacy(privacy.copy(allowImages = allow)) },
        )
    }

    EtaPreferenceGroupTitle(stringResource(R.string.mcp_device_server_models_title))
    EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        EtaPreference(
            summary = stringResource(
                if (modelGroups.isEmpty()) R.string.mcp_device_server_models_empty else R.string.mcp_device_server_models_summary,
            ),
        )
        val listed = modelGroups.flatMap { group -> group.models.map { it.id } }
        val deletedLabel = stringResource(R.string.model_selection_deleted)
        val followDefaultEffortLabel = stringResource(R.string.reasoning_effort_follow_default)
        GroupedModelChecklist(
            groups = modelGroups,
            checkedIds = privacy.taskModelIds,
            unavailable = privacy.taskModelIds.filter { it !in listed }.map { id ->
                id to (unavailableModelLabel(providers, id)
                    ?.let { stringResource(R.string.model_selection_unavailable, it) }
                    ?: deletedLabel)
            },
            onCheckedChange = { id, checked ->
                val chosen = if (checked) privacy.taskModelIds + id else privacy.taskModelIds - id
                // 按列表顺序保存，靠前的为默认；暂时停用的模型保留勾选，重新启用后继续生效。
                val ordered = listed.filter { it in chosen } + chosen.filter { it !in listed }
                updatePrivacy(
                    privacy.copy(
                        taskModelIds = ordered.distinct(),
                        taskModelEfforts = privacy.taskModelEfforts.filterKeys { it in ordered },
                    ),
                )
            },
            // 每个勾选模型的强度；电脑端调用时仍可用 reasoning_effort 覆盖。
            checkedExtra = { modelId ->
                val capabilities = reasoningCapabilitiesFor(providers, modelId)
                if (capabilities != null) {
                    val options = entryEffortOptions(capabilities)
                    EtaDropdownPreference(
                        title = stringResource(R.string.reasoning_effort_title),
                        items = effortDropdownItems(options, followDefaultEffortLabel, capabilities.defaultEffort),
                        selectedIndex = options.indexOf(privacy.taskModelEfforts[modelId]).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            val effort = options.getOrNull(index)
                            val efforts = if (effort == null) {
                                privacy.taskModelEfforts - modelId
                            } else {
                                privacy.taskModelEfforts + (modelId to effort)
                            }
                            updatePrivacy(privacy.copy(taskModelEfforts = efforts))
                        },
                        modifier = Modifier.padding(start = 40.dp),
                    )
                }
            },
        )
    }

    if (editingPort) {
        McpPortDialog(
            initial = settings.port,
            onDismiss = { editingPort = false },
            onConfirm = { port ->
                editingPort = false
                update(settings.copy(port = port))
            },
        )
    }
}

@Composable
private fun McpPortDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableStateOf(initial.toString()) }
    val port = value.trim().toIntOrNull()?.takeIf { it in EtaMcpServerSettings.PORT_RANGE }
    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.mcp_device_server_port),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit).take(5) },
                label = stringResource(R.string.mcp_device_server_port),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (port == null) {
                Text(
                    text = stringResource(R.string.mcp_device_server_port_invalid),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(android.R.string.ok),
                confirmEnabled = port != null,
                onCancel = onDismiss,
                onConfirm = { port?.let(onConfirm) },
            )
        }
    }
}

private const val CONNECTION_REFRESH_MS = 30_000L

private fun connectionSummary(resources: Resources, activity: EtaMcpActivity, now: Long): String {
    val cutoff = now - EtaMcpProtocol.SESSION_IDLE_MS
    val lines = activity.clients
        .filter { it.lastSeenAt >= cutoff }
        .distinctBy { it.name }
        .map { client ->
            resources.getString(
                R.string.mcp_device_server_client_seen,
                client.name ?: resources.getString(R.string.mcp_device_server_client_unknown),
                relativeTime(resources, client.lastSeenAt, now),
            )
        }
        .toMutableList()
    activity.lastRejectedAt?.takeIf { it >= cutoff }?.let { rejectedAt ->
        lines += resources.getString(R.string.mcp_device_server_rejected, relativeTime(resources, rejectedAt, now))
    }
    return lines.joinToString("\n").ifEmpty { resources.getString(R.string.mcp_device_server_connections_none) }
}

private fun relativeTime(resources: Resources, at: Long, now: Long): String {
    val minutes = ((now - at).coerceAtLeast(0L) / 60_000L).toInt()
    return if (minutes < 1) {
        resources.getString(R.string.mcp_device_server_just_now)
    } else {
        resources.getString(R.string.mcp_device_server_minutes_ago, minutes)
    }
}

/**
 * Claude Code 的添加命令，写入用户级配置以便在任意目录可用；[adbPort] 非空时（USB 模式）
 * 在前面加上端口转发。同名服务器已存在时 `claude mcp add` 会失败，需先 `claude mcp remove -s user eta`。
 */
internal fun claudeCodeCommand(endpoint: String, token: String, adbPort: Int? = null): String = buildString {
    adbPort?.let { append("adb forward tcp:$it tcp:$it\n") }
    append("claude mcp add -s user --transport http eta $endpoint --header \"Authorization: Bearer $token\"")
}

/** Codex 的 `config.toml` 片段；USB 模式附一行提醒先做端口转发的注释。 */
internal fun codexConfig(endpoint: String, token: String, adbPort: Int? = null): String = buildString {
    adbPort?.let { append("# USB: run `adb forward tcp:$it tcp:$it` on the computer first\n") }
    append("[mcp_servers.eta]\nurl = \"$endpoint\"\nhttp_headers = { Authorization = \"Bearer $token\" }\n")
}
