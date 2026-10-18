package io.github.mangi.eta.agent.mcp.server

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.agent.runtime.AgentRuntimeRequestConfigResolver
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository

/**
 * 电脑端委托任务可用的模型。MCP 页勾选了模型时，任务只能使用这些模型，回退链也只在其中切换，
 * 勾选的模型不可用时直接失败，不会落到默认模型；未勾选任何模型时沿用 Eta 的默认模型。
 * 强度：电脑端传入的优先，其次手机上为该模型设的强度，再次全局默认强度，按模型能力就近取档。
 */
internal object EtaMcpModelAccess {
    const val PAYLOAD_MODEL_ID = AgentRuntimeRequestConfigResolver.PAYLOAD_MODEL_ID
    const val PAYLOAD_REASONING_EFFORT = AgentRuntimeRequestConfigResolver.PAYLOAD_REASONING_EFFORT

    /** 已勾选的模型 id；为空表示不限制。 */
    fun allowedModelIds(): List<String> = EtaMcpPrivacySettings.load().taskModelIds

    suspend fun choices(): EtaMcpTaskManager.ModelChoices {
        val privacy = EtaMcpPrivacySettings.load()
        val defaultEffort = RuntimeConfigRepository.reasoningSettings().defaultEffort
        val thinkingAllowed = AgentRuntimePolicy.permissions(Prefs.localAgentPreferences()).thinking
        if (privacy.taskModelIds.isEmpty()) {
            val config = RuntimeConfigRepository.currentRuntimeConfig()
            return EtaMcpTaskManager.ModelChoices(
                restricted = false,
                options = emptyList(),
                defaultModel = config?.let {
                    option(it.providerId, "${it.providerName} / ${it.modelDisplayName.ifBlank { it.model }}", it, null, defaultEffort)
                },
                thinkingAllowed = thinkingAllowed,
            )
        }
        val providers = ProviderRepository.allProviders()
        val options = privacy.taskModelIds.mapNotNull { id ->
            providers.enabledModel(id)?.let { (provider, model) ->
                val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model)
                option(id, "${provider.name} / ${model.displayName}", config, privacy.taskModelEfforts[id], defaultEffort)
            }
        }
        return EtaMcpTaskManager.ModelChoices(restricted = true, options = options, thinkingAllowed = thinkingAllowed)
    }

    private fun option(
        id: String,
        label: String,
        config: AgentModelClient.ModelConfig,
        configured: ReasoningEffort?,
        defaultEffort: ReasoningEffort,
    ): EtaMcpTaskManager.ModelOption {
        val capabilities = config.reasoningCapabilities
        return EtaMcpTaskManager.ModelOption(
            id = id,
            label = label,
            efforts = capabilities?.selectableEfforts?.map { it.wireValue }.orEmpty(),
            defaultEffort = capabilities?.let {
                RuntimeConfigRepository.withReasoningEffort(config, configured, defaultEffort).effectiveReasoningEffort.wireValue
            },
        )
    }

    /** 电脑端请求中指定的模型 id。 */
    fun requestedModelId(request: AgentRuntimeWire.RunRequest): String? =
        adapterPayload(request)?.optString(PAYLOAD_MODEL_ID)?.trim()?.takeIf(String::isNotEmpty)

    private fun requestedEffort(request: AgentRuntimeWire.RunRequest): ReasoningEffort? =
        ReasoningEffort.fromWireValue(adapterPayload(request)?.optString(PAYLOAD_REASONING_EFFORT))

    private fun adapterPayload(request: AgentRuntimeWire.RunRequest) =
        request.handoff?.takeIf { it.source == AgentRuntimeWire.PC_MCP_HANDOFF_SOURCE }
            ?.let { AgentExternalArchivePayload.from(it.payload) }?.adapterPayload

    /**
     * Runtime 侧解析电脑端任务的模型：不限制时用默认模型；限制时只接受勾选范围内且仍启用的模型，
     * 否则返回 null 让请求失败。
     */
    suspend fun runtimeConfig(request: AgentRuntimeWire.RunRequest): AgentModelClient.ModelConfig? {
        val privacy = EtaMcpPrivacySettings.load()
        val defaultEffort = RuntimeConfigRepository.reasoningSettings().defaultEffort
        val requested = requestedEffort(request)
        if (privacy.taskModelIds.isEmpty()) {
            return RuntimeConfigRepository.currentRuntimeConfig()
                ?.let { RuntimeConfigRepository.withReasoningEffort(it, requested, defaultEffort) }
        }
        val modelId = requestedModelId(request)?.takeIf { it in privacy.taskModelIds } ?: return null
        val config = RuntimeConfigRepository.enabledRuntimeConfig(modelId) ?: return null
        return RuntimeConfigRepository.withReasoningEffort(config, requested ?: privacy.taskModelEfforts[modelId], defaultEffort)
    }

    /** 回退链的限制范围：电脑端任务在勾选了模型时只能回退到勾选的模型；其他入口不限制。 */
    fun fallbackScope(request: AgentRuntimeWire.RunRequest): Set<String>? {
        if (request.handoff?.source != AgentRuntimeWire.PC_MCP_HANDOFF_SOURCE) return null
        return allowedModelIds().takeIf { it.isNotEmpty() }?.toSet()
    }
}
