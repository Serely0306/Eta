package io.github.mangi.eta.agent.terminal

import org.json.JSONArray
import org.json.JSONObject

/**
 * Schema 与执行前校验共用动作合同。
 *
 * 分级处理：与当前 action 无关的无害字段（超时、身份、分页等）直接忽略并在结果中注明；
 * 会改变命令含义的字段与组合（错误 action 上的 command/session_id、会话与环境字段冲突、
 * close 二选一）以及非法取值仍然拒绝，避免按猜测执行有副作用的命令。
 */
internal object TerminalToolContract {
    data class Failure(val code: String, val message: String)

    /** 通过校验的参数：与当前 action 无关的无害字段已剔除，名称记入 [ignored]。 */
    data class Normalized(val args: JSONObject, val ignored: List<String>)

    private data class Action(val required: Set<String>, val optional: Set<String>) {
        val fields: Set<String> get() = required + optional + "action"
    }

    private val environmentFields = setOf("cwd", "identity", "environment")
    /** 用错 action 会丢失调用意图的字段；其余无关字段可以安全忽略。 */
    private val intentFields = setOf("command", "session_id")
    private val actions = linkedMapOf(
        "open" to Action(emptySet(), environmentFields),
        "exec" to Action(setOf("command"), environmentFields + setOf("session_id", "timeout_ms", "merge_stderr", "async")),
        "read_async_result" to Action(setOf("job_id"), setOf("offset_chars", "max_chars", "close_if_done")),
        "close" to Action(emptySet(), setOf("session_id", "job_id")),
        "daemon_start" to Action(setOf("command"), environmentFields),
        "daemon_list" to Action(emptySet(), emptySet()),
        "daemon_logs" to Action(setOf("task_id"), emptySet()),
        "daemon_stop" to Action(setOf("task_id"), emptySet()),
    )

    fun schema(): JSONObject {
        val properties = properties()
        val branches = JSONArray()
        actions.forEach { (name, action) ->
            val branch = JSONObject()
                .put("properties", JSONObject().put("action", JSONObject().put("enum", JSONArray().put(name))))
                .put("required", JSONArray((action.required + "action").toList()))
            val forbidden = JSONArray(
                intentFields.filter { it !in action.fields }.map { JSONObject().put("required", JSONArray().put(it)) },
            )
            if (name == "exec") {
                (environmentFields + "async").forEach { field ->
                    val conflict = JSONObject().put("required", JSONArray().put("session_id").put(field))
                    if (field == "async") conflict.put("properties", JSONObject().put("async", JSONObject().put("enum", JSONArray().put(true))))
                    forbidden.put(conflict)
                }
            }
            if (forbidden.length() > 0) branch.put("not", JSONObject().put("anyOf", forbidden))
            if (name == "close") branch.put("oneOf", JSONArray()
                .put(JSONObject().put("required", JSONArray().put("session_id")))
                .put(JSONObject().put("required", JSONArray().put("job_id"))))
            branches.put(branch)
        }
        return JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray().put("action"))
            .put("oneOf", branches)
    }

    /** 校验并剔除无害的无关字段；冲突或非法取值返回 [Failure]。 */
    fun normalize(args: JSONObject): Pair<Normalized?, Failure?> {
        val actionName = args.opt("action") as? String
            ?: return null to invalid("action 必须是非空字符串")
        val canonicalAction = if (actionName == "open_and_exec") "exec" else actionName
        val action = actions[canonicalAction]
            ?: return null to Failure("UNSUPPORTED_TERMINAL_ACTION", "不支持的 terminal action")
        val properties = properties()
        val relevant = JSONObject()
        val ignored = mutableListOf<String>()
        args.keys().forEach { key ->
            if (key !in action.fields) {
                if (key in intentFields) return null to invalid(misplacedIntentMessage(actionName, key))
                ignored += key
                return@forEach
            }
            val value = args.opt(key)
            val spec = properties.getJSONObject(key)
            when (spec.getString("type")) {
                "string" -> {
                    if (value !is String || value.isBlank()) return null to invalid("$key 必须是非空字符串")
                    val allowed = spec.optJSONArray("enum")
                    val legacyValue = key == "action" && value == "open_and_exec" ||
                        key == "environment" && value in setOf("alpine", "debian")
                    if (!legacyValue && allowed != null && (0 until allowed.length()).none { allowed.getString(it) == value }) {
                        return null to invalid("$key 不是支持的取值")
                    }
                    if (spec.has("maxLength") && value.length > spec.getInt("maxLength")) return null to invalid("$key 过长")
                }
                "boolean" -> if (value !is Boolean) return null to invalid("$key 必须是布尔值")
                "integer" -> {
                    if (value !is Number || !value.toDouble().isFinite() || value.toDouble() != value.toLong().toDouble()) {
                        return null to invalid("$key 必须是整数")
                    }
                    if (value.toLong() < spec.optLong("minimum", Long.MIN_VALUE) || value.toLong() > spec.optLong("maximum", Long.MAX_VALUE)) {
                        return null to invalid("$key 超出允许范围")
                    }
                }
            }
            relevant.put(key, value)
        }
        action.required.firstOrNull { !relevant.has(it) }?.let { return null to invalid("$actionName 缺少参数 $it") }
        if (canonicalAction == "exec" && relevant.has("session_id")) {
            if (relevant.optBoolean("async")) return null to Failure("ASYNC_SESSION_UNSUPPORTED", "async 不复用持久会话，请省略 session_id")
            environmentFields.firstOrNull { relevant.has(it) }?.let {
                return null to invalid("session_id 已确定执行环境，不能同时提供 $it；需要切换目录请在会话中执行 cd")
            }
        }
        if (canonicalAction == "close" && relevant.has("session_id") == relevant.has("job_id")) {
            return null to invalid("close 必须且只能提供 session_id 或 job_id")
        }
        return Normalized(relevant, ignored) to null
    }

    fun validate(args: JSONObject): Failure? = normalize(args).second

    private fun misplacedIntentMessage(action: String, key: String): String = when (key) {
        "command" -> "$action 不执行命令；执行命令请用 exec（复用会话时传 session_id）或 daemon_start"
        else -> "$action 不接受 session_id；session_id 只用于 exec 或 close"
    }

    private fun invalid(message: String) = Failure("INVALID_ARGUMENT", message)

    private fun properties(): JSONObject = JSONObject()
        .put("action", text("终端动作。exec 是统一命令入口。").put("enum", JSONArray(actions.keys.toList())))
        .put("command", text("仅 exec/daemon_start，且为必填；使用实际环境支持的 Shell 语法。").put("maxLength", 4_000))
        .put("identity", text("仅 open/exec/daemon_start；exec 带 session_id 时不可提供。宿主执行身份，user 是 App UID；Android 默认当前可用身份，Linux 由所选后端决定。").put("enum", JSONArray().put("user").put("root")))
        .put("environment", text("仅 open/exec/daemon_start；exec 带 session_id 时不可提供。android 使用 Android 命令；linux 使用所选发行版。默认 android。").put("enum", JSONArray().put("android").put("linux")))
        .put("cwd", text("仅 open/exec/daemon_start；exec 带 session_id 时不可提供（在会话中执行 cd）。Android user 默认 Eta 私有工作区，root 默认 /data/local/tmp/eta；Linux 默认 /workspace。相对路径相对于默认工作区；~ 在 user 下为私有工作区、root 下为公共存储、Linux 下为 /root。"))
        .put("session_id", text("仅 exec/close。open 返回的持久会话 ID，复用会话已有目录、环境和身份。"))
        .put("job_id", text("仅 read_async_result/close。async exec 返回的任务 ID。"))
        .put("task_id", text("仅 daemon_logs/daemon_stop。daemon_start 返回的任务 ID。"))
        .put("timeout_ms", integer("仅 exec。超时毫秒，默认 30000；超时会终止命令，会话内超时也会关闭会话。", 1_000, 180_000))
        .put("merge_stderr", bool("仅 exec。是否把 stderr 合并到 stdout，默认 false。"))
        .put("async", bool("仅 exec。是否独立异步执行，默认 false；true 不能与 session_id 组合。"))
        .put("offset_chars", integer("仅 read_async_result。stdout 字符偏移，默认 0。", 0, Int.MAX_VALUE))
        .put("max_chars", integer("仅 read_async_result。每页 stdout 字符上限，默认 8000。", 1, 16_000))
        .put("close_if_done", bool("仅 read_async_result。任务结束且本页已到保留输出末尾时释放任务，默认 false。"))

    private fun text(description: String) = JSONObject().put("type", "string").put("minLength", 1).put("description", description)
    private fun bool(description: String) = JSONObject().put("type", "boolean").put("description", description)
    private fun integer(description: String, minimum: Int, maximum: Int) = JSONObject()
        .put("type", "integer").put("minimum", minimum).put("maximum", maximum).put("description", description)
}
