package io.github.mangi.eta.agent.runtime

/**
 * 后台任务通知在主对话里的一行摘要（AG-27）：去掉 Markdown 标记，取第一句，在句末或逗号处截断，
 * 不在句子中间断开；结论全文在子 Agent 的过程窗口里按 Markdown 显示。
 */
internal object BackgroundNoticeText {
    private const val SUMMARY_CHARS = 80
    private val HEADING_LINE = Regex("^\\s{0,3}#{1,6}\\s.*")
    private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val QUOTE_OR_LIST = Regex("(?m)^\\s*(?:>|[-*+]\\s|\\d+[.)]\\s)\\s*")
    private val EMPHASIS = Regex("\\*\\*|__|~~|(?<![\\w*])[*_](?=\\S)|(?<=\\S)[*_](?![\\w*])")
    private val CODE = Regex("`+")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val WHITESPACE = Regex("\\s+")
    private val CJK_ENDS = setOf('。', '！', '？', '；')
    private val ASCII_ENDS = setOf('.', '!', '?', ';')
    private val BREAKS = CJK_ENDS + setOf('，', '、', ' ')

    fun plain(text: String): String = text
        .replace(LINK, "$1")
        .replace(HEADING, "")
        .replace(QUOTE_OR_LIST, "")
        .replace(EMPHASIS, "")
        .replace(CODE, "")
        .replace(WHITESPACE, " ")
        .trim()

    /**
     * 第一句（最多 [SUMMARY_CHARS] 字）：开头的标题行在后面还有正文时跳过；英文句点只有后面是空白或结尾才算句末
     * （包名、路径里的点不算）；超长时在最后一个句读、逗号或空格处截断并加省略号。
     */
    fun summary(text: String): String {
        val lines = text.lines().filter(String::isNotBlank)
        val body = if (lines.size > 1 && HEADING_LINE.matches(lines.first())) lines.drop(1) else lines
        val plain = plain(body.joinToString("\n"))
        val end = sentenceEnd(plain)
        if (end in 0 until SUMMARY_CHARS) return plain.substring(0, end + 1)
        if (plain.length <= SUMMARY_CHARS) return plain
        val head = plain.take(SUMMARY_CHARS)
        val lastBreak = head.indexOfLast { it in BREAKS }
        return (if (lastBreak >= SUMMARY_CHARS / 2) head.substring(0, lastBreak) else head).trimEnd('，', '、', ' ') + "…"
    }

    private fun sentenceEnd(text: String): Int = text.indices.firstOrNull { index ->
        val char = text[index]
        char in CJK_ENDS || (char in ASCII_ENDS && (index == text.lastIndex || text[index + 1].isWhitespace()))
    } ?: -1
}
