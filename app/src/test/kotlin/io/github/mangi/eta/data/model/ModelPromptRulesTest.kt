package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelPromptRulesTest {
    @Test
    fun exactMatchWinsThenTheLongestPrefixAndOnlyOneRuleApplies() {
        val rules = mapOf("gpt-*" to "a", "gpt-6*" to "b", "gpt-6.1-sol" to "c", "*" to "d")

        assertEquals("gpt-6.1-sol", ModelPromptRules.match(rules, "gpt-6.1-sol"))
        assertEquals("gpt-6*", ModelPromptRules.match(rules, "gpt-6.1-mini"))
        assertEquals("gpt-*", ModelPromptRules.match(rules, "gpt-4o"))
        assertEquals("*", ModelPromptRules.match(rules, "claude-opus"))
        assertNull(ModelPromptRules.match(rules - "*", "claude-opus"))
    }

    @Test
    fun theSameModelIdWrittenDifferentlyByEachProviderMatchesOneRule() {
        val rules = mapOf("gpt-6.1-sol" to "c")
        listOf(
            "gpt-6.1-sol",
            "openai/gpt-6.1-sol",
            "GPT-6.1-SOL",
            "gpt-6.1-sol-2026-10-01",
            "gpt-6.1-sol-20261001",
            "gpt-6.1-sol:free",
            "gpt-6.1-sol[1m]",
        ).forEach { modelId -> assertEquals(modelId, "gpt-6.1-sol", ModelPromptRules.match(rules, modelId)) }
    }

    @Test
    fun patternsAreNormalizedAndAllowOnlyOneTrailingWildcard() {
        assertEquals("gpt-*", ModelPromptRules.normalizePattern(" OpenAI/GPT-* "))
        assertEquals("gpt-6.1-sol", ModelPromptRules.normalizePattern("gpt-6.1-sol-2026-10-01"))
        assertEquals("*", ModelPromptRules.normalizePattern("*"))
        assertNull(ModelPromptRules.normalizePattern(" "))
        assertNull(ModelPromptRules.normalizePattern("g*pt"))
        assertNull(ModelPromptRules.normalizePattern("gpt-**"))
    }
}
