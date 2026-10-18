package io.github.mangi.eta.data.usage

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ModelUsageRecordEntity
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 模型请求的用量账本。写入在单独的线程上排队，不阻塞流式输出；进程没有初始化账本（例如 Hook 进程）时不记录。
 */
internal object ModelUsageLedger {
    @Volatile
    private var appContext: Context? = null
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-usage-ledger").apply { isDaemon = true }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun record(record: ModelUsageRecordEntity) {
        val context = appContext ?: return
        writer.execute {
            runCatching { EtaDatabase.get(context).modelUsageDao().insert(record) }
                .onFailure { failure ->
                    AndroidAgentLogger.warnThrottled("usage_ledger_write_failed") {
                        "Usage ledger write failed: type=${failure.safeLogType()}"
                    }
                }
        }
    }

    /** 一次 run 的累计用量（含压缩与子 Agent 的请求）；账本不可用或还没有记录时为 null。 */
    fun runSummary(runId: String): ModelUsageSummary? {
        val context = appContext ?: return null
        val records = runCatching {
            runBlocking { EtaDatabase.get(context).modelUsageDao().forRun(runId) }
        }.getOrNull() ?: return null
        return records.takeIf { it.isNotEmpty() }?.let(ModelUsageSummary::of)
    }
}

/** 一组请求的合计；[requestsWithoutUsage] 为服务商没有返回用量的请求数。 */
internal data class ModelUsageSummary(
    val requests: Int,
    val requestsWithoutUsage: Int,
    val promptTokens: Long,
    val cachedTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("requests", requests)
        .put("requests_without_usage", requestsWithoutUsage)
        .put("input_tokens", promptTokens)
        .put("cached_tokens", cachedTokens)
        .put("cache_write_tokens", cacheWriteTokens)
        .put("output_tokens", outputTokens)
        .put("reasoning_tokens", reasoningTokens)

    companion object {
        fun of(records: List<ModelUsageRecordEntity>) = ModelUsageSummary(
            requests = records.size,
            requestsWithoutUsage = records.count { !it.hasUsage },
            promptTokens = records.sumOf { it.promptTokens },
            cachedTokens = records.sumOf { it.cachedTokens },
            cacheWriteTokens = records.sumOf { it.cacheWriteTokens },
            outputTokens = records.sumOf { it.outputTokens },
            reasoningTokens = records.sumOf { it.reasoningTokens },
        )
    }
}
