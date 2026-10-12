package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import java.io.IOException
import org.json.JSONArray

/**
 * 用户配置的模型回退链。主模型在重试用尽或遇到不可重试的失败后，按顺序换用下一个备用模型
 * 继续同一回合；切换后本次 run 的剩余回合都使用新模型，下一次 run 重新从主模型开始。
 */
internal data class ModelFallbackPolicy(
    val configs: List<AgentModelClient.ModelConfig>,
    /** 主模型被服务商内容审核拦截时是否也换模型；默认不换。 */
    val onContentFilter: Boolean = false,
) {
    fun shouldFallBack(failure: Throwable): Boolean = when (failure) {
        is AgentModelFailure -> when {
            // 上下文超限由压缩处理；改写回复的失败与模型无关。
            failure.code == "CONTEXT_OVERFLOW" -> false
            failure.code.startsWith("REPLY_REWRITE") -> false
            failure.code == "MODEL_CONTENT_FILTER" -> onContentFilter
            else -> true
        }
        // TLS、协议等未被分类为可重试的网络失败同样可能只是这一家服务的问题。
        is IOException -> true
        else -> false
    }

    companion object {
        val NONE = ModelFallbackPolicy(emptyList())

        /** 换模型前移除只属于上一个模型的 Provider 状态（Anthropic 签名块、Responses opaque items）。 */
        fun stripProviderState(messages: JSONArray) {
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                AnthropicEphemeralState.strip(message)
                ResponsesEphemeralState.strip(message)
            }
        }

        /**
         * 让备用模型能接上当前回合。工具回合进行中（最后一条 assistant 带工具调用）时，
         * Anthropic 开启思考要求该消息以本模型的思考块开头，签名已无法提供，因此关闭思考；
         * 模型强制思考时这一备用模型无法使用，返回 null。
         */
        fun prepare(candidate: AgentModelClient.ModelConfig, messages: JSONArray): AgentModelClient.ModelConfig? {
            if (candidate.providerType != ProviderTypes.ANTHROPIC) return candidate
            if (!candidate.effectiveReasoningEffort.enablesReasoning || !inToolTurn(messages)) return candidate
            if (candidate.reasoningCapabilities?.mandatory == true) return null
            return candidate.copy(thinkingEnabled = false, reasoningEffort = ReasoningEffort.OFF)
        }

        private fun inToolTurn(messages: JSONArray): Boolean {
            for (index in messages.length() - 1 downTo 0) {
                val message = messages.optJSONObject(index) ?: continue
                if (message.optString("role") == "assistant") {
                    return AgentConversationCodec.parseToolCalls(message).isNotEmpty()
                }
            }
            return false
        }
    }
}
