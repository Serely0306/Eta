package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.GuiControlStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOverlayVisibilityPolicyTest {
    @Test
    fun plannedToolsAndWaitingForGuiDoNotRevealOrDismissTheEntrySurface() {
        val events = listOf(
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = "tap"),
            AgentEvent.AssistantReceived(1, 0, "", listOf("launch_app", "observe_screen")),
            AgentEvent.ToolStarted(1, "tap-1", "tap", "点击"),
            AgentEvent.ToolStarted(1, "alarm-1", "set_alarm", "创建闹钟"),
            AgentEvent.GuiControlChanged(GuiControlStatus.Waiting),
            AgentEvent.GuiControlChanged(GuiControlStatus.Released),
        )
        events.forEach {
            assertFalse(AgentOverlayVisibilityPolicy.shouldRevealFor(it))
            assertFalse(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(it))
        }
    }

    @Test
    fun onlyAcquiredGuiControlDismissesTheEntrySurface() {
        val acquired = AgentEvent.GuiControlChanged(GuiControlStatus.Acquired)
        assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(acquired))
        assertTrue(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(acquired))
        assertFalse(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(
            AgentEvent.GuiControlChanged(GuiControlStatus.UserPaused),
        ))
    }

    @Test
    fun completedForegroundToolsRevealTheOverlayAndAreRecordedAfterPreparation() {
        val finished = AgentEvent.ToolFinished(1, "observe-1", "observe_screen", "完成", 0, 0, success = true)
        assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(finished))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(finished, false))
        assertTrue(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(finished, true))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(finished.copy(success = false), true))
    }

    @Test
    fun backgroundToolsAndReleaseHaveNoForegroundSideEffects() {
        listOf("terminal", "terminal_job", "read_file", "browser_use", "web_search", "release_gui", "get_current_context")
            .forEach { name ->
                val event = AgentEvent.ToolFinished(1, "call", name, "完成", 0, 0)
                assertFalse(name, AgentOverlayVisibilityPolicy.shouldRevealFor(event))
                assertFalse(name, AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(name))
            }
        assertTrue(AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal("set_alarm"))
        assertFalse(AgentOverlayVisibilityPolicy.isForegroundOperationTool("set_alarm"))
    }
}
