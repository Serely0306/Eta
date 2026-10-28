package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentUserQuestion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiHandoffPolicyTest {
    @After
    fun clear() = GuiHandoffPolicy.clearForTest()

    @Test
    fun onlyAppSessionsAreAskedAndATimeoutSilencesTheConversationForThreeMinutes() {
        assertNull(GuiHandoffPolicy.skipReason(null, "conv", now = 0))
        assertNull(GuiHandoffPolicy.skipReason(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conv", now = 0))
        for (source in listOf(AgentRuntimeWire.XIAOAI_HANDOFF_SOURCE, AgentRuntimeWire.AUTOMATION_HANDOFF_SOURCE,
            AgentRuntimeWire.PC_MCP_HANDOFF_SOURCE, AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE)) {
            assertEquals(GuiHandoffPolicy.STATUS_NOT_APPLICABLE, GuiHandoffPolicy.skipReason(source, "conv", now = 0)?.first)
        }

        GuiHandoffPolicy.startCooldown("conv", now = 1_000)
        assertEquals(GuiHandoffPolicy.STATUS_COOLDOWN, GuiHandoffPolicy.skipReason(null, "conv", now = 1_000 + 179_999)?.first)
        assertNull(GuiHandoffPolicy.skipReason(null, "other", now = 2_000))
        assertNull(GuiHandoffPolicy.skipReason(null, "conv", now = 1_000 + 180_000))
    }

    @Test
    fun handoffQuestionsHaveFixedOptionsAndAShortTimeout() {
        val question = AgentUserQuestion.parse(
            """{"kind":"gui_handoff","questions":[{"question":"要登录账号，谁来操作？","options":[{"label":"随便"}]}]}""",
        )!!
        assertTrue(question.isGuiHandoff)
        assertEquals(listOf("你来操作", "我自己来"), question.items.single().options.map { it.label })
        assertEquals(30_000L, AgentUserQuestion.GUI_HANDOFF_TIMEOUT_MS)
        assertEquals(question, AgentUserQuestion.parse(question.toJson()))
        assertEquals(AgentUserQuestion.KIND_QUESTION, AgentUserQuestion.parse("""{"questions":[{"question":"几点？","options":[{"label":"8"},{"label":"9"}]}]}""")!!.kind)
    }
}
