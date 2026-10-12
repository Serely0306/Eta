package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * update_plan 维护的任务计划。只用于跟踪进度：模型每次提交完整计划，Runtime 校验后以事件推给
 * 胶囊、通知与电脑端，工具本身不改变任何设备状态。
 */
internal data class AgentTaskPlan(
    val steps: List<Step>,
    val explanation: String = "",
) {
    enum class Status(val wire: String) {
        PENDING("pending"),
        IN_PROGRESS("in_progress"),
        COMPLETED("completed"),
    }

    data class Step(val title: String, val status: Status)

    val completedCount: Int get() = steps.count { it.status == Status.COMPLETED }

    /** 当前步骤的下标：进行中的一步；没有时取第一个待办；全部完成时为 null。 */
    val currentIndex: Int?
        get() = steps.indexOfFirst { it.status == Status.IN_PROGRESS }.takeIf { it >= 0 }
            ?: steps.indexOfFirst { it.status == Status.PENDING }.takeIf { it >= 0 }

    fun toJson(): String = JSONObject()
        .put("steps", JSONArray().also { array ->
            steps.forEach { step -> array.put(JSONObject().put("step", step.title).put("status", step.status.wire)) }
        })
        .apply { if (explanation.isNotBlank()) put("explanation", explanation) }
        .toString()

    companion object {
        const val TOOL_NAME = "update_plan"
        private const val MAX_STEPS = 20
        private const val MAX_STEP_CHARS = 120
        private const val MAX_EXPLANATION_CHARS = 300

        /** 解析模型参数（plan 数组）或事件中的规范化 JSON（steps 数组）。格式错误时返回原因。 */
        fun parse(raw: String): Result<AgentTaskPlan> = runCatching {
            val json = JSONObject(raw)
            val array = json.optJSONArray("plan") ?: json.optJSONArray("steps")
                ?: throw IllegalArgumentException("缺少 plan 数组")
            require(array.length() in 1..MAX_STEPS) { "计划需要 1–$MAX_STEPS 个步骤" }
            val steps = (0 until array.length()).map { index ->
                val item = array.optJSONObject(index) ?: throw IllegalArgumentException("第 ${index + 1} 步格式无效")
                val title = item.optString("step").trim()
                require(title.isNotEmpty()) { "第 ${index + 1} 步缺少描述" }
                val status = Status.entries.firstOrNull { it.wire == item.optString("status") }
                    ?: throw IllegalArgumentException("第 ${index + 1} 步的 status 只能是 pending、in_progress 或 completed")
                Step(title.take(MAX_STEP_CHARS), status)
            }
            require(steps.count { it.status == Status.IN_PROGRESS } <= 1) { "同一时刻最多一个步骤为 in_progress" }
            AgentTaskPlan(steps, json.optString("explanation").trim().take(MAX_EXPLANATION_CHARS))
        }

        fun schema(): JSONObject = AgentToolSchema.function(
            name = TOOL_NAME,
            description = "为需要多个步骤的任务建立并更新计划，让用户随时看到进度。每次提交完整计划（全部步骤及状态）；" +
                "开始一步时把它标为 in_progress，完成后标为 completed，同一时刻最多一个 in_progress。" +
                "计划有变时直接提交修改后的完整列表，可在 explanation 简述原因。简单任务（一两步即可完成）不要使用；" +
                "计划只用于跟踪，不代替实际执行，也不要在最终答复里重复整份计划。",
            parameters = JSONObject().put("type", "object").put("required", JSONArray().put("plan"))
                .put("properties", JSONObject()
                    .put("plan", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", MAX_STEPS)
                        .put("items", JSONObject().put("type", "object")
                            .put("required", JSONArray().put("step").put("status"))
                            .put("properties", JSONObject()
                                .put("step", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", MAX_STEP_CHARS)
                                    .put("description", "一句话描述该步骤要达成的结果"))
                                .put("status", JSONObject().put("type", "string")
                                    .put("enum", JSONArray(Status.entries.map { it.wire }))))))
                    .put("explanation", JSONObject().put("type", "string").put("maxLength", MAX_EXPLANATION_CHARS)
                        .put("description", "可选；计划调整的原因"))),
        )
    }
}
