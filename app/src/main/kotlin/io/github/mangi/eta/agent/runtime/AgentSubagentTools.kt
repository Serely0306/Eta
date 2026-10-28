package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主 Agent 使用子 Agent 的四个工具（AG-27，机制按 Claude Code）：派出、发消息、查看、停止。
 * 子 Agent 属于会话，跨轮次保留上下文；派出与发消息都立即返回，结果作为通知交回，没有等待工具；
 * 模型不能关闭子 Agent，关闭只留给用户。
 */
internal class AgentSubagentTools(
    private val manager: AgentSubagentManager,
    private val environment: SubagentEnvironment,
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
                manager.send(environment, target, message.take(MAX_TASK_CHARS))
            }
            LIST -> manager.list(environment, ids(arguments))
            STOP -> {
                val target = arguments.optString("agent_id").trim()
                if (target.isEmpty()) return result(invalid("缺少 agent_id"))
                manager.stop(environment, target)
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
        const val LIST = "list_agents"
        const val STOP = "stop_agent"
        val TOOL_NAMES = setOf(SPAWN, SEND, LIST, STOP)

        private const val MAX_TASK_CHARS = 4_000
        private const val MAX_CONTEXT_CHARS = 8_000
        private const val MAX_REASON_CHARS = 120

        fun schemas(): List<JSONObject> = listOf(
            AgentToolSchema.function(
                name = SPAWN,
                description = "派出一个子 Agent 在后台处理相互独立、需要大量读取或检索的子问题，立即返回 agent_id。" +
                    "派出后继续做别的，或先结束本轮回答；它完成后结论会作为通知交给你，不要猜测或预先描述还没出来的结果。" +
                    "子 Agent 有独立上下文，使用与你相同的模型和非界面工具（终端、文件、网页等），不能操作手机界面、不能提问、看不到当前对话，" +
                    "所以 task 要写清目标、范围与期望的结论形式，必要背景放进 context。" +
                    "派出前先看本会话已有的子 Agent：做过相关工作的，用 send_agent 交给它，不要新开。" +
                    "一两次工具调用、本机几条命令能查清的、需要操作界面的、需要和用户来回确认的事自己做，不要派。",
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
                description = "给本会话已有的子 Agent 发新任务或补充说明，它带着之前的全部上下文继续，立即返回。" +
                    "它正在运行时，消息在它下一次请求模型前送达；已写完本回合结论的，结论照常交给你，消息作为下一回合的开头；" +
                    "空闲时开始新回合。要改方向先 stop_agent 再发。",
                parameters = objectSchema(
                    required = listOf("agent_id", "message"),
                    "agent_id" to stringSchema("子 Agent 的 agent_id 或名字", 64),
                    "message" to stringSchema("要交给它的任务或补充", MAX_TASK_CHARS),
                ),
            ),
            AgentToolSchema.function(
                name = LIST,
                description = "查看本会话的子 Agent，不等待：状态（运行中、已完成、失败、已中断、已关闭）、任务、回合数；" +
                    "运行中的附进度（已运行时间、本回合轮数、最近的工具与最新一段中间说明）；还没交给你的结论附全文。",
                parameters = objectSchema(
                    required = emptyList(),
                    "agent_ids" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                        .put("description", "可选；只看这些"),
                ),
            ),
            AgentToolSchema.function(
                name = STOP,
                description = "停掉子 Agent 当前的回合；它变为已中断并通知你，之后仍可用 send_agent 继续。",
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
