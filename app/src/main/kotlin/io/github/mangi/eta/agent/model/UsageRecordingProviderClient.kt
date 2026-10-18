package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.db.ModelUsageRecordEntity
import io.github.mangi.eta.data.model.ProviderTypes

/**
 * 所有模型请求的统一出口：每次请求结束（成功、失败或取消）都记一条用量，包括压缩、改写、子 Agent 与转写润色。
 * 同一请求内的多次上报取最后一次（Anthropic 先报输入、再报累计输出）。
 */
internal class UsageRecordingProviderClient(
    private val delegate: AgentProviderClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val sink: (ModelUsageRecordEntity) -> Unit,
) : AgentProviderClient by delegate {
    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        val context = ModelUsageScope.current()
        val startedAt = now()
        var usage: AgentTokenUsage? = null
        var status = ModelUsageRecordEntity.STATUS_FAILED
        try {
            val response = delegate.complete(request, runController) { event ->
                if (event is ProviderEvent.Usage) usage = event.usage
                onEvent(event)
            }
            status = ModelUsageRecordEntity.STATUS_OK
            return response
        } catch (cancelled: AgentRunCancelledException) {
            status = ModelUsageRecordEntity.STATUS_CANCELLED
            throw cancelled
        } finally {
            runCatching { sink(record(request, context, usage, status, startedAt)) }
        }
    }

    private fun record(
        request: ProviderRequest,
        context: ModelUsageContext?,
        usage: AgentTokenUsage?,
        status: String,
        startedAt: Long,
    ): ModelUsageRecordEntity {
        val config = request.effectiveConfig
        return ModelUsageRecordEntity(
            createdAt = startedAt,
            durationMs = (now() - startedAt).coerceAtLeast(0),
            source = context?.source.orEmpty(),
            runId = context?.runId.orEmpty(),
            purpose = request.purpose.name.lowercase(),
            subagentIndex = context?.subagentIndex,
            providerId = config.providerId,
            providerName = config.providerName,
            model = config.model,
            modelDisplayName = config.modelDisplayName.ifBlank { config.model },
            endpoint = if (config.providerType == ProviderTypes.ANTHROPIC) ENDPOINT_ANTHROPIC else config.openAiEndpointMode,
            reasoningEffort = config.effectiveReasoningEffort.wireValue,
            status = status,
            hasUsage = usage != null,
            promptTokens = (usage?.promptTokens ?: usage?.inputTokens ?: 0).toLong(),
            cachedTokens = (usage?.cachedTokens ?: 0).toLong(),
            cacheWriteTokens = (usage?.cacheWriteTokens ?: 0).toLong(),
            outputTokens = (usage?.outputTokens ?: 0).toLong(),
            reasoningTokens = (usage?.reasoningTokens ?: 0).toLong(),
            sessionId = context?.sessionId.orEmpty(),
        )
    }

    private companion object {
        const val ENDPOINT_ANTHROPIC = "anthropic"
    }
}
