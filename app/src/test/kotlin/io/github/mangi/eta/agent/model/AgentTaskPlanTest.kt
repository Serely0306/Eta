package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskPlanTest {
    private fun args(vararg steps: Pair<String, String>, explanation: String? = null) = JSONObject()
        .put("plan", JSONArray().also { array ->
            steps.forEach { (title, status) -> array.put(JSONObject().put("step", title).put("status", status)) }
        })
        .apply { explanation?.let { put("explanation", it) } }
        .toString()

    @Test
    fun currentStepIsTheActiveOneOrFirstPending() {
        val plan = AgentTaskPlan.parse(args("读取日志" to "completed", "定位错误" to "in_progress", "修复" to "pending"))
            .getOrThrow()
        assertEquals(1, plan.currentIndex)
        assertEquals(1, plan.completedCount)

        val notStarted = AgentTaskPlan.parse(args("a" to "completed", "b" to "pending")).getOrThrow()
        assertEquals(1, notStarted.currentIndex)

        val done = AgentTaskPlan.parse(args("a" to "completed")).getOrThrow()
        assertNull(done.currentIndex)
    }

    @Test
    fun normalizedJsonRoundTripsThroughEventPayload() {
        val plan = AgentTaskPlan.parse(args("a" to "in_progress", "b" to "pending", explanation = "改为先备份")).getOrThrow()
        assertEquals(plan, AgentTaskPlan.parse(plan.toJson()).getOrThrow())
    }

    @Test
    fun invalidPlansAreRejectedWithReasons() {
        fun error(raw: String) = AgentTaskPlan.parse(raw).exceptionOrNull()?.message.orEmpty()
        assertTrue(error(args("a" to "in_progress", "b" to "in_progress")).contains("in_progress"))
        assertTrue(error(args("a" to "doing")).contains("status"))
        assertTrue(error(args("  " to "pending")).contains("描述"))
        assertTrue(error("""{"plan":[]}""").contains("步骤"))
        assertTrue(AgentTaskPlan.parse("not json").isFailure)
    }

    @Test
    fun schemaDeclaresPlanArrayWithStatusEnum() {
        val parameters = AgentTaskPlan.schema().getJSONObject("function").getJSONObject("parameters")
        val status = parameters.getJSONObject("properties").getJSONObject("plan")
            .getJSONObject("items").getJSONObject("properties").getJSONObject("status")
        assertEquals(listOf("pending", "in_progress", "completed"), (0 until status.getJSONArray("enum").length()).map {
            status.getJSONArray("enum").getString(it)
        })
    }
}
