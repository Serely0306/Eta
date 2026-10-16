package io.github.mangi.eta.agent.mcp.server

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository

/**
 * 电脑端委托任务可用的模型。MCP 页勾选了模型时，任务只能使用这些模型，回退链也只在其中切换，
 * 勾选的模型不可用时直接失败，不会落到默认模型；未勾选任何模型时沿用 Eta 的默认模型。
 */
internal object EtaMcpModelAccess {
    const val PAYLOAD_MODEL_ID = "modelId"

    /** 已勾选的模型 id；为空表示不限制。 */
    fun allowedModelIds(): List<String> = EtaMcpPrivacySettings.load().taskModelIds

    suspend fun choices(): EtaMcpTaskManager.ModelChoices {
        val allowed = allowedModelIds()
        if (allowed.isEmpty()) return EtaMcpTaskManager.ModelChoices.DEFAULT_MODEL
        val providers = ProviderRepository.allProviders()
        val options = allowed.mapNotNull { id ->
            providers.enabledModel(id)?.let { (provider, model) ->
                EtaMcpTaskManager.ModelOption(id, "${provider.name} / ${model.displayName}")
            }
        }
        return EtaMcpTaskManager.ModelChoices(restricted = true, options = options)
    }

    /** 电脑端请求中指定的模型 id。 */
    fun requestedModelId(request: AgentRuntimeWire.RunRequest): String? {
        val handoff = request.handoff?.takeIf { it.source == AgentRuntimeWire.PC_MCP_HANDOFF_SOURCE } ?: return null
        return AgentExternalArchivePayload.from(handoff.payload)?.adapterPayload
            ?.optString(PAYLOAD_MODEL_ID)?.trim()?.takeIf(String::isNotEmpty)
    }

    /**
     * Runtime 侧解析电脑端任务的模型：不限制时用默认模型；限制时只接受勾选范围内且仍启用的模型，
     * 否则返回 null 让请求失败。
     */
    suspend fun runtimeConfig(request: AgentRuntimeWire.RunRequest): AgentModelClient.ModelConfig? {
        val allowed = allowedModelIds()
        if (allowed.isEmpty()) return RuntimeConfigRepository.currentRuntimeConfig()
        val modelId = requestedModelId(request)?.takeIf { it in allowed } ?: return null
        return RuntimeConfigRepository.enabledRuntimeConfig(modelId)
    }

    /** 回退链的限制范围：电脑端任务在勾选了模型时只能回退到勾选的模型；其他入口不限制。 */
    fun fallbackScope(request: AgentRuntimeWire.RunRequest): Set<String>? {
        if (request.handoff?.source != AgentRuntimeWire.PC_MCP_HANDOFF_SOURCE) return null
        return allowedModelIds().takeIf { it.isNotEmpty() }?.toSet()
    }
}
