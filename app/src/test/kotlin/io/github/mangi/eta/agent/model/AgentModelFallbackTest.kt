package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelFallbackTest {
    private val anthropic = AgentModelClient.ModelConfig(
        providerType = ProviderTypes.ANTHROPIC,
        baseUrl = "https://example.invalid",
        apiKey = "key",
        model = "claude",
        systemPrompt = "",
        reasoningEffort = ReasoningEffort.DEFAULT,
    )

    private fun toolTurn() = JSONArray()
        .put(AgentConversationCodec.userTextMessage("开始"))
        .put(JSONObject().put("role", "assistant").put("content", "")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
                .put("function", JSONObject().put("name", "terminal").put("arguments", "{}")))))
        .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "ok"))

    @Test
    fun onlyModelSideFailuresTriggerFallback() {
        val policy = ModelFallbackPolicy(listOf(anthropic))
        assertTrue(policy.shouldFallBack(AgentModelFailure("HTTP_503", false, "unavailable")))
        assertTrue(policy.shouldFallBack(AgentModelFailure("HTTP_401", false, "auth")))
        assertTrue(policy.shouldFallBack(IOException("tls")))
        assertFalse(policy.shouldFallBack(AgentModelFailure("CONTEXT_OVERFLOW", false, "overflow")))
        assertFalse(policy.shouldFallBack(AgentModelFailure("MODEL_CONTENT_FILTER", false, "blocked")))
        assertTrue(policy.copy(onContentFilter = true).shouldFallBack(AgentModelFailure("MODEL_CONTENT_FILTER", false, "blocked")))
        assertFalse(policy.shouldFallBack(IllegalStateException("bug")))
    }

    @Test
    fun anthropicFallbackDropsThinkingInsideAToolTurnOrIsSkippedWhenMandatory() {
        val prepared = ModelFallbackPolicy.prepare(anthropic, toolTurn())
        assertEquals(ReasoningEffort.OFF, prepared?.effectiveReasoningEffort)

        val mandatory = anthropic.copy(reasoningCapabilities = ModelReasoningCapabilities(mandatory = true))
        assertNull(ModelFallbackPolicy.prepare(mandatory, toolTurn()))

        // 回合开头没有待续的工具调用时保留思考。
        val fresh = JSONArray().put(AgentConversationCodec.userTextMessage("开始"))
        assertSame(anthropic, ModelFallbackPolicy.prepare(anthropic, fresh))
        val openAi = anthropic.copy(providerType = ProviderTypes.OPENAI_COMPATIBLE)
        assertSame(openAi, ModelFallbackPolicy.prepare(openAi, toolTurn()))
    }

    @Test
    fun providerStateOfThePreviousModelIsStripped() {
        val messages = toolTurn()
        val assistant = messages.getJSONObject(1)
        AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray().put(JSONObject().put("type", "thinking")))
        ResponsesEphemeralState.attachOutputItems(assistant, JSONArray().put(JSONObject().put("type", "reasoning")))
        ModelFallbackPolicy.stripProviderState(messages)
        assertNull(AnthropicEphemeralState.contentBlocks(assistant))
        assertNull(ResponsesEphemeralState.outputItems(assistant))
        assertEquals("c1", AgentConversationCodec.parseToolCalls(assistant).single().id)
    }
}
