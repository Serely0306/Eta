package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomHeader
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
    ) {
        builder.set("User-Agent", "Eta")
        CustomHeaderFilter.mergeInto(builder, customHeaders)
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
}
