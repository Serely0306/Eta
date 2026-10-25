package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestionsPayloadTest {
    @Test
    fun utf8BudgetRejectsOversizedUnicodeAndBlankFragments() {
        assertTrue(SearchSuggestionsPayload.isValidHtml("x".repeat(SearchSuggestionsPayload.MAX_HTML_BYTES)))
        assertFalse(SearchSuggestionsPayload.isValidHtml("中".repeat(SearchSuggestionsPayload.MAX_HTML_BYTES / 3 + 1)))
        assertFalse(SearchSuggestionsPayload.isValidHtml(" "))
    }

    @Test
    fun roundsHaveIndependentByteAndFragmentBudgetsAndDuplicateDoesNotSpendBudget() {
        val budget = SearchSuggestionsBudget()
        val large = "x".repeat(SearchSuggestionsPayload.MAX_HTML_BYTES)
        assertTrue(budget.accept(1, 0, large))
        assertFalse(budget.accept(1, 0, large))
        assertTrue(budget.accept(1, 1, large))
        assertFalse(budget.accept(1, 2, "extra"))
        repeat(SearchSuggestionsPayload.MAX_FRAGMENTS) { assertTrue(budget.accept(2, it, "fragment-$it")) }
        assertFalse(budget.accept(2, 6, "extra"))
        assertFalse(budget.accept(-1, 0, "fragment"))
        assertFalse(budget.accept(3, -1, "fragment"))
        budget.clear()
        assertTrue(budget.accept(1, 0, large))
    }
}
