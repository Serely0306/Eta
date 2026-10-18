package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubagentTranscriptStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun savesLoadsAndDeletesByRun() {
        val record = record("run/1", "call:1", listOf(ConversationMessage(role = "assistant", content = "结论")))
        SubagentTranscriptStore.save(context, record)

        assertEquals(record, SubagentTranscriptStore.load(context, "run/1", "call:1", 0))
        assertNull(SubagentTranscriptStore.load(context, "run/1", "call:1", 1))

        // 运行中的快照被最终结果覆盖。
        SubagentTranscriptStore.save(context, record.copy(running = true, ok = false, result = ""))
        assertTrue(SubagentTranscriptStore.load(context, "run/1", "call:1", 0)!!.running)
        SubagentTranscriptStore.save(context, record)
        assertEquals(record, SubagentTranscriptStore.load(context, "run/1", "call:1", 0))

        SubagentTranscriptStore.deleteRuns(context, listOf("run/1"))
        assertNull(SubagentTranscriptStore.load(context, "run/1", "call:1", 0))
    }

    @Test
    fun oversizedTranscriptsAreTruncatedPerMessage() {
        val huge = "x".repeat(150_000)
        val record = record("run-2", "call", List(20) { ConversationMessage(role = "tool", toolCallId = "c$it", content = huge) })
        val capped = SubagentTranscriptStore.capped(record)
        assertTrue(capped.transcript.all { it.content.length < 21_000 && it.content.endsWith("已截断）") })
        val small = record("run-3", "call", listOf(ConversationMessage(role = "tool", content = "短")))
        assertEquals(small, SubagentTranscriptStore.capped(small))
    }

    private fun record(runId: String, callId: String, transcript: List<ConversationMessage>) = SubagentTranscriptStore.Record(
        runId = runId, toolCallId = callId, index = 0, total = 2, task = "任务", ok = true, result = "结论",
        createdAt = 1L, transcript = transcript,
    )
}
