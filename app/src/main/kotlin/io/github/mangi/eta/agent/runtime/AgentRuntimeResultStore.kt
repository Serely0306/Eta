package io.github.mangi.eta.agent.runtime

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeResultEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** 终态先落盘，入口成功提交后 ACK；未确认结果不按年龄或数量淘汰。 */
internal object AgentRuntimeResultStore {
    private const val MAX_AGE_MS = 12L * 60L * 60L * 1000L
    private const val MAX_RECENT_ACKNOWLEDGEMENTS = 32

    private val deliveryLock = Any()
    private val recentlyAcknowledgedRunIds = LinkedHashMap<String, Long>()

    /**
     * An ACK prevents rewriting the outbox, but an external archive may still be committed.
     * Result, archive and consumed notification IDs share the same transaction.
     */
    fun add(
        context: Context,
        completedRun: AgentRuntimeWire.CompletedRun,
        archivedRun: AgentRunArchiveStore.ArchivedRun? = null,
        consumedNoticeIds: List<Long> = emptyList(),
    ): Boolean {
        val appContext = context.applicationContext
        val entity = completedRun.toEntity()
        synchronized(deliveryLock) {
            pruneAcknowledgements(System.currentTimeMillis())
            val writeOutbox = !recentlyAcknowledgedRunIds.containsKey(entity.runId)
            if (!writeOutbox && archivedRun == null) return false
            runBlocking(Dispatchers.IO) {
                val database = EtaDatabase.get(appContext)
                database.withTransaction {
                    val dao = database.runtimeRunDao()
                    if (writeOutbox) dao.upsertRuntimeResult(entity)
                    if (archivedRun != null) AgentRunArchiveStore.add(dao, archivedRun)
                    if (consumedNoticeIds.isNotEmpty()) {
                        database.subagentDao().markMailDelivered(consumedNoticeIds, entity.runId)
                    }
                }
            }
            return true
        }
    }

    fun list(context: Context): List<AgentRuntimeWire.CompletedRun> {
        val appContext = context.applicationContext
        return synchronized(deliveryLock) {
            runBlocking(Dispatchers.IO) {
                EtaDatabase.get(appContext).runtimeRunDao().runtimeResults()
                    .map { it.toDomain() }
            }
        }
    }

    fun pendingPage(context: Context): List<AgentRuntimeWire.CompletedRun> = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(context.applicationContext).runtimeRunDao().pendingResultHeaders(8).map { header ->
            AgentRuntimeWire.CompletedRun(
                AgentRuntimeWire.EntryHandoff(header.handoffId, header.handoffSource, header.handoffPayload, header.dismissEntrySurface),
                AgentRuntimeWire.RunResult(header.runId, header.ok, "", contextSnapshotRef = header.runId,
                    operation = header.operation, rewriteTargetMessageId = header.rewriteTargetMessageId),
                header.createdAt,
            )
        }
    }

    fun readOwned(context: Context, runId: String, owner: String): AgentRuntimeWire.CompletedRun? = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(context.applicationContext).runtimeRunDao().ownedResult(runId, owner)?.toDomain()
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        val appContext = context.applicationContext
        synchronized(deliveryLock) {
            runBlocking(Dispatchers.IO) {
                EtaDatabase.get(appContext)
                    .runtimeRunDao()
                    .acknowledgeRuntimeResult(runId)
            }
            rememberAcknowledgement(runId, System.currentTimeMillis())
        }
    }

    private fun rememberAcknowledgement(runId: String, now: Long) {
        pruneAcknowledgements(now)
        recentlyAcknowledgedRunIds.remove(runId)
        while (recentlyAcknowledgedRunIds.size >= MAX_RECENT_ACKNOWLEDGEMENTS) {
            val oldestRunId = recentlyAcknowledgedRunIds.keys.firstOrNull() ?: break
            recentlyAcknowledgedRunIds.remove(oldestRunId)
        }
        recentlyAcknowledgedRunIds[runId] = now
    }

    private fun pruneAcknowledgements(now: Long) {
        recentlyAcknowledgedRunIds.entries.removeAll { (_, acknowledgedAt) ->
            now - acknowledgedAt > MAX_AGE_MS
        }
    }

    private fun AgentRuntimeWire.CompletedRun.toEntity(): RuntimeResultEntity {
        val stableRunId = result.runId.ifBlank { handoff.id }
        return RuntimeResultEntity(
            runId = stableRunId,
            handoffId = handoff.id,
            handoffSource = handoff.source,
            handoffPayload = handoff.payload,
            dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
            ok = result.ok,
            content = result.content,
            error = result.error,
            reasoningContent = result.reasoningContent,
            transcriptJson = AgentConversationCodec.encodeTranscriptForStorage(result.transcript),
            contextSnapshotJson = result.contextSnapshot?.encode().orEmpty(),
            operation = result.operation,
            rewriteTargetMessageId = result.rewriteTargetMessageId,
            createdAt = createdAt,
        )
    }

    private fun RuntimeResultEntity.toDomain(): AgentRuntimeWire.CompletedRun =
        AgentRuntimeWire.CompletedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = handoffId,
                source = handoffSource,
                payload = handoffPayload,
                dismissEntrySurfaceOnForegroundOperation = dismissEntrySurface,
            ),
            result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = ok,
                content = content,
                error = error,
                reasoningContent = reasoningContent,
                contextSnapshot = AgentContextSnapshot.decode(contextSnapshotJson),
                operation = operation,
                rewriteTargetMessageId = rewriteTargetMessageId,
                transcript = legacyCompatibleTranscript(
                    raw = transcriptJson,
                    ok = ok,
                    content = content,
                    reasoningContent = reasoningContent,
                ),
            ),
            createdAt = createdAt,
        )

    private fun legacyCompatibleTranscript(
        raw: String,
        ok: Boolean,
        content: String,
        reasoningContent: String,
    ): List<AgentModelClient.ConversationMessage> =
        AgentConversationCodec.decodeTranscript(raw).ifEmpty {
            if (!ok || content.isBlank()) return@ifEmpty emptyList()
            listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = content,
                    reasoningContent = reasoningContent,
                )
            )
        }
}
