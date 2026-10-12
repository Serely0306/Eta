package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * ask_user 的问题模型。Runtime、App 问题面板与状态胶囊共用同一份解析结果，
 * 回答统一折叠为 [AgentUserAnswer] 后作为工具结果交回模型。
 */
internal data class AgentUserQuestion(val items: List<Item>) {
    data class Item(
        val question: String,
        val header: String,
        val options: List<Option>,
        val multiSelect: Boolean,
    )

    data class Option(val label: String, val description: String)

    fun toJson(): String = JSONObject().put("questions", JSONArray().also { array ->
        items.forEach { item ->
            array.put(JSONObject()
                .put("question", item.question)
                .put("header", item.header)
                .put("multi_select", item.multiSelect)
                .put("options", JSONArray().also { options ->
                    item.options.forEach { option ->
                        options.put(JSONObject().put("label", option.label).put("description", option.description))
                    }
                }))
        }
    }).toString()

    companion object {
        const val TOOL_NAME = "ask_user"
        /** 无人回答时按跳过处理，避免任务与前台服务无限期挂起。 */
        const val TIMEOUT_MS = 10 * 60 * 1000L
        private const val MAX_QUESTIONS = 4
        private const val MIN_OPTIONS = 2
        private const val MAX_OPTIONS = 4

        fun parse(raw: String): AgentUserQuestion? = runCatching {
            val array = JSONObject(raw).getJSONArray("questions")
            val items = (0 until minOf(array.length(), MAX_QUESTIONS)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val options = item.optJSONArray("options") ?: JSONArray()
                Item(
                    question = item.optString("question").trim(),
                    header = item.optString("header").trim(),
                    options = (0 until minOf(options.length(), MAX_OPTIONS)).mapNotNull { optionIndex ->
                        val option = options.optJSONObject(optionIndex) ?: return@mapNotNull null
                        Option(option.optString("label").trim(), option.optString("description").trim())
                            .takeIf { it.label.isNotBlank() }
                    },
                    multiSelect = item.optBoolean("multi_select", false),
                ).takeIf { it.question.isNotBlank() }
            }
            AgentUserQuestion(items).takeIf { items.isNotEmpty() }
        }.getOrNull()

        fun schema(): JSONObject = AgentToolSchema.function(
            name = TOOL_NAME,
            description = "运行中向用户提问并等待回答，本次任务不会结束。只在缺少会明显影响结果、且无法从上下文或工具合理推断的关键信息时使用；" +
                "需要用户补充信息才能继续时优先用本工具，而不是先结束回答再在正文里提问。" +
                "需要用户亲手完成某个界面步骤时，也可用本工具说明要做什么并提供“已完成”等选项，等待确认后继续。" +
                "每次 1–4 个问题，每题 2–4 个互斥选项（multi_select=true 时可多选）；用户总能输入自定义答案，不要添加“其他”选项。" +
                "返回 status：answered 时读取 answers 或 text；skipped 表示用户选择不回答，timeout 表示长时间无人回答，" +
                "这两种情况按最合理的假设继续或说明需要用户补充的内容，不要立即重复同一问题。",
            parameters = JSONObject().put("type", "object").put("required", JSONArray().put("questions"))
                .put("properties", JSONObject().put("questions", JSONObject()
                    .put("type", "array").put("minItems", 1).put("maxItems", MAX_QUESTIONS)
                    .put("items", JSONObject().put("type", "object")
                        .put("required", JSONArray().put("question").put("options"))
                        .put("properties", JSONObject()
                            .put("question", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 300)
                                .put("description", "完整的问题，以问号结尾。"))
                            .put("header", JSONObject().put("type", "string").put("maxLength", 16)
                                .put("description", "可选的短标签，如“时间”“收件人”。"))
                            .put("multi_select", JSONObject().put("type", "boolean").put("default", false))
                            .put("options", JSONObject().put("type", "array")
                                .put("minItems", MIN_OPTIONS).put("maxItems", MAX_OPTIONS)
                                .put("items", JSONObject().put("type", "object").put("required", JSONArray().put("label"))
                                    .put("properties", JSONObject()
                                        .put("label", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 40))
                                        .put("description", JSONObject().put("type", "string").put("maxLength", 120))))))))),
        )
    }
}

/** 用户对一次提问的处理结果。 */
internal sealed interface AgentUserAnswer {
    val status: String

    /** 按问题顺序给出所选选项标签；未作答的问题为空列表。 */
    data class Selected(val selections: List<List<String>>) : AgentUserAnswer {
        override val status: String get() = STATUS_ANSWERED
    }

    /** 用户在输入框或胶囊里直接写下的回答，覆盖全部问题。 */
    data class Text(val text: String) : AgentUserAnswer {
        override val status: String get() = STATUS_ANSWERED
    }

    data object Skipped : AgentUserAnswer {
        override val status: String get() = STATUS_SKIPPED
    }

    data object TimedOut : AgentUserAnswer {
        override val status: String get() = STATUS_TIMEOUT
    }

    fun toWireJson(): String = when (this) {
        is Selected -> JSONObject().put("status", status)
            .put("selections", JSONArray(selections.map { JSONArray(it) }))
        is Text -> JSONObject().put("status", status).put("text", text)
        Skipped, TimedOut -> JSONObject().put("status", status)
    }.toString()

    fun toToolResult(question: AgentUserQuestion?): String = when (this) {
        is Selected -> JSONObject().put("status", status).put("answers", JSONArray().also { answers ->
            selections.forEachIndexed { index, selected ->
                answers.put(JSONObject()
                    .put("question", question?.items?.getOrNull(index)?.question.orEmpty())
                    .put("selected", JSONArray(selected)))
            }
        })
        is Text -> JSONObject().put("status", status).put("text", text)
        Skipped -> JSONObject().put("status", status).put("message", "用户跳过了这个问题")
        TimedOut -> JSONObject().put("status", status).put("message", "长时间没有收到回答")
    }.toString()

    companion object {
        const val STATUS_ANSWERED = "answered"
        const val STATUS_SKIPPED = "skipped"
        const val STATUS_TIMEOUT = "timeout"
        private const val MAX_TEXT_CHARS = 2_000

        fun fromWireJson(raw: String): AgentUserAnswer? = runCatching {
            val json = JSONObject(raw)
            when (json.getString("status")) {
                STATUS_SKIPPED -> Skipped
                STATUS_TIMEOUT -> TimedOut
                STATUS_ANSWERED -> json.optJSONArray("selections")?.let { array ->
                    Selected((0 until array.length()).map { index ->
                        val selected = array.optJSONArray(index) ?: JSONArray()
                        (0 until selected.length()).map(selected::optString).filter(String::isNotBlank)
                    })
                } ?: json.optString("text").trim().takeIf(String::isNotBlank)?.let { Text(it.take(MAX_TEXT_CHARS)) }
                else -> null
            }
        }.getOrNull()

        fun text(raw: String): Text = Text(raw.trim().take(MAX_TEXT_CHARS))
    }
}
