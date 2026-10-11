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
     * 官方 OpenAI 按 prompt_cache_key 把同一对话路由到同一缓存分片。兼容端点可能拒绝未知字段，
     * 因此只对官方主机发送；用户在自定义请求体中显式设置时保持原值。
     */
    fun applyPromptCacheKey(request: JSONObject, baseUrl: String, sessionId: String) {
        if (baseUrl.toHttpUrlOrNull()?.host != "api.openai.com" || request.has("prompt_cache_key")) return
        request.put("prompt_cache_key", sessionRoutingId(sessionId))
    }

    private fun sessionRoutingId(sessionId: String): String =
        UUID.nameUUIDFromBytes(sessionId.toByteArray(Charsets.UTF_8)).toString()
}
