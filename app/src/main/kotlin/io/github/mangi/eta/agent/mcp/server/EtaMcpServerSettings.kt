package io.github.mangi.eta.agent.mcp.server

import android.content.SharedPreferences
import android.util.Base64
import io.github.mangi.eta.config.Prefs
import java.security.SecureRandom

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
