package io.github.mangi.eta.agent.runtime

/** 搜索建议是短期展示数据，所有协议与展示入口共用同一 UTF-8 预算。 */
internal object SearchSuggestionsPayload {
    const val MAX_HTML_BYTES = 64 * 1024
    const val MAX_ROUND_BYTES = 128 * 1024
    const val MAX_FRAGMENTS = 5

    fun isValidHtml(html: String): Boolean =
        html.isNotBlank() && html.length <= MAX_HTML_BYTES &&
            html.toByteArray(Charsets.UTF_8).size <= MAX_HTML_BYTES
}

internal class SearchSuggestionsBudget {
    private val rounds = mutableMapOf<Int, MutableList<Pair<Int, String>>>()

    fun accept(round: Int, index: Int, html: String): Boolean {
        if (round < 0 || index < 0 || !SearchSuggestionsPayload.isValidHtml(html)) return false
        val fragments = rounds.getOrPut(round) { mutableListOf() }
        if (fragments.any { it.first == index && it.second == html }) return false
        if (fragments.size >= SearchSuggestionsPayload.MAX_FRAGMENTS) return false
        val bytes = html.toByteArray(Charsets.UTF_8).size + fragments.sumOf { it.second.toByteArray(Charsets.UTF_8).size }
        if (bytes > SearchSuggestionsPayload.MAX_ROUND_BYTES) return false
        fragments += index to html
        return true
    }

    fun clear() = rounds.clear()
}
