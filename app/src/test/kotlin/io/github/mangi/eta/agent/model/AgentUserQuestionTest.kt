package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUserQuestionTest {
    @Test
    fun parseNormalizesQuestionsAndDropsBlankEntries() {
        val parsed = AgentUserQuestion.parse(
            """{"questions":[
                {"question":"  发给谁？ ","header":"收件人","options":[{"label":"妈妈"},{"label":" "},{"label":"爸爸","description":"微信"}]},
                {"question":"  ","options":[{"label":"A"},{"label":"B"}]}
            ]}""",
        )!!
        assertEquals(1, parsed.items.size)
        assertEquals("发给谁？", parsed.items.single().question)
        assertEquals(listOf("妈妈", "爸爸"), parsed.items.single().options.map { it.label })
        assertEquals(parsed, AgentUserQuestion.parse(parsed.toJson()))
        assertNull(AgentUserQuestion.parse("""{"questions":[]}"""))
        assertNull(AgentUserQuestion.parse("not json"))
    }

    @Test
    fun toolResultsCarryStatusAndAnswersInQuestionOrder() {
        val question = AgentUserQuestion(listOf(
            AgentUserQuestion.Item("几点？", "", listOf(AgentUserQuestion.Option("9 点", ""), AgentUserQuestion.Option("10 点", "")), false),
        ))
        val selected = JSONObject(AgentUserAnswer.Selected(listOf(listOf("9 点"))).toToolResult(question))
        assertEquals("answered", selected.getString("status"))
        assertEquals("几点？", selected.getJSONArray("answers").getJSONObject(0).getString("question"))
        assertEquals("9 点", selected.getJSONArray("answers").getJSONObject(0).getJSONArray("selected").getString(0))
        assertEquals("skipped", JSONObject(AgentUserAnswer.Skipped.toToolResult(question)).getString("status"))
        assertEquals("timeout", JSONObject(AgentUserAnswer.TimedOut.toToolResult(question)).getString("status"))
        assertEquals("自己写的", JSONObject(AgentUserAnswer.text(" 自己写的 ").toToolResult(question)).getString("text"))
        assertTrue(AgentUserQuestion.schema().toString().contains(AgentUserQuestion.TOOL_NAME))
    }
}
