package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.config.Prefs
import kotlin.random.Random

/**
 * 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具，所以整次重发不会重复执行工具。
 * 次数取全局设置（默认 5，AG-26）；退避从 1 秒起翻倍并加抖动，服务商给了 Retry-After 时按它等。
 */
internal class AgentModelRetry(
    private val maxRetries: () -> Int = Prefs::modelRetryLimit,
    private val jitter: () -> Double = { Random.nextDouble(-JITTER_RATIO, JITTER_RATIO) },
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var callbackFailed = false
            try {
                val response = provider.complete(request, controller) { event ->
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (!classified.retryable) throw classified
                val limit = maxRetries()
                if (retries >= limit) throw exhausted(classified, limit)
                retries += 1
                val delayMs = delayMs(retries, classified.retryAfterMs, jitter())
                onEvent(
                    AgentEvent.ModelRetryScheduled(
                        round, retries, limit, delayMs.toInt(), classified.code, reasonText(classified),
                    ),
                )
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终思考摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    /**
     * 没有回合与事件的单次请求（上下文压缩）按同一策略重试；[onRetry] 只用于记录日志。
     */
    fun <T> retrying(
        controller: AgentRunController,
        onRetry: (attempt: Int, limit: Int, delayMs: Long, failure: AgentModelFailure) -> Unit = { _, _, _, _ -> },
        block: () -> T,
    ): T {
        var retries = 0
        while (true) {
            controller.throwIfCancelled()
            try {
                return block()
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (!classified.retryable) throw classified
                val limit = maxRetries()
                if (retries >= limit) throw exhausted(classified, limit)
                retries += 1
                val delayMs = delayMs(retries, classified.retryAfterMs, jitter())
                onRetry(retries, limit, delayMs, classified)
                waitBeforeRetry(controller, delayMs)
            }
        }
    }

    companion object {
        private const val BASE_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 30_000L
        private const val MAX_RETRY_AFTER_MS = 60_000L
        private const val JITTER_RATIO = 0.2
        private const val MAX_REASON_CHARS = 120

        /** 第 [retry] 次重试前的等待：服务商指定的优先（最多 60 秒），否则 1、2、4、8… 秒加抖动，最多 30 秒。 */
        fun delayMs(retry: Int, retryAfterMs: Long?, jitter: Double): Long {
            retryAfterMs?.let { return it.coerceIn(0, MAX_RETRY_AFTER_MS) }
            val base = (BASE_DELAY_MS shl (retry - 1).coerceIn(0, 15)).coerceAtMost(MAX_DELAY_MS)
            return (base * (1 + jitter)).toLong().coerceIn(0, MAX_DELAY_MS)
        }

        /** 显示给用户的原因：分类后的说明压成一行，最多 120 字。 */
        fun reasonText(failure: AgentModelFailure): String =
            failure.message.orEmpty().replace(WHITESPACE, " ").trim().take(MAX_REASON_CHARS).ifBlank { failure.code }

        private fun exhausted(classified: AgentModelFailure, limit: Int): AgentModelFailure = AgentModelFailure(
            classified.code, false,
            if (limit == 0) "${classified.message} 未开启失败重试，已保留此前完成的工具结果。"
            else "${classified.message} 已重试 $limit 次仍未恢复，已保留此前完成的工具结果。",
            classified,
        )

        private val WHITESPACE = Regex("\\s+")
    }
}
