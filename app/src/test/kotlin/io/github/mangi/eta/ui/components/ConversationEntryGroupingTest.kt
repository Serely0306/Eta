package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.app.conversationEntrySource
import io.github.mangi.eta.ui.model.ConversationEntrySource
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationEntryGroupingTest {
    @Test
    fun vendorArchivesAreRecognizedByTaggedIdOrLegacyTitle() {
        assertEquals(ConversationEntrySource.XiaoAi, conversationEntrySource("archive-xiaoai-0123abcd", "改过的名字"))
        assertEquals(ConversationEntrySource.XiaoAi, conversationEntrySource("archive-0123abcd", "超级小爱：查天气"))
        assertEquals(ConversationEntrySource.Breeno, conversationEntrySource("archive-breeno-0123abcd", ""))
        assertEquals(ConversationEntrySource.Breeno, conversationEntrySource("archive-0123abcd", "小布对话"))
        assertNull(conversationEntrySource("archive-0123abcd", "改过的名字"))
        assertNull(conversationEntrySource("conv-1", "超级小爱：普通会话不归组"))
        assertNull(conversationEntrySource("assistant-0123abcd", "Eta 语音"))
    }

    @Test
    fun drawerPutsVendorGroupsAfterPinnedAndKeepsTimeSectionsForTheRest() {
        val now = System.currentTimeMillis()
        fun summary(id: String, source: ConversationEntrySource? = null, pinned: Boolean = false) = ConversationSummaryUi(
            id = id, title = id, preview = "", timeLabel = "", updatedAtMillis = now,
            mode = ConversationModeUi.Chat, isPinned = pinned, entrySource = source,
        )
        val keys = listOf(
            summary("chat-1"),
            summary("xiaoai-1", ConversationEntrySource.XiaoAi),
            summary("pinned", pinned = true),
            summary("chat-2"),
            summary("xiaoai-2", ConversationEntrySource.XiaoAi),
            summary("breeno-1", ConversationEntrySource.Breeno),
        ).drawerSectionKeys()
        assertEquals(listOf("Pinned", "Entry(source=XiaoAi)", "Entry(source=Breeno)", "Today"), keys)
    }
}
