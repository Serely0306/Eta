package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 子 Agent 的完整过程。主会话只保存子 Agent 的结论，过程按 run 存成文件供界面查看：
 * 内容与主会话同样已脱敏（敏感工具结果、图片不保存），30 天后清理，删除会话时一并删除。
 */
internal object SubagentTranscriptStore {
    @Serializable
    data class Record(
        val runId: String,
        val toolCallId: String,
        val index: Int,
        val total: Int,
        val task: String,
        val ok: Boolean,
        val result: String,
        val createdAt: Long,
        val transcript: List<AgentModelClient.ConversationMessage>,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun save(context: Context, record: Record, now: Long = System.currentTimeMillis()) {
        runCatching {
            val file = file(context, record.runId, record.toolCallId, record.index)
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Record.serializer(), capped(record)))
            prune(context, now)
        }.onFailure { failure ->
            AndroidAgentLogger.warn("Subagent transcript save failed: type=${failure.safeLogType()}")
        }
    }

    fun load(context: Context, runId: String, toolCallId: String, index: Int): Record? =
        runCatching {
            file(context, runId, toolCallId, index).takeIf(File::isFile)
                ?.readText()?.let { json.decodeFromString(Record.serializer(), it) }
        }.getOrNull()

    fun deleteRuns(context: Context, runIds: Collection<String>) {
        runIds.forEach { runId -> File(root(context), safeName(runId)).deleteRecursively() }
    }

    /** 过大的过程逐条截短正文，避免一次读取占用过多内存。 */
    internal fun capped(record: Record): Record {
        if (json.encodeToString(Record.serializer(), record).length <= MAX_FILE_CHARS) return record
        return record.copy(transcript = record.transcript.map { message ->
            message.copy(
                content = message.content.truncated(),
                contentJson = message.contentJson.takeIf { it.length <= MAX_MESSAGE_CHARS }.orEmpty(),
                reasoningContent = message.reasoningContent.truncated(),
            )
        })
    }

    private fun String.truncated(): String =
        if (length <= MAX_MESSAGE_CHARS) this else take(MAX_MESSAGE_CHARS) + TRUNCATED_SUFFIX

    private fun prune(context: Context, now: Long) {
        root(context).listFiles()?.forEach { dir ->
            if (now - dir.lastModified() > RETENTION_MS) dir.deleteRecursively()
        }
    }

    private fun root(context: Context) = File(context.applicationContext.filesDir, DIRECTORY)

    private fun file(context: Context, runId: String, toolCallId: String, index: Int) =
        File(File(root(context), safeName(runId)), "${safeName(toolCallId)}-$index.json")

    private fun safeName(value: String): String = value.replace(UNSAFE, "_").take(MAX_NAME_CHARS).ifBlank { "_" }

    private const val DIRECTORY = "subagent_transcripts"
    private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1_000
    private const val MAX_FILE_CHARS = 2_000_000
    private const val MAX_MESSAGE_CHARS = 20_000
    private const val MAX_NAME_CHARS = 120
    private const val TRUNCATED_SUFFIX = "\n…（过长，已截断）"
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")
}
