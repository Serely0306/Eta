package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ModelFallbackPolicy
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelFallbackSettings
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ReasoningSettings
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.libxposed.service.XposedService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object RuntimeConfigRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun selectedProviderIdFlow() = SettingsDataStore.selectedProviderIdFlow()

    fun selectedModelIdFlow() = SettingsDataStore.selectedModelIdFlow()

    suspend fun selectedProvider(): ProviderSetting? {
        val settings = ProviderRepository.repairSelection()
        return settings.selectedProviderId?.let { ProviderRepository.providerById(it) }
    }

    suspend fun setSelectedProviderId(id: String?) {
        val settings = SettingsDataStore.settings()
        val provider = id?.let { ProviderRepository.providerById(it) }
            ?.takeIf { it.isEnabled }
        val activeModel = provider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = provider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val model = activeModel ?: provider?.selectedOrFirstModel(rememberedModelId)
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun setSelectedModelId(id: String?) {
        val provider = id?.let { ProviderRepository.providerByModelId(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == id && it.isEnabled }
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun currentRuntimeConfig(): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        val settings = ProviderRepository.repairSelection()
        val provider = settings.selectedProviderId?.let { ProviderRepository.providerById(it) } ?: return null
        val model = provider.selectedOrFirstModel(settings.selectedModelId) ?: return null
        return buildRuntimeConfig(provider, model)
    }

    /** 会话绑定的模型仍可用时使用它，否则回落到默认模型。 */
    suspend fun runtimeConfigFor(modelId: String?): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.allProviders().enabledModel(modelId)?.let { (provider, model) ->
            return buildRuntimeConfig(provider, model)
        }
        return currentRuntimeConfig()
    }

    /** 指定模型仍启用时返回其配置；与 [runtimeConfigFor] 不同，不可用时返回 null，不回落到默认模型。 */
    suspend fun enabledRuntimeConfig(modelId: String): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        return ProviderRepository.allProviders().enabledModel(modelId)?.let { (provider, model) ->
            buildRuntimeConfig(provider, model)
        }
    }

    fun vendorAssistantModelIdFlow() = SettingsDataStore.vendorAssistantModelIdFlow()

    suspend fun setVendorAssistantModelId(modelId: String?, service: XposedService?): Boolean {
        SettingsDataStore.setVendorAssistantModelId(modelId)
        return syncToRemotePreferences(service)
    }

    fun reasoningSettingsFlow() = SettingsDataStore.reasoningSettingsFlow()

    suspend fun reasoningSettings(): ReasoningSettings = SettingsDataStore.reasoningSettings()

    /** 厂商助手的强度变化要刷新 RemotePreferences 中的快照。 */
    suspend fun setReasoningSettings(settings: ReasoningSettings, service: XposedService?): Boolean {
        SettingsDataStore.setReasoningSettings(settings)
        return syncToRemotePreferences(service)
    }

    /**
     * RemotePreferences 中的模型快照只由厂商助手入口（小爱、小布）读取；Eta 自有入口在 Runtime
     * 侧按各自的设置解析。单独选择的模型停用或删除时回落到默认模型。
     */
    suspend fun vendorAssistantRuntimeConfig(): AgentModelClient.ModelConfig? =
        entryRuntimeConfig(SettingsDataStore.vendorAssistantModelId(), SettingsDataStore.reasoningSettings().vendorEffort)

    /** Eta 语音入口（电源键、助理浮层）：单独选的模型与强度，模型不可用时回落到默认模型。 */
    suspend fun voiceRuntimeConfig(): AgentModelClient.ModelConfig? {
        val settings = SettingsDataStore.reasoningSettings()
        return entryRuntimeConfig(settings.voiceModelId, settings.voiceEffort)
    }

    /**
     * 入口单独选择的模型与强度：模型为空或不可用时用默认模型，强度为空时用该模型的默认强度。
     * 自动任务与厂商助手共用这一规则。
     */
    suspend fun entryRuntimeConfig(modelId: String?, effort: ReasoningEffort?): AgentModelClient.ModelConfig? {
        val config = runtimeConfigFor(modelId) ?: return null
        return withReasoningEffort(config, effort)
    }

    /**
     * 入口单独设置的强度优先，其次 [defaultEffort]（默认是模型自己的默认强度，见 [buildRuntimeConfig]）；
     * 按模型能力就近取档。模型不支持思考时保持原配置。思考权限关闭时仍由 AgentRuntimePolicy 统一改为 Off。
     */
    fun withReasoningEffort(
        config: AgentModelClient.ModelConfig,
        requested: ReasoningEffort?,
        defaultEffort: ReasoningEffort = config.effectiveReasoningEffort,
    ): AgentModelClient.ModelConfig {
        val capabilities = config.reasoningCapabilities ?: return config
        val effort = capabilities.normalize(requested ?: defaultEffort)
        return config.copy(reasoningEffort = effort, thinkingEnabled = effort.enablesReasoning)
    }

    fun modelFallbackFlow() = SettingsDataStore.modelFallbackFlow()

    suspend fun setModelFallback(settings: ModelFallbackSettings) = SettingsDataStore.setModelFallback(settings)

    /**
     * 按回退链顺序构造备用模型配置；停用、删除或与主模型相同的条目跳过。
     * 工具开关沿用主模型配置，由 Runtime 再与本地权限取交集。强度用该条目单独设置的值，
     * 没有设置时跟随主模型本次的强度，都按备用模型的能力就近取档。
     * [allowedModelIds] 非空时只保留其中的模型（电脑端任务限定了可用模型时使用）。
     */
    suspend fun modelFallbackPolicy(
        primary: AgentModelClient.ModelConfig,
        allowedModelIds: Set<String>? = null,
    ): ModelFallbackPolicy {
        val settings = SettingsDataStore.modelFallback()
        val modelIds = settings.modelIds.filter { allowedModelIds == null || it in allowedModelIds }
        if (modelIds.isEmpty()) return ModelFallbackPolicy.NONE
        ProviderRepository.ensureBuiltInsMerged()
        val providers = ProviderRepository.allProviders()
        val configs = modelIds.mapNotNull { id ->
            providers.enabledModel(id)?.let { (provider, model) ->
                withReasoningEffort(
                    buildRuntimeConfig(provider, model),
                    requested = settings.efforts[id],
                    defaultEffort = primary.effectiveReasoningEffort,
                )
            }
        }.filterNot { it.providerId == primary.providerId && it.model == primary.model }
            .map { config ->
                config.copy(
                    terminalTools = primary.terminalTools,
                    browserTools = primary.browserTools,
                    deviceDirectTools = primary.deviceDirectTools,
                    deviceSensitiveReadTools = primary.deviceSensitiveReadTools,
                    deviceSensitiveActionTools = primary.deviceSensitiveActionTools,
                    autoCompactionEnabled = primary.autoCompactionEnabled,
                )
            }
        return ModelFallbackPolicy(configs, onContentFilter = settings.onContentFilter)
    }

    suspend fun oauthRuntimeConfigFor(
        providerId: String,
        authMode: String,
        modelName: String,
    ): AgentModelClient.ModelConfig? {
        if (!ProviderAuthModes.isOAuth(authMode)) return null
        val provider = ProviderRepository.providerById(providerId)
            ?.takeIf { it.isEnabled && it.authMode == authMode } ?: return null
        val model = provider.models.firstOrNull { it.isEnabled && it.modelId == modelName } ?: return null
        return buildRuntimeConfig(provider, model)
    }

    suspend fun syncToRemotePreferences(service: XposedService?): Boolean {
        val prefs = Prefs.remotePreferencesForUi(service) ?: return false
        val config = vendorAssistantRuntimeConfig() ?: return clearRuntimeConfig(prefs)
        return writeRuntimeConfig(prefs, config)
    }

    suspend fun ensureDefaults(service: XposedService?) {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.repairSelection()
        syncToRemotePreferences(service)
    }

    fun runtimeConfigJson(config: AgentModelClient.ModelConfig): String =
        json.encodeToString(if (config.authMode == ProviderAuthModes.API_KEY) config else config.copy(apiKey = ""))

    fun buildRuntimeConfig(provider: ProviderSetting, model: Model): AgentModelClient.ModelConfig {
        val systemPrompt = provider.systemPrompt.orEmpty().trim()
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val profile = ProviderConnectionProfile.forAuthMode(provider.authMode)
        val endpointMode = profile?.endpointMode ?: provider.endpointMode()
        val reasoningCapabilities = reasoningCapabilities(provider, model)
        return AgentModelClient.ModelConfig(
            providerId = provider.id,
            providerName = provider.name,
            providerType = profile?.providerType ?: provider.runtimeProviderType,
            providerSourceType = sourceType,
            baseUrl = profile?.baseUrl ?: provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim().takeIf { provider.authMode == ProviderAuthModes.API_KEY }.orEmpty(),
            authMode = provider.authMode,
            model = model.modelId.trim(),
            modelDisplayName = model.displayName.trim(),
            contextWindow = model.effectiveContextWindow,
            autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
            systemPrompt = systemPrompt,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
            openAiEndpointMode = endpointMode,
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            promptCacheKeyEnabled = provider.promptCacheKeyEnabled,
            reasoningSummary = provider.reasoningSummary,
            thinkingEnabled = reasoningCapabilities != null,
            // 模型编辑里设的默认强度；没设时为服务商默认（不发送强度参数）。
            reasoningEffort = reasoningCapabilities?.normalize(model.defaultReasoningEffort ?: ReasoningEffort.DEFAULT)
                ?: ReasoningEffort.OFF,
            reasoningCapabilities = reasoningCapabilities,
            customHeaders = provider.customHeaders + model.customHeaders,
            customBody = provider.customBody + model.customBody,
        )
    }

    fun reasoningCapabilities(provider: ProviderSetting, model: Model): ModelReasoningCapabilities? {
        if (provider is GeminiProviderSetting) {
            return GeminiModelCapabilities.requestReasoningCapabilities(model.modelId)
        }
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val inferOpenAiCatalog = sourceType == io.github.mangi.eta.data.model.ProviderSourceTypes.CUSTOM &&
            provider.endpointMode() == OpenAiEndpointMode.RESPONSES
        return ReasoningCapabilityResolver.resolve(
            sourceType = if (inferOpenAiCatalog) {
                io.github.mangi.eta.data.model.ProviderSourceTypes.OPENAI
            } else {
                sourceType
            },
            model = model,
            inferExactCatalogModel = inferOpenAiCatalog,
        )
    }

    private fun ProviderSetting.endpointMode(): String = when (this) {
        is OpenAiCompatibleProviderSetting -> endpointMode
        is CustomProviderSetting -> endpointMode
        is AnthropicProviderSetting, is GeminiProviderSetting -> ""
    }

    private fun writeRuntimeConfig(
        prefs: SharedPreferences,
        config: AgentModelClient.ModelConfig,
    ): Boolean =
        runCatching {
            prefs.edit()
                .putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, runtimeConfigJson(config))
                .commit()
        }.getOrDefault(false)

    private fun clearRuntimeConfig(prefs: SharedPreferences): Boolean =
        runCatching {
            prefs.edit()
                .remove(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
                .commit()
        }.getOrDefault(false)
}
