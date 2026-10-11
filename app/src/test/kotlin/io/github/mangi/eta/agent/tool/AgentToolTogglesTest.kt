package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolTogglesTest {
    @Test
    fun cardsToggleWholeFamiliesAndUnlistedToolsStandAlone() {
        assertEquals(setOf("long_press", "long_press_element"), AgentToolToggles.family("long_press"))
        assertEquals(setOf("tap_area", "tap"), AgentToolToggles.family("tap_area"))
        assertEquals(setOf("search_notes"), AgentToolToggles.family("search_notes"))
    }

    @Test
    fun disablingObservationStopsDependentGuiToolsButNotIndependentOnes() {
        val effective = AgentToolToggles.effective(setOf("observe_screen"))
        listOf("observe_screen", "tap", "tap_element", "long_press_element", "scroll_element", "swipe", "type_text")
            .forEach { assertTrue(it, it in effective) }
        listOf("press_key", "launch_app", "wait_for_text").forEach { assertFalse(it, it in effective) }
        assertEquals("observe_screen", AgentToolToggles.dependencyOf("tap_element"))
        assertNull(AgentToolToggles.dependencyOf("observe_screen"))
    }

    @Test
    fun skillChainsFollowTheirPrerequisiteAndEmptyInputStaysEmpty() {
        assertTrue("skills_install_from_github" in AgentToolToggles.effective(setOf("skills_inspect_github")))
        assertTrue("skills_read_resource" in AgentToolToggles.effective(setOf("skills_read")))
        assertFalse("skills_read" in AgentToolToggles.effective(setOf("skills_read_resource")))
        assertTrue(AgentToolToggles.effective(emptySet()).isEmpty())
    }
}
