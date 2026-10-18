package io.github.mangi.eta.data.usage

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ModelUsageRecordEntity
import java.io.OutputStream
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
                .onSuccess { if (record.hasUsage) UsageQuotaMonitor.check(context, record.providerId) }
                .onFailure { failure ->
                    AndroidAgentLogger.warnThrottled("usage_ledger_write_failed") {
                        "Usage ledger write failed: type=${failure.safeLogType()}"
                    }
                }
        }
    }

    /** 时间范围内的记录，最新的在前；随账本写入实时更新。 */
    fun recordsFlow(from: Long, to: Long, limit: Int = MAX_REPORT_ROWS): Flow<List<ModelUsageRecordEntity>> =
        appContext?.let { EtaDatabase.get(it).modelUsageDao().betweenFlow(from, to, limit) } ?: flowOf(emptyList())

    /** 一个模型会话（聊天为会话 id）的全部记录，含压缩与子 Agent 的请求。 */
    fun sessionRecordsFlow(sessionId: String): Flow<List<ModelUsageRecordEntity>> =
        appContext?.let { EtaDatabase.get(it).modelUsageDao().forSessionFlow(sessionId) } ?: flowOf(emptyList())

    /** 导出全部记录为 CSV（只有计数与模型信息），费用按当前价格表计算。 */
    suspend fun exportCsv(output: OutputStream, cost: (ModelUsageRecordEntity) -> Double?): Int {
        val context = appContext ?: return 0
        val dao = EtaDatabase.get(context).modelUsageDao()
        var count = 0
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(CSV_HEADER)
            writer.newLine()
            var offset = 0
            while (true) {
                val page = dao.page(EXPORT_PAGE_SIZE, offset)
                if (page.isEmpty()) break
                page.forEach { record ->
                    writer.write(csvRow(record, cost(record)))
                    writer.newLine()
                }
                count += page.size
                offset += page.size
            }
        }
        return count
    }

    private fun csvRow(record: ModelUsageRecordEntity, costUsd: Double?): String = listOf(
        Instant.ofEpochMilli(record.createdAt).toString(),
        record.source, record.runId, record.purpose, record.subagentIndex?.toString().orEmpty(),
        record.providerName, record.model, record.endpoint, record.reasoningEffort, record.status,
        record.hasUsage.toString(), record.promptTokens.toString(), record.cachedTokens.toString(),
        record.cacheWriteTokens.toString(), record.outputTokens.toString(), record.reasoningTokens.toString(),
        record.durationMs.toString(), costUsd?.let { "%.6f".format(Locale.ROOT, it) }.orEmpty(),
    ).joinToString(",") { field ->
        if (field.any { it == ',' || it == '"' || it == '\n' }) "\"${field.replace("\"", "\"\"")}\"" else field
    }

    private const val MAX_REPORT_ROWS = 20_000
    private const val EXPORT_PAGE_SIZE = 500
    private const val CSV_HEADER = "time,source,run_id,purpose,subagent_index,provider,model,endpoint,reasoning_effort," +
        "status,has_usage,input_tokens,cached_tokens,cache_write_tokens,output_tokens,reasoning_tokens,duration_ms,cost_usd"

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
