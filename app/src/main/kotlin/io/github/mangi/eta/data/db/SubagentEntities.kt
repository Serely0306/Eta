package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.serialization.Serializable

/**
 * 会话里的一个子 Agent（AG-19）。跨轮次保留：模型上下文在 [transcriptJson]（已脱敏），续用时作为历史。
 * [id] 为 `<会话>|<agentId>`，供分块文本与删除触发器使用。
 */
@Serializable
@Entity(tableName = "conversation_subagents", indices = [Index("conversation_id")])
internal data class ConversationSubagentEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "agent_id") val agentId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "task") val task: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "turn") val turn: Int,
    @ColumnInfo(name = "rounds") val rounds: Int,
    @ColumnInfo(name = "last_result") val lastResult: String,
    @ColumnInfo(name = "last_error") val lastError: String,
    @ColumnInfo(name = "transcript_json") val transcriptJson: String,
    @ColumnInfo(name = "model_session_id") val modelSessionId: String,
    @ColumnInfo(name = "owner_instance_id") val ownerInstanceId: String,
    @ColumnInfo(name = "created_run_id") val createdRunId: String,
    @ColumnInfo(name = "last_run_id") val lastRunId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        fun rowId(conversationId: String, agentId: String): String = "$conversationId|$agentId"
    }
}

/** 子 Agent 一个回合结束后的通知；投递给主 Agent 后记下投递到的 run，未投递的跨进程保留。 */
@Serializable
@Entity(tableName = "conversation_subagent_mail", indices = [Index("conversation_id")])
internal data class ConversationSubagentMailEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "agent_id") val agentId: String,
    @ColumnInfo(name = "turn") val turn: Int,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "delivered_run_id") val deliveredRunId: String?,
)

@Dao
internal interface SubagentDao : ChunkedTextDao {
    @Query("SELECT * FROM conversation_subagents WHERE conversation_id = :conversationId ORDER BY created_at ASC, agent_id ASC")
    suspend fun agentRows(conversationId: String): List<ConversationSubagentEntity>

    @Transaction
    suspend fun agents(conversationId: String): List<ConversationSubagentEntity> =
        agentRows(conversationId).map { restoreAgent(it) }

    suspend fun restoreAgent(row: ConversationSubagentEntity): ConversationSubagentEntity =
        row.copy(transcriptJson = restoreText(TABLE, row.id, TRANSCRIPT_FIELD, row.transcriptJson))

    @Upsert
    suspend fun upsertAgentRow(row: ConversationSubagentEntity)

    @Transaction
    suspend fun upsertAgent(row: ConversationSubagentEntity) {
        upsertAgentRow(row.copy(transcriptJson = storeText(TABLE, row.id, TRANSCRIPT_FIELD, row.transcriptJson)))
    }

    /** 进程重启后，上一个进程里还在运行的子 Agent 改为已中断，之后可以继续使用。 */
    @Query("UPDATE conversation_subagents SET status = 'interrupted', updated_at = :now " +
        "WHERE status = 'running' AND owner_instance_id != :ownerInstanceId")
    suspend fun interruptStale(ownerInstanceId: String, now: Long): Int

    @Insert
    suspend fun insertMail(mail: ConversationSubagentMailEntity): Long

    @Query("SELECT * FROM conversation_subagent_mail WHERE conversation_id = :conversationId AND delivered_run_id IS NULL ORDER BY id ASC")
    suspend fun undeliveredMail(conversationId: String): List<ConversationSubagentMailEntity>

    /** [runId] 为空表示恢复为未投递（主 run 没来得及交给模型就结束了）。 */
    @Query("UPDATE conversation_subagent_mail SET delivered_run_id = :runId WHERE id IN (:ids)")
    suspend fun markMailDelivered(ids: List<Long>, runId: String?)

    @Query("SELECT DISTINCT conversation_id FROM conversation_subagent_mail WHERE delivered_run_id IS NULL")
    suspend fun conversationsWithUndeliveredMail(): List<String>

    @Query("DELETE FROM conversation_subagents WHERE conversation_id = :conversationId")
    suspend fun deleteAgents(conversationId: String)

    @Query("DELETE FROM conversation_subagent_mail WHERE conversation_id = :conversationId")
    suspend fun deleteMail(conversationId: String)

    @Transaction
    suspend fun deleteConversation(conversationId: String) {
        deleteAgents(conversationId)
        deleteMail(conversationId)
    }
}

private const val TABLE = "conversation_subagents"
private const val TRANSCRIPT_FIELD = "transcriptJson"
