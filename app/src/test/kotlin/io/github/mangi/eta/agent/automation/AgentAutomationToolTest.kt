package io.github.mangi.eta.agent.automation

import io.github.mangi.eta.config.Prefs
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentAutomationToolTest {
    private val app = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.parse("2026-10-12T07:00").atZone(zone).toInstant().toEpochMilli()
    private val tool by lazy { AgentAutomationTool(app, now = { now }, zone = { zone }) }

    @Before
    fun setUp() {
        Prefs.initLocal(app)
        AgentAutomationStore.all().forEach { AgentAutomationStore.delete(it.id) }
    }

    private fun call(args: JSONObject) = JSONObject(tool.execute(args))

    @Test
    fun createUpdateListAndDeleteAutomations() {
        val created = call(
            JSONObject().put("action", "create").put("name", "天气").put("prompt", "看看今天的天气并提醒带伞")
                .put("trigger", JSONObject().put("type", "daily").put("time", "08:00").put("days", JSONArray(listOf(1, 2, 3, 4, 5)))),
        )
        assertTrue(created.toString(), created.getBoolean("ok"))
        val automation = created.getJSONObject("automation")
        val id = automation.getString("id")
        assertEquals("08:00", automation.getJSONObject("trigger").getString("time"))
        assertEquals("2026-10-12 08:00", automation.getString("next_run"))

        val updated = call(JSONObject().put("action", "update").put("id", id).put("enabled", false))
        assertFalse(updated.getJSONObject("automation").getBoolean("enabled"))
        assertEquals("看看今天的天气并提醒带伞", updated.getJSONObject("automation").getString("prompt"))

        val listed = call(JSONObject().put("action", "list"))
        assertEquals(1, listed.getInt("count"))
        assertEquals("default", listed.getJSONArray("automations").getJSONObject(0).getString("model"))
        assertEquals("follow_default", listed.getJSONArray("automations").getJSONObject(0).getString("reasoning_effort"))

        val withEffort = call(JSONObject().put("action", "update").put("id", id).put("reasoning_effort", "high"))
        assertEquals("high", withEffort.getJSONObject("automation").getString("reasoning_effort"))
        assertEquals(io.github.mangi.eta.data.model.ReasoningEffort.HIGH, AgentAutomationStore.get(id)?.reasoningEffort)
        val reset = call(JSONObject().put("action", "update").put("id", id).put("reasoning_effort", ""))
        assertEquals("follow_default", reset.getJSONObject("automation").getString("reasoning_effort"))
        assertEquals(
            "INVALID_ARGUMENT",
            call(JSONObject().put("action", "update").put("id", id).put("reasoning_effort", "turbo")).getString("code"),
        )

        assertTrue(call(JSONObject().put("action", "delete").put("id", id)).getBoolean("ok"))
        assertEquals(0, call(JSONObject().put("action", "list")).getInt("count"))
    }

    @Test
    fun invalidTriggersAreRejectedWithReasons() {
        fun create(trigger: JSONObject) = call(
            JSONObject().put("action", "create").put("name", "x").put("prompt", "y").put("trigger", trigger),
        )
        assertEquals("INVALID_ARGUMENT", create(JSONObject().put("type", "interval").put("minutes", 5)).getString("code"))
        assertEquals("INVALID_ARGUMENT", create(JSONObject().put("type", "notification")).getString("code"))
        assertEquals("INVALID_ARGUMENT", create(JSONObject().put("type", "once").put("at", "2026-10-11 09:00")).getString("code"))
        assertEquals("INVALID_ARGUMENT", create(JSONObject().put("type", "daily").put("time", "8点")).getString("code"))
        assertEquals("INVALID_ARGUMENT", call(JSONObject().put("action", "update").put("id", "missing")).getString("code"))
        assertEquals(0, AgentAutomationStore.all().size)
    }
}
