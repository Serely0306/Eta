package io.github.mangi.eta.agent.mcp.server

import android.content.SharedPreferences
import android.util.Base64
import io.github.mangi.eta.config.Prefs
import java.security.SecureRandom
import org.json.JSONArray

/**
 * 设备端 MCP 服务的配置，保存在 App 私有的本地 Agent 配置中，不同步到 Hook 进程。
 * 令牌始终必需：即使只监听本机，同一设备上的其他应用也能连到回环地址。
 */
internal data class EtaMcpServerSettings(
    val enabled: Boolean = false,
    /** false 时只监听 127.0.0.1，电脑端经 `adb forward` 连接；true 时监听所有网卡供局域网连接。 */
    val lanAccess: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val token: String = "",
) {
    companion object {
        const val DEFAULT_PORT = 18765
        val PORT_RANGE = 1024..65535

        private const val KEY_ENABLED = "mcp_server_enabled"
        private const val KEY_LAN_ACCESS = "mcp_server_lan_access"
        private const val KEY_PORT = "mcp_server_port"
        private const val KEY_TOKEN = "mcp_server_token"
        private const val TOKEN_BYTES = 32

        fun load(preferences: SharedPreferences? = Prefs.localAgentPreferences()): EtaMcpServerSettings {
            val prefs = preferences ?: return EtaMcpServerSettings()
            return runCatching {
                EtaMcpServerSettings(
                    enabled = prefs.getBoolean(KEY_ENABLED, false),
                    lanAccess = prefs.getBoolean(KEY_LAN_ACCESS, false),
                    port = prefs.getInt(KEY_PORT, DEFAULT_PORT).takeIf { it in PORT_RANGE } ?: DEFAULT_PORT,
                    token = prefs.getString(KEY_TOKEN, null).orEmpty(),
                )
            }.getOrDefault(EtaMcpServerSettings())
        }

        /** 保存配置；首次启用时自动生成令牌。返回实际保存的配置，失败时返回 null。 */
        fun save(
            settings: EtaMcpServerSettings,
            preferences: SharedPreferences? = Prefs.localAgentPreferences(),
        ): EtaMcpServerSettings? {
            val prefs = preferences ?: return null
            val normalized = settings.copy(
                port = settings.port.takeIf { it in PORT_RANGE } ?: DEFAULT_PORT,
                token = settings.token.ifBlank { newToken() },
            )
            val saved = runCatching {
                prefs.edit()
                    .putBoolean(KEY_ENABLED, normalized.enabled)
                    .putBoolean(KEY_LAN_ACCESS, normalized.lanAccess)
                    .putInt(KEY_PORT, normalized.port)
                    .putString(KEY_TOKEN, normalized.token)
                    .commit()
            }.getOrDefault(false)
            return normalized.takeIf { saved }
        }

        fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }
}

/**
 * 只作用于 MCP 出口的隐私设置，Eta 自身的模型调用不受影响。每次调用时读取，修改后不必重启服务。
 */
internal data class EtaMcpPrivacySettings(
    /** 工具结果中的号码类信息替换为可逆占位符后再发给电脑端。 */
    val redactOutput: Boolean = true,
    /** 是否向电脑端返回屏幕截图、网页截图与图片文件。 */
    val allowImages: Boolean = false,
    /**
     * 委托任务可用的模型（Eta 内部模型 id），靠前的为默认。为空时委托任务使用 Eta 的默认模型；
     * 非空时只能使用其中的模型，回退链也只在其中切换。
     */
    val taskModelIds: List<String> = emptyList(),
) {
    companion object {
        private const val KEY_REDACT_OUTPUT = "mcp_server_redact_output"
        private const val KEY_ALLOW_IMAGES = "mcp_server_allow_images"
        private const val KEY_TASK_MODEL_IDS = "mcp_server_task_model_ids"
        /** 旧版按模型保存的强度，已改由模型自身的默认强度决定；保存时清掉。 */
        private const val KEY_TASK_MODEL_EFFORTS = "mcp_server_task_model_efforts"

        fun load(preferences: SharedPreferences? = Prefs.localAgentPreferences()): EtaMcpPrivacySettings {
            // 读不到配置时按最严格的默认值处理。
            val prefs = preferences ?: return EtaMcpPrivacySettings()
            return runCatching {
                EtaMcpPrivacySettings(
                    redactOutput = prefs.getBoolean(KEY_REDACT_OUTPUT, true),
                    allowImages = prefs.getBoolean(KEY_ALLOW_IMAGES, false),
                    taskModelIds = decodeIds(prefs.getString(KEY_TASK_MODEL_IDS, null)),
                )
            }.getOrDefault(EtaMcpPrivacySettings())
        }

        fun save(
            settings: EtaMcpPrivacySettings,
            preferences: SharedPreferences? = Prefs.localAgentPreferences(),
        ): Boolean {
            val prefs = preferences ?: return false
            return runCatching {
                prefs.edit()
                    .putBoolean(KEY_REDACT_OUTPUT, settings.redactOutput)
                    .putBoolean(KEY_ALLOW_IMAGES, settings.allowImages)
                    .putString(KEY_TASK_MODEL_IDS, JSONArray(settings.taskModelIds.distinct()).toString())
                    .remove(KEY_TASK_MODEL_EFFORTS)
                    .commit()
            }.getOrDefault(false)
        }

        /** 读不出的列表视为空（使用默认模型），不因格式问题阻断服务。 */
        private fun decodeIds(raw: String?): List<String> {
            val array = raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
            return (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }.distinct()
        }
    }
}
