package io.github.mangi.eta.agent.mcp.server

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.ExternalArchiveIds
import java.io.File

/**
 * 电脑端委托任务的会话历史。以前只在内存里保留最近 8 个会话，进程重启或被挤出后同一
 * conversation_id 从空上下文开始；现在按会话写进私有目录（与主会话同样脱敏），内存只作缓存。
 * 文件以侧栏“电脑端”会话的编号命名，会话删除时据此一起删除。
 */
internal class EtaMcpConversationHistories(private val directory: File) {
    private val cache = LinkedHashMap<String, List<AgentModelClient.ConversationMessage>>()

    fun load(conversationKey: String): List<AgentModelClient.ConversationMessage> = synchronized(this) {
        cache[conversationKey]?.let { return it }
        val file = fileFor(directory, conversationKey)
        val history = if (file.isFile) {
            runCatching { AgentConversationCodec.decodeTranscript(file.readText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        remember(conversationKey, history)
        history
    }

    fun save(conversationKey: String, history: List<AgentModelClient.ConversationMessage>) = synchronized(this) {
        remember(conversationKey, history)
        directory.mkdirs()
        val file = fileFor(directory, conversationKey)
        val temporary = File(directory, file.name + ".tmp")
        temporary.writeText(AgentConversationCodec.encodeConversationCheckpoint(history))
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    private fun remember(conversationKey: String, history: List<AgentModelClient.ConversationMessage>) {
        cache.remove(conversationKey)
        cache[conversationKey] = history
        while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
    }

    companion object {
        private const val DIRECTORY = "mcp_histories"
        private const val CACHE_SIZE = 8

        fun forContext(context: Context) = EtaMcpConversationHistories(File(context.applicationContext.filesDir, DIRECTORY))

        /** 侧栏删除“电脑端”会话时调用；不是电脑端会话或没有历史文件时什么也不做。 */
        fun deleteForConversation(context: Context, conversationId: String) {
            File(File(context.applicationContext.filesDir, DIRECTORY), "$conversationId.json").delete()
        }

        internal fun fileFor(directory: File, conversationKey: String): File = File(
            directory,
            ExternalArchiveIds.conversationId(AgentRuntimeWire.PC_MCP_HANDOFF_SOURCE, conversationKey) + ".json",
        )
    }
}
