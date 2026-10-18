package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    /** 写入缓存的输入（Anthropic `cache_creation_input_tokens`）。 */
    val cacheWriteTokens: Int? = null,
    /**
     * 本次请求的全部输入，含缓存读写。各协议口径不同：OpenAI 的 input 已含缓存，Anthropic 的 input
     * 只是未缓存部分，由各 Provider 解析时换算，统计与命中率只用这个字段。
     */
    val promptTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null &&
            cacheWriteTokens == null &&
            promptTokens == null
}
