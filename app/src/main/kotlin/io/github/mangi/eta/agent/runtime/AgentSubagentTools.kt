package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主 Agent 使用子 Agent 的六个工具（AG-19，机制参考 Codex）：派出、发消息、等待、查看、停止、关闭。
 * 子 Agent 属于会话，跨轮次保留上下文；派出与发消息都立即返回，主 Agent 可以继续做别的。
 */
internal class AgentSubagentTools(
    private val manager: AgentSubagentManager,
    private val environment: SubagentEnvironment,
    private val parentController: AgentRunController,
) {
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val arguments = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrElse {
            return result(invalid("参数不是有效的 JSON"))
        }
        val response = when (call.name) {
            SPAWN -> {
                val task = arguments.optString("task").trim()
                if (task.isEmpty()) return result(invalid("缺少 task"))
                manager.spawn(
                    environment,
                    task = task.take(MAX_TASK_CHARS),
                    context = arguments.optString("context").trim().take(MAX_CONTEXT_CHARS),
                    name = arguments.optString("name").takeIf(String::isNotBlank),
                    reason = arguments.optString("reason").trim().take(MAX_REASON_CHARS),
                )
            }
            SEND -> {
                val target = arguments.optString("agent_id").trim()
                val message = arguments.optString("message").trim()
                if (target.isEmpty() || message.isEmpty()) return result(invalid("需要 agent_id 与 message"))
                manager.send(environment, target, message.take(MAX_TASK_CHARS), arguments.optBoolean("interrupt"))
            }
            WAIT -> {
                val seconds = arguments.optInt("timeout_seconds", DEFAULT_WAIT_SECONDS)
                    .coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS)
                manager.wait(environment, ids(arguments), seconds * 1_000L, parentController)
            }
            LIST -> manager.list(environment, ids(arguments))
            STOP, CLOSE -> {
                val target = arguments.optString("agent_id").trim()
                if (target.isEmpty()) return result(invalid("缺少 agent_id"))
                if (call.name == STOP) manager.stop(environment, target) else manager.close(environment, target)
            }
            else -> invalid("未知的子 Agent 工具")
        }
        return result(response)
    }

    private fun ids(arguments: JSONObject): List<String> {
        val array = arguments.optJSONArray("agent_ids") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotEmpty) }
    }

    private fun result(json: JSONObject) = AgentModelClient.ToolResult(json.toString())

    private fun invalid(message: String) = JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT").put("message", message)

    internal companion object {
        const val SPAWN = "spawn_agent"
        const val SEND = "send_agent"
        const val WAIT = "wait_agents"
        const val LIST = "list_agents"
        const val STOP = "stop_agent"
        const val CLOSE = "close_agent"
        val TOOL_NAMES = setOf(SPAWN, SEND, WAIT, LIST, STOP, CLOSE)

        private const val MAX_TASK_CHARS = 4_000
        private const val MAX_CONTEXT_CHARS = 8_000
        private const val MAX_REASON_CHARS = 120
        private const val DEFAULT_WAIT_SECONDS = 30
        private const val MIN_WAIT_SECONDS = 10
        private const val MAX_WAIT_SECONDS = 600

        fun schemas(): List<JSONObject> = listOf(
            AgentToolSchema.function(
                name = SPAWN,
                description = "派出一个子 Agent 处理相互独立、需要大量读取或检索的子问题，立即返回 agent_id，你可以继续做别的。" +
                    "子 Agent 有独立上下文，使用与你相同的模型和非界面工具（终端、文件、网页等），不能操作手机界面、不能提问、看不到当前对话，" +
                    "所以 task 要写清目标、范围与期望的结论形式，必要背景放进 context。它完成后结论会作为通知自动交给你；需要结果才能继续时用 wait_agents。" +
                    "子 Agent 属于当前会话，之后的轮次也能用 send_agent 继续交给它，它记得之前的内容。" +
                    "一两次工具调用能完成的、需要操作界面的、需要和用户来回确认的事自己做，不要派。",
                parameters = objectSchema(
                    required = listOf("task", "reason"),
                    "task" to stringSchema("子任务的目标、范围与期望的结论形式", MAX_TASK_CHARS),
                    "context" to stringSchema("可选；子 Agent 需要的背景，如路径、已知结论、限制条件", MAX_CONTEXT_CHARS),
                    "name" to JSONObject().put("type", "string").put("pattern", "^[a-z0-9_]{1,32}$")
                        .put("description", "可选；便于之后称呼的名字，小写字母、数字、下划线，会话内唯一"),
                    "reason" to stringSchema("一句话说明为什么交给子 Agent，显示给用户", MAX_REASON_CHARS),
                ),
            ),
            AgentToolSchema.function(
                name = SEND,
                description = "给已有的子 Agent 发新任务或补充说明，它带着之前的上下文继续。运行中时排进它的下一轮；空闲时开始新回合。" +
                    "interrupt 为 true 时先停掉它当前的回合。",
                parameters = objectSchema(
                    required = listOf("agent_id", "message"),
                    "agent_id" to stringSchema("子 Agent 的 agent_id 或名字", 64),
                    "message" to stringSchema("要交给它的任务或补充", MAX_TASK_CHARS),
                    "interrupt" to JSONObject().put("type", "boolean").put("description", "可选；先停掉它当前的回合"),
                ),
            ),
            AgentToolSchema.function(
                name = WAIT,
                description = "等子 Agent 出结果：指定的（省略则为全部运行中的）任一结束、用户追加了输入、或超时就返回，结束的会带上结论。" +
                    "只在确实需要结果才能继续时使用。",
                parameters = objectSchema(
                    required = emptyList(),
                    "agent_ids" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                        .put("description", "可选；要等的子 Agent"),
                    "timeout_seconds" to JSONObject().put("type", "integer").put("minimum", MIN_WAIT_SECONDS)
                        .put("maximum", MAX_WAIT_SECONDS).put("description", "可选；默认 $DEFAULT_WAIT_SECONDS 秒"),
                ),
            ),
            AgentToolSchema.function(
                name = LIST,
                description = "查看本会话的子 Agent：状态（运行中、已完成、失败、已中断、已关闭）、任务、回合数与结论；不等待。",
                parameters = objectSchema(
                    required = emptyList(),
                    "agent_ids" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                        .put("description", "可选；只看这些"),
                ),
            ),
            AgentToolSchema.function(
                name = STOP,
                description = "停掉子 Agent 当前的回合；它变为已中断，之后仍可用 send_agent 继续。",
                parameters = objectSchema(required = listOf("agent_id"), "agent_id" to stringSchema("子 Agent 的 agent_id 或名字", 64)),
            ),
            AgentToolSchema.function(
                name = CLOSE,
                description = "不再使用这个子 Agent：运行中会先停掉，之后不能再给它发消息；它的过程仍保留供查看。",
                parameters = objectSchema(required = listOf("agent_id"), "agent_id" to stringSchema("子 Agent 的 agent_id 或名字", 64)),
            ),
        )

        private fun stringSchema(description: String, maxLength: Int) =
            JSONObject().put("type", "string").put("maxLength", maxLength).put("description", description)

        private fun objectSchema(required: List<String>, vararg properties: Pair<String, JSONObject>): JSONObject =
            JSONObject().put("type", "object").put("required", JSONArray(required))
                .put("properties", JSONObject().apply { properties.forEach { (name, schema) -> put(name, schema) } })
    }
}
