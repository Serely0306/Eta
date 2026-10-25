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
    }

    @Test
    fun schemaLimitsParallelTasks() {
        val tasks = AgentSubagentTool.schema().getJSONObject("function").getJSONObject("parameters")
            .getJSONObject("properties").getJSONObject("tasks")
        assertEquals(AgentSubagentTool.MAX_TASKS, tasks.getInt("maxItems"))
        assertEquals("task", tasks.getJSONObject("items").getJSONArray("required").getString(0))
    }
}
