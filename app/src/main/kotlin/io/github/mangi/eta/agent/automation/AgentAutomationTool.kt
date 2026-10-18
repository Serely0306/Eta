package io.github.mangi.eta.agent.automation

import android.content.Context
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * manage_automations：让 Agent 按用户要求创建、修改、删除或立即运行自动任务。
 * 与设置页共用同一份存储与排期；电脑端经设备端 MCP 服务也能调用。
 */
internal class AgentAutomationTool(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    fun execute(args: JSONObject): String {
        val result = try {
            when (args.optString("action")) {
                "list" -> list()
                "create" -> save(args, existing = null)
                "update" -> save(args, existing = find(args))
                "delete" -> delete(find(args))
                "run_now" -> runNow(find(args))
                else -> failure("INVALID_ARGUMENT", "action 只能是 list、create、update、delete 或 run_now")
            }
        } catch (failure: IllegalArgumentException) {
            failure("INVALID_ARGUMENT", failure.message ?: "参数无效")
        }
        return result.toString()
    }

    private fun list(): JSONObject {
        val items = JSONArray()
        AgentAutomationStore.all().forEach { items.put(describe(it)) }
        val models = JSONArray()
        availableModels().forEach { (id, label, efforts) ->
            models.put(JSONObject().put("id", id).put("name", label).put("reasoning_efforts", JSONArray(efforts)))
        }
        return JSONObject().put("ok", true).put("automations", items).put("count", items.length())
            .put("exact_alarms_allowed", AgentAutomationScheduler.exactAlarmsAllowed(context))
            .put("models", models)
    }

    /** 可指定给自动任务的模型：已启用服务商下的已启用模型，附可选的思考强度。读不到服务商时为空，不影响其余操作。 */
    private fun availableModels(): List<Triple<String, String, List<String>>> = runCatching {
        runBlocking { ProviderRepository.allProviders() }
    }.getOrDefault(emptyList()).filter { it.isEnabled }.flatMap { provider ->
        provider.models.filter { it.isEnabled }.sortedBy { it.sortOrder }.map { model ->
            Triple(
                model.id,
                "${provider.name} / ${model.displayName}",
                RuntimeConfigRepository.reasoningCapabilities(provider, model)
                    ?.selectableEfforts?.map { it.wireValue }.orEmpty(),
            )
        }
    }

    private fun save(args: JSONObject, existing: AgentAutomation?): JSONObject {
        val name = args.optString("name").trim().ifEmpty { existing?.name.orEmpty() }
        val prompt = args.optString("prompt").trim().ifEmpty { existing?.prompt.orEmpty() }
        require(name.isNotEmpty()) { "缺少 name" }
        require(prompt.isNotEmpty()) { "缺少 prompt：写清每次触发时要完成的事" }
        require(name.length <= AgentAutomation.MAX_NAME_CHARS) { "name 最长 ${AgentAutomation.MAX_NAME_CHARS} 字" }
        require(prompt.length <= AgentAutomation.MAX_PROMPT_CHARS) { "prompt 最长 ${AgentAutomation.MAX_PROMPT_CHARS} 字" }
        val trigger = args.optJSONObject("trigger")?.let(::parseTrigger) ?: existing?.trigger
            ?: throw IllegalArgumentException("缺少 trigger")
        // 传空字符串表示改回跟随默认；不传则保持原值。
        val modelId = if (args.has("model")) args.optString("model").trim().ifEmpty { null } else existing?.modelId
        if (args.has("model") && modelId != null) {
            require(availableModels().any { it.first == modelId }) { "model 不在可用模型中，先 list 查看 models" }
        }
        val effort = if (args.has("reasoning_effort")) {
            args.optString("reasoning_effort").trim().ifEmpty { null }?.let { raw ->
                ReasoningEffort.fromWireValue(raw) ?: throw IllegalArgumentException("reasoning_effort 无效：$raw")
            }
        } else {
            existing?.reasoningEffort
        }
        val automation = AgentAutomation(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            prompt = prompt,
            trigger = trigger,
            enabled = if (args.has("enabled")) args.optBoolean("enabled", true) else existing?.enabled ?: true,
            createdAt = existing?.createdAt ?: now(),
            lastRunAt = existing?.lastRunAt,
            lastStatus = existing?.lastStatus,
            lastSummary = existing?.lastSummary,
            modelId = modelId,
            reasoningEffort = effort,
        )
        if (!AgentAutomationStore.upsert(automation)) {
            return failure("AUTOMATION_LIMIT", "最多保存 ${AgentAutomation.MAX_AUTOMATIONS} 个自动任务，或配置写入失败")
        }
        AgentAutomationScheduler.reschedule(context, now())
        return JSONObject().put("ok", true).put("automation", describe(automation))
    }

    private fun delete(automation: AgentAutomation): JSONObject {
        AgentAutomationScheduler.cancel(context, automation.id)
        if (!AgentAutomationStore.delete(automation.id)) return failure("WRITE_FAILED", "删除失败")
        return JSONObject().put("ok", true).put("deleted", automation.id)
    }

    private fun runNow(automation: AgentAutomation): JSONObject {
        require(automation.enabled) { "任务已停用，先 update enabled=true" }
        val started = AgentAutomationRunner.fire(context, automation.id, trigger = null)
        return if (started) {
            JSONObject().put("ok", true).put("status", "started")
                .put("message", "已在后台开始执行；结果会归档到“自动任务”并发送通知，本次对话不会等待它完成")
        } else {
            failure("AUTOMATION_BUSY", "该任务正在运行或无法启动")
        }
    }

    private fun find(args: JSONObject): AgentAutomation {
        val id = args.optString("id").trim()
        require(id.isNotEmpty()) { "缺少 id，可先 list 查看" }
        return AgentAutomationStore.get(id) ?: throw IllegalArgumentException("未找到自动任务 $id")
    }

    private fun parseTrigger(raw: JSONObject): AutomationTrigger = when (raw.optString("type")) {
        "daily" -> {
            val (hour, minute) = AutomationSchedule.parseTime(raw.optString("time"))
                ?: throw IllegalArgumentException("daily 需要 time，格式 HH:mm")
            val days = raw.optJSONArray("days")?.let { array ->
                (0 until array.length()).map { array.optInt(it) }.toSet()
            }.orEmpty()
            require(days.all { it in 1..7 }) { "days 使用 1–7（1=周一，7=周日）" }
            AutomationTrigger.Daily(hour, minute, days)
        }
        "interval" -> {
            val minutes = raw.optInt("minutes", 0)
            require(minutes >= AutomationTrigger.MIN_INTERVAL_MINUTES) { "interval 至少 ${AutomationTrigger.MIN_INTERVAL_MINUTES} 分钟" }
            AutomationTrigger.Interval(minutes)
        }
        "once" -> {
            val at = AutomationSchedule.parseLocalDateTime(raw.optString("at"), zone())
                ?: throw IllegalArgumentException("once 需要 at，格式 yyyy-MM-dd HH:mm（本地时间）")
            require(at > now()) { "at 必须是将来的时间" }
            AutomationTrigger.Once(at)
        }
        "notification" -> {
            val trigger = AutomationTrigger.OnNotification(
                packageName = raw.optString("package").trim(),
                keyword = raw.optString("keyword").trim(),
                cooldownMinutes = raw.optInt("cooldown_minutes", AutomationTrigger.DEFAULT_COOLDOWN_MINUTES).coerceIn(1, 24 * 60),
            )
            require(trigger.packageName.isNotEmpty() || trigger.keyword.isNotEmpty()) { "notification 至少填 package 或 keyword" }
            trigger
        }
        else -> throw IllegalArgumentException("trigger.type 只能是 daily、interval、once 或 notification")
    }

    private fun describe(automation: AgentAutomation): JSONObject = JSONObject()
        .put("id", automation.id)
        .put("name", automation.name)
        .put("prompt", automation.prompt)
        .put("enabled", automation.enabled)
        .put("trigger", describeTrigger(automation.trigger))
        .put("model", automation.modelId ?: "default")
        .put("reasoning_effort", automation.reasoningEffort?.wireValue ?: "follow_default")
        .apply {
            AutomationSchedule.nextFireAt(automation, now(), zone())?.let { put("next_run", format(it)) }
            automation.lastRunAt?.let { put("last_run", format(it)) }
            automation.lastStatus?.let { put("last_status", it) }
            automation.lastSummary?.let { put("last_summary", it) }
        }

    private fun describeTrigger(trigger: AutomationTrigger): JSONObject = when (trigger) {
        is AutomationTrigger.Daily -> JSONObject().put("type", "daily")
            .put("time", "%02d:%02d".format(trigger.hour, trigger.minute))
            .put("days", JSONArray(trigger.daysOfWeek.sorted()))
        is AutomationTrigger.Interval -> JSONObject().put("type", "interval").put("minutes", trigger.minutes)
        is AutomationTrigger.Once -> JSONObject().put("type", "once").put("at", format(trigger.atMillis))
        is AutomationTrigger.OnNotification -> JSONObject().put("type", "notification")
            .put("package", trigger.packageName).put("keyword", trigger.keyword)
            .put("cooldown_minutes", trigger.cooldownMinutes)
    }

    private fun format(millis: Long): String = DISPLAY_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone()))

    private fun failure(code: String, message: String) = JSONObject().put("ok", false).put("code", code).put("message", message)

    internal companion object {
        const val TOOL_NAME = "manage_automations"
        private val DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun schema(): JSONObject = AgentToolSchema.function(
            name = TOOL_NAME,
            description = "管理自动任务：到点（每天/每周几、固定间隔、单次）或收到匹配的通知时，Eta 在后台自动执行一段任务描述，" +
                "结果归档到“自动任务”并发送通知。用户要求“每天几点…”“每隔…”“某时提醒/检查…”“收到某应用或含某词的通知时…”时使用。" +
                "action=list 查看现有任务；create 新建（name、prompt、trigger 必填）；update 修改（id 必填，只改提供的字段）；" +
                "delete 删除；run_now 立即在后台执行一次（不等待结果）。prompt 写成触发时要完成的完整任务，执行时用户可能不在手机前。" +
                "时间均为手机本地时间。",
            parameters = JSONObject().put("type", "object").put("required", JSONArray().put("action"))
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "string")
                        .put("enum", JSONArray(listOf("list", "create", "update", "delete", "run_now"))))
                    .put("id", JSONObject().put("type", "string").put("description", "update、delete、run_now 时必填"))
                    .put("name", JSONObject().put("type", "string").put("maxLength", AgentAutomation.MAX_NAME_CHARS))
                    .put("prompt", JSONObject().put("type", "string").put("maxLength", AgentAutomation.MAX_PROMPT_CHARS))
                    .put("enabled", JSONObject().put("type", "boolean"))
                    .put("model", JSONObject().put("type", "string")
                        .put("description", "可选；执行用的模型 id，取自 list 返回的 models。省略或传空字符串时用 Eta 的默认模型"))
                    .put("reasoning_effort", JSONObject().put("type", "string")
                        .put("description", "可选；思考强度（off、default、minimal、low、medium、high、xhigh、max）。" +
                            "省略或传空字符串时跟随全局默认强度；模型不支持的档位按最接近的较低档执行"))
                    .put("trigger", JSONObject().put("type", "object").put("required", JSONArray().put("type"))
                        .put("properties", JSONObject()
                            .put("type", JSONObject().put("type", "string")
                                .put("enum", JSONArray(listOf("daily", "interval", "once", "notification"))))
                            .put("time", JSONObject().put("type", "string").put("description", "daily：HH:mm"))
                            .put("days", JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer"))
                                .put("description", "daily：1–7（1=周一），省略表示每天"))
                            .put("minutes", JSONObject().put("type", "integer")
                                .put("description", "interval：间隔分钟数，至少 ${AutomationTrigger.MIN_INTERVAL_MINUTES}"))
                            .put("at", JSONObject().put("type", "string").put("description", "once：yyyy-MM-dd HH:mm"))
                            .put("package", JSONObject().put("type", "string").put("description", "notification：来源应用包名"))
                            .put("keyword", JSONObject().put("type", "string").put("description", "notification：标题或正文包含的词"))
                            .put("cooldown_minutes", JSONObject().put("type", "integer")
                                .put("description", "notification：两次触发的最短间隔，默认 ${AutomationTrigger.DEFAULT_COOLDOWN_MINUTES}"))))),
        )
    }
}
