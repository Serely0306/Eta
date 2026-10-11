package io.github.mangi.eta.hook.xiaoai

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference

internal object XiaoAiTakeoverPolicy {
    private const val AGENT_PREFIX = "/agent "
    private const val AGENT_ENCODED_PREFIX = "/agent%20"
    private const val IMAGE_ONLY_PLACEHOLDER = "blank"
    private const val IMAGE_ONLY_PROMPT = "请分析这张图片"

    data class Decision(
        val prompt: String,
    )

    fun isSupportedVersion(versionCode: Long): Boolean =
        XiaoAiTargets.forVersion(versionCode) != null

    fun matchesOutboundEvent(fullName: String): Boolean =
        fullName == "Nlp.Request"

    fun decide(
        query: String,
        hasImage: Boolean,
        customModelEnabled: Boolean,
        requirePrefix: Boolean,
    ): Decision? {
        if (!customModelEnabled) return null
        val trimmed = query.trim()
        val prefixed = when {
            trimmed.startsWith(AGENT_PREFIX) ->
                trimmed.removePrefix(AGENT_PREFIX).trim()

            trimmed.startsWith(AGENT_ENCODED_PREFIX) ->
                trimmed.removePrefix(AGENT_ENCODED_PREFIX).trim()

            else -> null
        }
        if (requirePrefix && prefixed == null) return null

        val prompt = (prefixed ?: trimmed).let { candidate ->
            if (
                hasImage &&
                (candidate.isBlank() || candidate.equals(IMAGE_ONLY_PLACEHOLDER, ignoreCase = true))
            ) {
                IMAGE_ONLY_PROMPT
            } else {
                candidate
            }
        }
        return prompt.takeIf(String::isNotBlank)?.let(::Decision)
    }
}

/** 超级小爱的混淆目标随版本变化；只收录逐项核对过签名的版本，未知版本不安装业务 Hook。 */
internal data class XiaoAiTargets(
    val engineClass: String,
    val engineSendMethod: String,
    val engineClearMethod: String,
    val asrProcessorClass: String,
    val agentActionManagerClass: String,
    val imageIdStoreClass: String,
    val floatManagerClass: String,
    val flowToastCardClass: String,
    val ttsPlayerClass: String,
) {
    companion object {
        private val BY_VERSION = mapOf(
            // 7.13.32.0016：已通过小米真机验证。
            507013032L to XiaoAiTargets(
                engineClass = "y00.r0",
                engineSendMethod = "C0",
                engineClearMethod = "V",
                asrProcessorClass = "z10.a",
                agentActionManagerClass = "kh0.s0",
                imageIdStoreClass = "k00.y0",
                floatManagerClass = "com.xiaomi.voiceassistant.widget.d",
                flowToastCardClass =
                    "com.xiaomi.voiceassistant.instruction.card.stream.FlowTemplateToastCard",
                ttsPlayerClass = "la0.n1",
            ),
            // 8.2.65.4714（HyperOS 4）：按 DEX 核对签名与语义；新版 V() 只是 forceInterrupt，会话清理为 W()。
            508002065L to XiaoAiTargets(
                engineClass = "p31.o0",
                engineSendMethod = "D0",
                engineClearMethod = "W",
                asrProcessorClass = "s41.c",
                agentActionManagerClass = "fo1.x0",
                imageIdStoreClass = "com.xiaomi.voiceassist.baselibrary.utils.i1",
                floatManagerClass = "com.xiaomi.voiceassistant.widget.s1",
                flowToastCardClass = "com.xiaomi.voiceassistant.instruction.card.stream.b",
                ttsPlayerClass = "xf1.v1",
            ),
        )

        fun forVersion(versionCode: Long): XiaoAiTargets? = BY_VERSION[versionCode]
    }
}

internal class XiaoAiRunSlot<T : Any> {
    private val current = AtomicReference<T?>()

    fun get(): T? = current.get()

    fun replace(value: T): T? = current.getAndSet(value)

    fun clear(value: T): Boolean = current.compareAndSet(value, null)
}

internal class XiaoAiRendererSlot<T : Any> {
    private val attached = AtomicReference<T?>()

    fun attach(value: T): T? = attached.getAndSet(value)

    fun detach(): T? = attached.getAndSet(null)
}

internal class XiaoAiQueryCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class QueryInfo(
        val dialogId: String,
        val query: String,
        val imageFileId: String?,
        val documentInput: Boolean,
        val capturedAt: Long,
    )

    private val entries = LinkedHashMap<String, QueryInfo>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun put(
        dialogId: String,
        query: String,
        imageFileId: String?,
        documentInput: Boolean = false,
    ): Boolean {
        val normalizedId = dialogId.trim()
        if (normalizedId.isBlank()) return false
        val now = clock()
        pruneExpired(now)
        entries.remove(normalizedId)
        entries[normalizedId] = QueryInfo(
            dialogId = normalizedId,
            query = query,
            imageFileId = imageFileId?.trim()?.takeIf(String::isNotBlank),
            documentInput = documentInput,
            capturedAt = now,
        )
        trimToCapacity()
        return true
    }

    @Synchronized
    fun take(dialogId: String): QueryInfo? {
        val now = clock()
        pruneExpired(now)
        return entries.remove(dialogId.trim())
    }

    /**
     * Nlp.Request 的 Event ID 由小爱在出站时重新生成，不能假设它等于 setQueryInfo 的
     * dialogId。优先按 ID 取值，再按同一轮查询文本关联；关联成功后同样消费一次。
     */
    @Synchronized
    fun takeMatching(
        eventId: String,
        eventQuery: String,
    ): QueryInfo? {
        val now = clock()
        pruneExpired(now)
        entries.remove(eventId.trim())?.let { return it }

        val normalizedQuery = eventQuery.trim()
        if (normalizedQuery.isBlank()) return null
        val matchingKey = entries.entries
            .lastOrNull { (_, value) -> value.query.trim() == normalizedQuery }
            ?.key
            ?: return null
        return entries.remove(matchingKey)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int {
        pruneExpired(clock())
        return entries.size
    }

    private fun pruneExpired(now: Long) {
        entries.entries.removeIf { (_, value) -> now - value.capturedAt > ttlMillis }
    }

    private fun trimToCapacity() {
        while (entries.size > capacity) {
            val oldest = entries.entries.firstOrNull()?.key ?: return
            entries.remove(oldest)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_TTL_MILLIS = 120_000L
    }
}

internal class XiaoAiTurnTracker(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Turn(
        val dialogId: String,
        val query: String,
        val hasImage: Boolean,
        val documentInput: Boolean,
        val capturedAt: Long,
    )

    private var current: Turn? = null

    init {
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun capture(
        dialogId: String,
        query: String,
        hasImage: Boolean = false,
        documentInput: Boolean = false,
    ) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return
        current = Turn(
            dialogId = dialogId.trim(),
            query = normalizedQuery,
            hasImage = hasImage,
            documentInput = documentInput,
            capturedAt = clock(),
        )
    }

    @Synchronized
    fun latest(): Turn? {
        val turn = current ?: return null
        if (clock() - turn.capturedAt > ttlMillis) {
            current = null
            return null
        }
        return turn
    }

    @Synchronized
    fun clear() {
        current = null
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 15_000L
    }
}

internal class XiaoAiRecentIds(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = LinkedHashMap<String, Long>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun add(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        val now = clock()
        pruneExpired(now)
        entries.remove(normalized)
        entries[normalized] = now
        while (entries.size > capacity) {
            entries.remove(entries.entries.firstOrNull()?.key ?: break)
        }
    }

    @Synchronized
    fun contains(id: String): Boolean {
        val now = clock()
        pruneExpired(now)
        return entries.containsKey(id.trim())
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun pruneExpired(now: Long) {
        entries.entries.removeIf { (_, createdAt) -> now - createdAt > ttlMillis }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_TTL_MILLIS = 120_000L
    }
}
