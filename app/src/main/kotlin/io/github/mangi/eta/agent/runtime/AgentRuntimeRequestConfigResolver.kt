package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient

internal object AgentRuntimeRequestConfigResolver {
    /** Runtime 侧替换入口模型配置时读取的设置。 */
    enum class RuntimeConfigSource {
        DEFAULT_MODEL,
        VENDOR_ASSISTANT_MODEL,
    }

    /**
     * Eta 语音入口使用默认模型；超级小爱使用厂商助手模型。小爱进程中的 RemotePreferences
     * 是进程启动时的快照，部分框架不会把后续修改推送到已运行的进程，因此由 Runtime 在收到
     * 请求时按当前设置解析。小布入口会按快速/深度思考覆盖推理设置，仍沿用入口配置。
     */
    fun runtimeConfigSource(request: AgentRuntimeWire.RunRequest): RuntimeConfigSource? =
        when (request.handoff?.source) {
            AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
            AgentRuntimeWire.PC_MCP_HANDOFF_SOURCE -> RuntimeConfigSource.DEFAULT_MODEL
            AgentRuntimeWire.XIAOAI_HANDOFF_SOURCE -> RuntimeConfigSource.VENDOR_ASSISTANT_MODEL
            else -> null
        }

    fun requiresRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        runtimeConfigSource(request) != null

    /**
     * 只用 Runtime 侧的模型与凭据替换入口配置；工具开关保留入口请求的值，
     * 随后仍由 AgentRuntimePolicy 与本地权限取交集。模型配置本身不携带工具开关，
     * 直接整体替换会让默认关闭的终端、敏感读取与敏感操作工具在语音入口始终不可用。
     */
    fun applyRuntimeConfig(
        request: AgentRuntimeWire.RunRequest,
        runtimeConfig: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest {
        if (!requiresRuntimeConfig(request)) return request
        val entry = request.config
        val config = runtimeConfig.copy(
            terminalTools = entry.terminalTools,
            browserTools = entry.browserTools,
            deviceDirectTools = entry.deviceDirectTools,
            deviceSensitiveReadTools = entry.deviceSensitiveReadTools,
            deviceSensitiveActionTools = entry.deviceSensitiveActionTools,
        )
        val handoff = request.handoff ?: return request.copy(config = config)
        val archivePayload = AgentExternalArchivePayload.from(handoff.payload)
        return request.copy(
            config = config,
            handoff = archivePayload?.let { payload ->
                handoff.copy(
                    payload = payload.copy(
                        thinkingEnabled = config.effectiveReasoningEffort.enablesReasoning,
                        reasoningEffort = config.effectiveReasoningEffort,
                    ).toJson(),
                )
            } ?: handoff,
        )
    }
}
