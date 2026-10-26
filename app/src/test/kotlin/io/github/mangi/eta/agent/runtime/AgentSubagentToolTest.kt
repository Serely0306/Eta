package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubagentToolTest {
    @Test
    fun subagentsDoNotGetScreenBrowserOrSkillTools() {
        val excluded = AgentSubagentTool.excludedTools(
            listOf(
                "observe_screen", "tap_element", "launch_app", "release_gui", "set_alarm", "browser_use", "skills_read",
                "terminal", "web_search", "read_file",
            ),
        )
        assertEquals(
            setOf("observe_screen", "tap_element", "launch_app", "release_gui", "set_alarm", "browser_use", "skills_read"),
            excluded,
        )
        assertTrue(listOf("terminal", "web_search", "read_file").none { it in excluded })
        // 子 Agent 不能再派子 Agent、不能提问或建计划。
        assertTrue(AgentSubagentTools.TOOL_NAMES.all { it in AgentSubagentTool.COORDINATOR_TOOLS })
        assertTrue("ask_user" in AgentSubagentTool.COORDINATOR_TOOLS)
    }

    @Test
    fun sixToolsAreExposedWithTheirRequiredArguments() {
        val schemas = AgentSubagentTools.schemas().associate { schema ->
            val function = schema.getJSONObject("function")
            function.getString("name") to function.getJSONObject("parameters").getJSONArray("required")
                .let { array -> (0 until array.length()).map(array::getString) }
        }
        assertEquals(AgentSubagentTools.TOOL_NAMES, schemas.keys)
        assertEquals(listOf("task", "reason"), schemas.getValue("spawn_agent"))
        assertEquals(listOf("agent_id", "message"), schemas.getValue("send_agent"))
        assertEquals(emptyList<String>(), schemas.getValue("wait_agents"))
        assertEquals(listOf("agent_id"), schemas.getValue("close_agent"))
    }
}
