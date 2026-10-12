package io.github.mangi.eta.agent.automation

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAutomationTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun automation(trigger: AutomationTrigger, createdAt: Long = at("2026-10-12T07:00"), lastRunAt: Long? = null) =
        AgentAutomation(id = "a", name = "天气", prompt = "看看天气", trigger = trigger, createdAt = createdAt, lastRunAt = lastRunAt)

    @Test
    fun dailyFiresTodayIfStillAheadOtherwiseTomorrowAndRespectsWeekdays() {
        val daily = automation(AutomationTrigger.Daily(8, 0))
        assertEquals(at("2026-10-12T08:00"), AutomationSchedule.nextFireAt(daily, at("2026-10-12T07:30"), zone))
        assertEquals(at("2026-10-13T08:00"), AutomationSchedule.nextFireAt(daily, at("2026-10-12T08:00"), zone))
        // 2026-10-12 是周一；只在周六周日触发时跳到 10-17。
        val weekend = automation(AutomationTrigger.Daily(8, 0, setOf(6, 7)))
        assertEquals(at("2026-10-17T08:00"), AutomationSchedule.nextFireAt(weekend, at("2026-10-12T07:30"), zone))
        assertNull(AutomationSchedule.nextFireAt(automation(AutomationTrigger.Daily(25, 0)), at("2026-10-12T07:30"), zone))
    }

    @Test
    fun intervalSkipsMissedPeriodsAndRejectsTooShortIntervals() {
        val hourly = automation(AutomationTrigger.Interval(60), createdAt = at("2026-10-12T07:00"))
        assertEquals(at("2026-10-12T08:00"), AutomationSchedule.nextFireAt(hourly, at("2026-10-12T07:10"), zone))
        // 关机错过两个周期后不补跑，直接排到当前时间之后的下一个周期。
        assertEquals(at("2026-10-12T10:00"), AutomationSchedule.nextFireAt(hourly, at("2026-10-12T09:30"), zone))
        assertNull(AutomationSchedule.nextFireAt(automation(AutomationTrigger.Interval(5)), at("2026-10-12T07:10"), zone))
    }

    @Test
    fun onceFiresOnlyInTheFutureAndOnlyOnce() {
        val once = automation(AutomationTrigger.Once(at("2026-10-12T09:00")))
        assertEquals(at("2026-10-12T09:00"), AutomationSchedule.nextFireAt(once, at("2026-10-12T08:00"), zone))
        assertNull(AutomationSchedule.nextFireAt(once, at("2026-10-12T09:01"), zone))
        assertNull(AutomationSchedule.nextFireAt(once.copy(lastRunAt = at("2026-10-12T09:00")), at("2026-10-12T08:00"), zone))
    }

    @Test
    fun notificationTriggersNeedAFilterAndHonorCooldown() {
        val trigger = AutomationTrigger.OnNotification(packageName = "com.tencent.mm", keyword = "快递", cooldownMinutes = 10)
        assertTrue(AutomationSchedule.matches(trigger, "com.tencent.mm", "顺丰快递", ""))
        assertFalse(AutomationSchedule.matches(trigger, "com.android.mms", "顺丰快递", ""))
        assertFalse(AutomationSchedule.matches(trigger, "com.tencent.mm", "群消息", "吃饭了吗"))
        assertFalse(AutomationSchedule.matches(AutomationTrigger.OnNotification(), "any", "any", "any"))
        assertTrue(AutomationSchedule.matches(AutomationTrigger.OnNotification(keyword = "Code"), "x", "", "your code is 1234"))

        val recent = automation(trigger, lastRunAt = at("2026-10-12T08:00"))
        assertFalse(AutomationSchedule.cooledDown(recent, trigger, at("2026-10-12T08:05")))
        assertTrue(AutomationSchedule.cooledDown(recent, trigger, at("2026-10-12T08:10")))
        assertNull(AutomationSchedule.nextFireAt(recent, at("2026-10-12T08:05"), zone))
    }

    @Test
    fun automationsRoundTripThroughStoredJson() {
        val list = listOf(
            automation(AutomationTrigger.Daily(8, 30, setOf(1, 2, 3, 4, 5))),
            automation(AutomationTrigger.OnNotification(keyword = "验证码")).copy(id = "b", enabled = false),
            automation(AutomationTrigger.Once(at("2026-10-13T09:00"))).copy(id = "c", lastStatus = AgentAutomation.STATUS_COMPLETED),
        )
        assertEquals(list, AgentAutomationStore.decode(AgentAutomationStore.encode(list)))
        assertEquals(emptyList<AgentAutomation>(), AgentAutomationStore.decode("not json"))
    }

    @Test
    fun timeParsingAcceptsLocalFormats() {
        assertEquals(8 to 5, AutomationSchedule.parseTime("08:05"))
        assertNull(AutomationSchedule.parseTime("8点"))
        assertEquals(at("2026-10-13T09:00"), AutomationSchedule.parseLocalDateTime("2026-10-13 09:00", zone))
        assertNull(AutomationSchedule.parseLocalDateTime("明天", zone))
    }
}
