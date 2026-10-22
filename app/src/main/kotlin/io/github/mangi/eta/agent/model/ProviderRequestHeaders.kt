package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.agent.auth.CodexOAuth
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import java.util.UUID
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal object ProviderRequestHeaders {
    fun mergeInto(
        builder: Headers.Builder,
        baseUrl: String,
        customHeaders: List<CustomHeader>,
        sessionId: String = UUID.randomUUID().toString(),
        authMode: String = ProviderAuthModes.API_KEY,
        model: String? = null,
    ) {
        val profile = ProviderConnectionProfile.forAuthMode(authMode)
        if (profile != null) {
            require(customHeaders.none { isSubscriptionManagedHeader(it.name) }) {
                "订阅连接的认证、Cookie 与路由请求头由 Eta 管理，不能自定义覆盖"
            }
        }
        builder.set("User-Agent", "Eta")
        CustomHeaderFilter.mergeInto(builder, customHeaders)
        if (profile == ProviderConnectionProfile.XAI_GROK) {
            builder.set("X-XAI-Token-Auth", "xai-grok-cli")
            model?.let { builder.set("x-grok-model-override", it) }
        }
        if (profile == ProviderConnectionProfile.OPENAI_CODEX) {
            builder.set("originator", CodexOAuth.ORIGINATOR)
            builder.set("OpenAI-Beta", "responses=experimental")
            builder.set("session-id", sessionId)
        }
        if (baseUrl.toHttpUrlOrNull()?.host == "opencode.ai") {
            // 会话头由 Runtime 持有，避免固定自定义值把所有对话合并到同一路由。
            builder.set("x-opencode-session", sessionRoutingId(sessionId))
        }
    }

    /**
     * OpenAI 按 prompt_cache_key 把同一对话路由到同一缓存分片。Responses 的兼容中转站同样依赖它：
     * 不带时同一会话的命中率只有约 16%（AG-14），因此 [anyHost] 为 true 时对所有主机发送，
     * 由服务商设置的开关关闭。Chat Completions 的兼容端点可能拒绝未知字段，只对官方主机发送。
     * 用户在自定义请求体中显式设置时保持原值。
     */
    fun applyPromptCacheKey(request: JSONObject, baseUrl: String, sessionId: String, anyHost: Boolean = false) {
        if (request.has("prompt_cache_key")) return
        if (!anyHost && baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") return
        request.put("prompt_cache_key", sessionRoutingId(sessionId))
    }

    private fun sessionRoutingId(sessionId: String): String =
        UUID.nameUUIDFromBytes(sessionId.toByteArray(Charsets.UTF_8)).toString()
    private fun isSubscriptionManagedHeader(name: String): Boolean {
        val normalized = name.trim().lowercase()
        return CustomHeaderFilter.isForbidden(normalized) ||
            normalized in setOf(
                "cookie", "proxy-authorization", "api-key", "user-agent", "origin", "referer", "originator", "session-id",
            ) || normalized.startsWith("x-grok-") || normalized.startsWith("x-xai-") ||
            normalized.startsWith("openai-") || normalized.startsWith("x-openai-") ||
            normalized.startsWith("chatgpt-") || normalized == "x-authenticateresponse"
    }
}
