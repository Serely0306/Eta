package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReasoningEffortResolutionTest {
    private val lowToMax = ModelReasoningCapabilities(
        supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.MAX),
        mandatory = true,
    )

    @Test
    fun entryEffortWinsOverDefaultAndFallsBackToNearestLowerLevel() {
        val config = config(lowToMax)

        assertEquals(ReasoningEffort.HIGH, resolve(config, ReasoningEffort.HIGH, ReasoningEffort.LOW))
        assertEquals(ReasoningEffort.LOW, resolve(config, null, ReasoningEffort.LOW))
        // XHigh 不可选时取最接近的较低档。
        assertEquals(ReasoningEffort.HIGH, resolve(config, ReasoningEffort.XHIGH, ReasoningEffort.DEFAULT))
        // 不能关闭思考的模型选 Off 时落到服务商默认。
        assertEquals(ReasoningEffort.DEFAULT, resolve(config, ReasoningEffort.OFF, ReasoningEffort.DEFAULT))
        assertEquals(ReasoningEffort.DEFAULT, resolve(config, null, ReasoningEffort.DEFAULT))
    }

    @Test
    fun modelsWithoutReasoningKeepTheirConfig() {
        val config = config(capabilities = null)
        assertSame(config, RuntimeConfigRepository.withReasoningEffort(config, ReasoningEffort.HIGH, ReasoningEffort.MAX))
    }

    @Test
    fun onlyDefaultLevelModelsAlwaysUseProviderDefault() {
        val config = config(ModelReasoningCapabilities(defaultEnabled = true, mandatory = true))
        assertEquals(ReasoningEffort.DEFAULT, resolve(config, ReasoningEffort.HIGH, ReasoningEffort.HIGH))
    }

    @Test
    fun toggleEntriesKeepConfiguredEffortWhenThinkingIsOn() {
        assertEquals(ReasoningEffort.OFF, ReasoningEffort.forToggle(false, ReasoningEffort.HIGH))
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.forToggle(true, ReasoningEffort.HIGH))
        assertEquals(ReasoningEffort.DEFAULT, ReasoningEffort.forToggle(true, ReasoningEffort.OFF))
    }

    private fun resolve(
        config: AgentModelClient.ModelConfig,
        requested: ReasoningEffort?,
        defaultEffort: ReasoningEffort,
    ): ReasoningEffort {
        val resolved = RuntimeConfigRepository.withReasoningEffort(config, requested, defaultEffort)
        assertEquals(resolved.effectiveReasoningEffort.enablesReasoning, resolved.thinkingEnabled)
        return resolved.effectiveReasoningEffort
    }

    private fun config(capabilities: ModelReasoningCapabilities?) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "key",
        model = "test-model",
        systemPrompt = "",
        thinkingEnabled = capabilities != null,
        reasoningEffort = if (capabilities != null) ReasoningEffort.DEFAULT else ReasoningEffort.OFF,
        reasoningCapabilities = capabilities,
    )
}
