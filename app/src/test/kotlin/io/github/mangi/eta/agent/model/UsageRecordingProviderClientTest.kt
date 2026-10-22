package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.db.ModelUsageRecordEntity
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.usage.ModelUsageSummary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageRecordingProviderClientTest {
    private val records = mutableListOf<ModelUsageRecordEntity>()

    private class FakeProvider(private val behavior: (onEvent: (ProviderEvent) -> Unit) -> ProviderResponse) : AgentProviderClient {
        override val id = "fake"
        override val capabilities = ProviderCapabilities(EndpointKind.RESPONSES, true, true, true, true, true, true)
        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse = behavior(onEvent)
    }

    @Test
    fun recordsLastUsageOfEachRequestWithRunContext() {
        var clock = 1_000L
        val client = UsageRecordingProviderClient(FakeProvider { onEvent ->
            onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100, promptTokens = 1_000, cachedTokens = 900)))
            onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100, promptTokens = 1_000, cachedTokens = 900,
                cacheWriteTokens = 50, outputTokens = 40, reasoningTokens = 12)))
            clock += 250
            ProviderResponse(JSONObject().put("content", "ok"))
        }, now = { clock }, sink = records::add)

        ModelUsageScope.run(ModelUsageContext("pc_mcp", "run-1", subagentIndex = 2, sessionId = "pc_mcp:task")) {
            client.complete(request(ProviderRequestPurpose.CHAT), AgentRunController())
        }

        val record = records.single()
        assertEquals("pc_mcp", record.source)
        assertEquals("run-1", record.runId)
        assertEquals("pc_mcp:task", record.sessionId)
        assertEquals(2, record.subagentIndex)
        assertEquals("chat", record.purpose)
        assertEquals(ModelUsageRecordEntity.STATUS_OK, record.status)
        assertEquals("responses", record.endpoint)
        assertEquals("high", record.reasoningEffort)
        assertEquals(250L, record.durationMs)
        assertTrue(record.hasUsage)
        assertEquals(1_000L, record.promptTokens)
        assertEquals(900L, record.cachedTokens)
        assertEquals(50L, record.cacheWriteTokens)
        assertEquals(40L, record.outputTokens)
        assertEquals(12L, record.reasoningTokens)
    }

    @Test
    fun requestsWithoutUsageFailuresAndCancellationsAreStillRecorded() {
        val noUsage = UsageRecordingProviderClient(FakeProvider { ProviderResponse(JSONObject()) }, sink = records::add)
        noUsage.complete(request(ProviderRequestPurpose.COMPACTION), AgentRunController())

        val failing = UsageRecordingProviderClient(FakeProvider { onEvent ->
            onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 30)))
            error("upstream 500")
        }, sink = records::add)
        runCatching { failing.complete(request(ProviderRequestPurpose.CHAT), AgentRunController()) }

        val cancelled = UsageRecordingProviderClient(FakeProvider { throw AgentRunCancelledException() }, sink = records::add)
        runCatching { cancelled.complete(request(ProviderRequestPurpose.CHAT), AgentRunController()) }

        assertEquals(3, records.size)
        assertFalse(records[0].hasUsage)
        assertEquals("compaction", records[0].purpose)
        assertEquals("", records[0].source)
        assertNull(records[0].subagentIndex)
        assertEquals(ModelUsageRecordEntity.STATUS_FAILED, records[1].status)
        assertEquals(30L, records[1].promptTokens)
        assertEquals(ModelUsageRecordEntity.STATUS_CANCELLED, records[2].status)

        val summary = ModelUsageSummary.of(records)
        assertEquals(3, summary.requests)
        assertEquals(2, summary.requestsWithoutUsage)
        assertEquals(30L, summary.promptTokens)
    }

    @Test
    fun subscriptionRequestsRecordTheAuthModeAsEndpoint() {
        val client = UsageRecordingProviderClient(FakeProvider { ProviderResponse(JSONObject()) }, sink = records::add)
        val base = request(ProviderRequestPurpose.CHAT)
        client.complete(
            base.copy(config = base.config.copy(apiKey = "", authMode = ProviderAuthModes.OPENAI_CHATGPT)),
            AgentRunController(),
        )
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, records.single().endpoint)
    }

    private fun request(purpose: ProviderRequestPurpose) = ProviderRequest(
        config = AgentModelClient.ModelConfig(
            providerId = "relay-a",
            providerName = "Relay A",
            baseUrl = "https://relay.example.com/v1",
            apiKey = "key",
            model = "gpt-6-astra",
            modelDisplayName = "GPT-6 Astra",
            systemPrompt = "",
            openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
            thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH,
        ),
        messages = JSONArray(),
        tools = JSONArray(),
        purpose = purpose,
    )
}
