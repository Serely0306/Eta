package io.github.mangi.eta.ui.screens.mcp

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
import io.github.mangi.eta.agent.mcp.server.EtaMcpServerController
import io.github.mangi.eta.agent.mcp.server.EtaMcpServerSettings
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
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
    var settings by remember { mutableStateOf(EtaMcpServerSettings.load()) }
    var editingPort by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { EtaMcpServerController.refreshAddresses() }

    fun update(next: EtaMcpServerSettings) {
        EtaMcpServerSettings.save(next)?.let { saved ->
            settings = saved
            EtaMcpServerController.applySettings(context)
        }
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
            EtaArrowPreference(
                title = stringResource(R.string.mcp_device_server_copy_claude),
                summary = if (settings.lanAccess) endpoint else stringResource(R.string.mcp_device_server_adb_hint, settings.port),
                onClick = { copy(claudeCodeCommand(endpoint, settings.token)) },
            )
            EtaArrowPreference(
                title = stringResource(R.string.mcp_device_server_copy_codex),
                summary = endpoint,
                onClick = { copy(codexConfig(endpoint, settings.token)) },
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

internal fun claudeCodeCommand(endpoint: String, token: String): String =
    "claude mcp add --transport http eta $endpoint --header \"Authorization: Bearer $token\""

internal fun codexConfig(endpoint: String, token: String): String =
    "[mcp_servers.eta]\nurl = \"$endpoint\"\nhttp_headers = { Authorization = \"Bearer $token\" }\n"
