package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 一次模型请求的用量，与会话独立保存，删除会话不影响。只存计数与模型信息，不存任何对话内容。
 * token 统一口径：[promptTokens] 为含缓存读写的全部输入，[cachedTokens]、[cacheWriteTokens] 是其中的缓存部分，
 * [reasoningTokens] 是 [outputTokens] 中的推理部分。服务商没有返回用量时 [hasUsage] 为 false，计数均为 0。
 */
@Serializable
@Entity(
    tableName = "model_usage_records",
    indices = [Index("created_at"), Index("run_id")],
)
internal data class ModelUsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    /** 入口：Runtime 的 handoff 来源，聊天为 `agent_ui`；未知为空。 */
    val source: String,
    @ColumnInfo(name = "run_id") val runId: String,
    /** 请求用途：chat、compaction、reply_rewrite、transcript_refine。 */
    val purpose: String,
    /** 子 Agent 的序号（从 0 开始）；主 Agent 的请求为 null。 */
    @ColumnInfo(name = "subagent_index") val subagentIndex: Int?,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "provider_name") val providerName: String,
    val model: String,
    @ColumnInfo(name = "model_display_name") val modelDisplayName: String,
    /** responses、chat_completions 或 anthropic。 */
    val endpoint: String,
    @ColumnInfo(name = "reasoning_effort") val reasoningEffort: String,
    /** ok、failed 或 cancelled；失败的请求也可能已经产生用量。 */
    val status: String,
    @ColumnInfo(name = "has_usage") val hasUsage: Boolean,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Long,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Long,
    @ColumnInfo(name = "cache_write_tokens") val cacheWriteTokens: Long,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Long,
) {
    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}

@Dao
internal interface ModelUsageDao {
    /** 由账本的写入线程调用，不在主线程。 */
    @Insert
    fun insert(record: ModelUsageRecordEntity)

    @Query("SELECT * FROM model_usage_records WHERE run_id = :runId ORDER BY id")
    suspend fun forRun(runId: String): List<ModelUsageRecordEntity>

    @Query(
        "SELECT * FROM model_usage_records WHERE created_at >= :from AND created_at < :to " +
            "ORDER BY created_at DESC, id DESC LIMIT :limit"
    )
    suspend fun between(from: Long, to: Long, limit: Int): List<ModelUsageRecordEntity>

    @Query(
        "SELECT * FROM model_usage_records WHERE created_at >= :from AND created_at < :to " +
            "ORDER BY created_at DESC, id DESC LIMIT :limit"
    )
    fun betweenFlow(from: Long, to: Long, limit: Int): Flow<List<ModelUsageRecordEntity>>

    @Query("SELECT * FROM model_usage_records ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ModelUsageRecordEntity>

    @Query("DELETE FROM model_usage_records")
    suspend fun deleteAll()
}
