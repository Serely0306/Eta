package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatScrollPolicyTest {
    @Test
    fun waitingRowShowsOnlyWhileNoOutputIsArriving() {
        val user = io.github.mangi.eta.ui.model.UserMessageUi(id = "u", content = "问")
        val placeholder = io.github.mangi.eta.ui.model.AgentMessageUi(id = "a", content = "", isStreaming = true)
        val streaming = placeholder.copy(content = "正在回答")
        val runningTool = io.github.mangi.eta.ui.model.ToolActivityMessageUi(
            "t", "terminal", io.github.mangi.eta.ui.model.ToolActivityStatusUi.Running, "",
        )
        assertFalse(isWaitingForModel(emptyList()))
        assertTrue(isWaitingForModel(listOf(user)))
        assertTrue(isWaitingForModel(listOf(user, placeholder)))
        assertFalse(isWaitingForModel(listOf(user, streaming)))
        assertFalse(isWaitingForModel(listOf(user, runningTool)))
        assertTrue(isWaitingForModel(listOf(user, runningTool.copy(status = io.github.mangi.eta.ui.model.ToolActivityStatusUi.Success))))
    }

    @Test
    fun waitingClockKeepsTheFirstStartOfEachWaitAcrossRecomposition() {
        assertEquals(1_000L, ModelWaitClock.startOf("clock-test-a", 1_000L))
        // 行被回收后重新创建：同一段等待仍从第一次的起点算。
        assertEquals(1_000L, ModelWaitClock.startOf("clock-test-a", 9_000L))
        assertEquals(9_000L, ModelWaitClock.startOf("clock-test-b", 9_000L))
        repeat(64) { ModelWaitClock.startOf("clock-test-fill-$it", 10_000L) }
        assertEquals(20_000L, ModelWaitClock.startOf("clock-test-a", 20_000L))
    }

    @Test
    fun historyPagingRunsDuringAStreamOnlyAfterTheUserLeavesTheBottom() {
        assertTrue(historyPagingEnabled(isStreaming = false, keepBottomAnchored = true))
        assertTrue(historyPagingEnabled(isStreaming = false, keepBottomAnchored = false))
        assertFalse(historyPagingEnabled(isStreaming = true, keepBottomAnchored = true))
        assertTrue(historyPagingEnabled(isStreaming = true, keepBottomAnchored = false))
    }

    @Test
    fun networkCompletionKeepsFollowingUntilRenderedTailSettles() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun draggingInterruptsCompletionFollowing() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = true,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completionDoesNotPullReaderBackFromHistory() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = false,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completedContentExpansionDoesNotFollowBottom() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun streamingTailGrowthFollowsBottom() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun completedConversationDoesNotJumpToInitialBottom() {
        assertFalse(
            shouldRequestInitialBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun streamingConversationRequestsInitialBottom() {
        assertTrue(
            shouldRequestInitialBottom(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun contentGrowthDoesNotDisableBottomFollowing() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = false,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun draggingAwayFromBottomDisablesFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = true,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun reachingBottomEnablesFollowingAgain() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
            )
        )
    }

    @Test
    fun growingTailOnlyScrollsByTheOverflowDistance() {
        assertEquals(
            BottomFollowDecision(scrollByPx = 24),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = 1024,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }

    @Test
    fun largeAppendRequestsBottomOnlyWhenSentinelLeftTheViewport() {
        assertEquals(
            BottomFollowDecision(requestIndex = 8),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = null,
                viewportEnd = 1000,
                lastVisibleIndex = 6,
            ),
        )
    }

    @Test
    fun disabledFollowingNeverMovesTheList() {
        assertEquals(
            BottomFollowDecision(),
            resolveBottomFollowDecision(
                enabled = false,
                bottomItemIndex = 8,
                sentinelBottom = 1100,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }
}
