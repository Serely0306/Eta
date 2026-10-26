package io.github.mangi.eta.agent.mcp.server

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.ExternalArchiveIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EtaMcpConversationHistoriesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun historySurvivesAProcessRestartAndManyOtherConversations() {
        val directory = folder.newFolder("mcp_histories")
        val first = EtaMcpConversationHistories(directory)
        val history = listOf(
            ConversationMessage(role = "user", content = "第一题：TCP 三次握手的目的"),
            ConversationMessage(role = "assistant", content = "建立可靠连接"),
        )
        first.save("pc-1", history)
        // 超过内存缓存的会话数，旧会话仍能从磁盘读回。
        repeat(12) { first.save("other-$it", listOf(ConversationMessage(role = "user", content = "x$it"))) }
        assertEquals(history, first.load("pc-1"))

        // 新实例相当于进程重启后。
        assertEquals(history, EtaMcpConversationHistories(directory).load("pc-1"))
        assertTrue(EtaMcpConversationHistories(directory).load("never-used").isEmpty())
    }

    @Test
    fun fileIsNamedAfterTheSidebarConversationSoDeletingItCanRemoveTheHistory() {
        val directory = folder.newFolder("mcp_histories")
        EtaMcpConversationHistories(directory).save("pc-2", listOf(ConversationMessage(role = "user", content = "x")))
        val conversationId = ExternalArchiveIds.conversationId(AgentRuntimeWire.PC_MCP_HANDOFF_SOURCE, "pc-2")
        val file = EtaMcpConversationHistories.fileFor(directory, "pc-2")
        assertEquals("$conversationId.json", file.name)
        assertTrue(conversationId.startsWith("archive-pc_mcp-"))
        assertTrue(file.delete())
        assertFalse(file.exists())
    }
}
