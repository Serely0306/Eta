package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundNoticeTextTest {
    @Test
    fun markdownConclusionBecomesOneCleanLine() {
        val conclusion = "## 配置结论\n\n**未发现 Scene 中阻止 `com.google.android.gms.unstable` 启动的显式设置、子进程黑名单或对应 Java hook，" +
            "现有证据不足以归因于 Scene。** 没有把 `process is bad` 当作崩溃证据。\n\n源目录：`/data/user/0/com.omarea.vtools`"
        val summary = BackgroundNoticeText.summary(conclusion)
        assertFalse(summary, summary.contains("#") || summary.contains("*") || summary.contains("`"))
        assertFalse(summary.startsWith("配置结论"))
        // 包名里的点不算句末；超长时在逗号处截断。
        assertEquals("未发现 Scene 中阻止 com.google.android.gms.unstable 启动的显式设置、子进程黑名单或对应 Java hook…", summary)
    }

    @Test
    fun shortTextsAndFirstSentencesAreKeptWhole() {
        assertEquals("下载目录占 2 GB", BackgroundNoticeText.summary("下载目录占 2 GB"))
        assertEquals("无异常。", BackgroundNoticeText.summary("无异常。后面还有很多说明。"))
        assertEquals("Done.", BackgroundNoticeText.summary("Done. See details below."))
        assertEquals("sub_2 与 scene_start_audit 都正常", BackgroundNoticeText.summary("sub_2 与 scene_start_audit 都正常"))
        assertEquals("链接 文档", BackgroundNoticeText.plain("[链接](https://example.invalid) _文档_"))
    }

    @Test
    fun longTextWithoutPunctuationIsCutWithEllipsis() {
        val summary = BackgroundNoticeText.summary("长".repeat(200))
        assertEquals(81, summary.length)
        assertTrue(summary.endsWith("…"))
    }
}
