package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.usage.ModelUsageLedger

internal object ProviderClientFactory {
    private val responses = UsageRecordingProviderClient(OpenAiResponsesProvider, sink = ModelUsageLedger::record)
    private val chatCompletions = UsageRecordingProviderClient(OpenAiChatCompletionsProvider, sink = ModelUsageLedger::record)
    private val anthropic = UsageRecordingProviderClient(AnthropicMessagesProvider, sink = ModelUsageLedger::record)

    /** 返回的客户端会把每次请求的用量记进账本。 */
    fun getClient(config: AgentModelClient.ModelConfig): AgentProviderClient =
        when (config.providerType) {
            ProviderTypes.OPENAI_COMPATIBLE -> when (config.openAiEndpointMode) {
                OpenAiEndpointMode.RESPONSES -> responses
                else -> chatCompletions
            }
            ProviderTypes.ANTHROPIC -> anthropic
            else -> error("不支持的 Provider 协议类型：${config.providerType}")
        }
}
