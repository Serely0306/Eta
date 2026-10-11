package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.AgentMemorySnapshot

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    val coreContent: String,
    val coreTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            coreContent = "",
            coreTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow)
        val core = extractCore(snapshot.content)
        val headings = snapshot.content.lineSequence()
            .filter { line -> HEADING.matches(line.trimEnd()) }
            .joinToString("\n")
            .take(MAX_HEADING_INDEX_CHARS)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            coreContent = core.take(coreBudget),
            coreTruncated = core.length > coreBudget,
            headingIndex = headings,
            coreBudgetChars = coreBudget,
        )
    }

    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
    }

    private fun extractCore(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val start = lines.indexOfFirst { it.trim() == CORE_HEADING }
        if (start < 0) return ""
        val end = ((start + 1) until lines.size)
            .firstOrNull { index -> lines[index].startsWith("# ") }
            ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private val HEADING = Regex("^#{1,2}\\s+.+$")
    private const val CORE_HEADING = "# 核心记忆"
    /** 窗口未知时用于按比例分配记忆、世界书等注入预算的保守基准，不参与压缩判定。 */
    const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_CORE_CHARS = 32_000
    private const val MAX_HEADING_INDEX_CHARS = 4_000
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}

/**
 * 同一会话的后续 run 复用系统提示中的记忆快照。模型自己的 memory_write 不再改写系统提示，
 * 否则下一轮请求的整段前缀缓存都会失效；写入前 revision 冲突会引导模型先读取最新内容。
 * 用户在设置中修改、上下文窗口变化或会话发生压缩（前缀本来就已改变）时重新取快照。
 * 只保存在 Runtime 进程内存中，进程重启后按当前文件重建。
 */
internal object AgentMemorySessionSnapshots {
    private const val MAX_SESSIONS = 32

    private data class Key(
        val contextWindow: Int?,
        val externalEditGeneration: Long,
        val compactionMarker: String,
    )

    private data class Entry(val key: Key, val context: AgentMemoryContext)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_SESSIONS
    }

    fun resolve(
        sessionId: String,
        contextWindow: Int?,
        externalEditGeneration: Long,
        history: List<AgentModelClient.ConversationMessage>,
        build: () -> AgentMemoryContext,
    ): AgentMemoryContext {
        val key = Key(contextWindow, externalEditGeneration, compactionMarker(history))
        synchronized(entries) {
            entries[sessionId]?.takeIf { it.key == key }?.let { return it.context }
        }
        val built = build()
        synchronized(entries) { entries[sessionId] = Entry(key, built) }
        return built
    }

    internal fun clear() = synchronized(entries) { entries.clear() }

    private fun compactionMarker(history: List<AgentModelClient.ConversationMessage>): String =
        history.lastOrNull { it.contextSummary }
            ?.let { "${it.messageId}:${it.summaryThroughUserTurn}:${it.content.length}" }
            .orEmpty()
}
