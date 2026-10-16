package io.github.mangi.eta.hook.xiaoai

import java.util.LinkedHashMap

internal data class XiaoAiFinalAsr(val dialogId: String, val query: String) {
    companion object {
        fun from(dialogId: String, isFinal: Boolean, results: Iterable<String>): XiaoAiFinalAsr? {
            if (!isFinal) return null
            val query = results.firstOrNull()?.trim().orEmpty()
            return query.takeIf(String::isNotBlank)?.let { XiaoAiFinalAsr(dialogId.trim(), it) }
        }
    }
}

internal object XiaoAiVoiceInputPolicy {
    fun canClaim(
        enginePrefix: String,
        requestMode: Int?,
        hasImage: Boolean,
        documentInput: Boolean,
    ): Boolean = enginePrefix == "main" &&
        requestMode in setOf(3, 7, 19, 23) && !hasImage && !documentInput
}

internal object XiaoAiEtaOutput {
    private val depth = ThreadLocal.withInitial { 0 }

    fun isActive(): Boolean = depth.get() > 0

    fun <T> run(block: () -> T): T {
        val previousDepth = depth.get()
        depth.set(previousDepth + 1)
        return try {
            block()
        } finally {
            if (previousDepth == 0) depth.remove() else depth.set(previousDepth)
        }
    }
}

internal class XiaoAiVoiceTakeoverState(
    private val capacity: Int = 64,
    private val ttlMillis: Long = 120_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val claimedDialogs = LinkedHashMap<String, Long>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    /**
     * 认领并启动一次。[start] 在锁内执行以保证同一 dialog 只启动一次；过滤器对每条指令都会取同一把锁，
     * 所以 [start] 只能做非阻塞的登记与派发，不得等待 IPC 或主线程。
     */
    @Synchronized
    fun tryClaim(dialogId: String, start: () -> Boolean): Boolean {
        val normalizedId = dialogId.trim()
        if (normalizedId.isBlank()) return false
        val now = clock()
        pruneExpired(now)
        if (claimedDialogs.containsKey(normalizedId)) return true
        if (!start()) return false
        claimedDialogs[normalizedId] = now
        while (claimedDialogs.size > capacity) {
            claimedDialogs.remove(claimedDialogs.entries.first().key)
        }
        return true
    }

    @Synchronized
    fun isClaimed(dialogId: String): Boolean {
        pruneExpired(clock())
        return claimedDialogs.containsKey(dialogId.trim())
    }

    @Synchronized
    fun shouldSuppress(dialogId: String, fullName: String): Boolean {
        if (XiaoAiEtaOutput.isActive()) return false
        if (!isClaimed(dialogId) || fullName.isBlank()) return false
        return fullName !in CAPTURE_INSTRUCTIONS
    }

    private fun pruneExpired(now: Long) {
        claimedDialogs.entries.removeIf { (_, claimedAt) -> now - claimedAt > ttlMillis }
    }

    private companion object {
        val CAPTURE_INSTRUCTIONS = setOf(
            "SpeechRecognizer.RecognizeResult",
            "SpeechRecognizer.StopCapture",
            "System.TruncationNotification",
            "System.Heartbeat",
            "Offline.CloudStop",
        )
    }
}
