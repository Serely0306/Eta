package io.github.mangi.eta.agent.runtime

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentConversationIdentityTest {
    @Test
    fun legacyAndStructuredUiHandoffsUseTheSameConversation() {
        val legacy = AgentRuntimeWire.EntryHandoff("run-a", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conv-a")
        val structured = legacy.copy(payload = AgentUiHandoffPayload("conv-a").toJson())
        assertEquals("conv-a", AgentConversationIdentity.fromHandoff(legacy, "run-a"))
        assertEquals("conv-a", AgentConversationIdentity.fromHandoff(structured, "run-b"))
    }

    @Test
    fun externalRunsUseTheSourceTaggedArchiveIdentityOfTheAppSide() {
        val payload = AgentExternalArchivePayload("请求", "dialog-42", "会话").toJson()
        val handoff = AgentRuntimeWire.EntryHandoff("run-a", "xiaoai", payload)
        // 运行登记、会话浏览器与 App 导入的归档会话必须是同一个 ID（本分支带来源标记的编号）。
        assertEquals("archive-xiaoai-777ba66ea9576d0e68c49b39", AgentConversationIdentity.fromHandoff(handoff, "run-a"))
        assertEquals(ExternalArchiveIds.conversationId("xiaoai", "dialog-42"),
            AgentConversationIdentity.fromHandoff(handoff, "run-a"))
        assertEquals(AgentConversationIdentity.fromHandoff(handoff, "run-a"),
            AgentConversationIdentity.fromHandoff(handoff.copy(id = "run-b"), "run-b"))
        assertNotEquals(AgentConversationIdentity.fromHandoff(handoff, "run-a"),
            AgentConversationIdentity.fromHandoff(handoff.copy(source = "breeno"), "run-a"))
    }

    @Test
    fun requestsWithoutAConversationRemainRunScoped() {
        assertEquals("run:run-a", AgentConversationIdentity.fromHandoff(null, "run-a"))
        assertNotEquals(AgentConversationIdentity.fromHandoff(null, "run-a"),
            AgentConversationIdentity.fromHandoff(null, "run-b"))
    }

    @Test
    fun voiceArchiveKeepsItsPublishedPrefixAndDigest() {
        assertEquals("assistant-fa2945f5951c8f096f360631",
            AgentConversationIdentity.archiveConversationId(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "voice-conversation"))
    }
}
