package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryContextBuilderTest {
    @Test
    fun sessionSnapshotStaysFrozenUntilExternalEditWindowChangeOrCompaction() {
        AgentMemorySessionSnapshots.clear()
        var content = "# 核心记忆\n初始"
        fun resolve(
            session: String = "conversation-1",
            window: Int? = 128_000,
            generation: Long = 0,
            history: List<AgentModelClient.ConversationMessage> = emptyList(),
        ) = AgentMemorySessionSnapshots.resolve(session, window, generation, history) {
            AgentMemoryContextBuilder.build(snapshot(content), window)
        }.coreContent

        assertEquals("# 核心记忆\n初始", resolve())
        content = "# 核心记忆\n模型写入"
        assertEquals("# 核心记忆\n初始", resolve())
        assertEquals("# 核心记忆\n模型写入", resolve(session = "conversation-2"))
        assertEquals("# 核心记忆\n模型写入", resolve(generation = 1))
        content = "# 核心记忆\n窗口变化"
        assertEquals("# 核心记忆\n窗口变化", resolve(window = 256_000, generation = 1))
        content = "# 核心记忆\n压缩之后"
        val summary = AgentModelClient.ConversationMessage(
            role = "assistant", content = "摘要", contextSummary = true,
            summaryThroughUserTurn = 3, messageId = "summary-1",
        )
        assertEquals("# 核心记忆\n压缩之后", resolve(window = 256_000, generation = 1, history = listOf(summary)))
        content = "# 核心记忆\n不应出现"
        assertEquals("# 核心记忆\n压缩之后", resolve(window = 256_000, generation = 1, history = listOf(summary)))
        AgentMemorySessionSnapshots.clear()
    }

    @Test
    fun coreBudgetTracksWindowWithSafeUnknownFallback() {
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(null))
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(128_000))
        assertEquals(16_000, AgentMemoryContextBuilder.coreBudgetChars(256_000))
        assertEquals(32_000, AgentMemoryContextBuilder.coreBudgetChars(1_000_000))
        assertEquals(4_000, AgentMemoryContextBuilder.coreBudgetChars(16_000))
    }

    @Test
    fun injectsOnlyCoreSectionAndBoundedHeadingIndex() {
        val content = "# 核心记忆\n长期偏好\n## 关系\n家人\n# 详细背景\n不应自动注入"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals("# 核心记忆\n长期偏好\n## 关系\n家人", context.coreContent)
        assertFalse(context.coreContent.contains("不应自动注入"))
        assertEquals("# 核心记忆\n## 关系\n# 详细背景", context.headingIndex)
        assertFalse(context.coreTruncated)
    }

    @Test
    fun oversizedCoreIsTruncatedWithoutDroppingRevision() {
        val content = "# 核心记忆\n" + "a".repeat(10_000)
        val snapshot = snapshot(content)
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        assertEquals(8_000, context.coreContent.length)
        assertTrue(context.coreTruncated)
        assertEquals(snapshot.revision, context.revision)
    }

    @Test
    fun detailsWithoutCoreHeadingAreIndexedButNotAutomaticallyInjected() {
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 项目\n只应按需读取的细节"),
            256_000,
        )

        assertEquals("", context.coreContent)
        assertEquals("# 项目", context.headingIndex)
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
