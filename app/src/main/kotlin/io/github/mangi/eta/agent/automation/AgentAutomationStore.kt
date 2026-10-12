package io.github.mangi.eta.agent.automation

import android.content.SharedPreferences
import io.github.mangi.eta.config.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 自动任务列表保存在 App 私有的本地 Agent 配置中；读写在进程内串行，并以 Flow 通知界面。 */
internal object AgentAutomationStore {
    private const val KEY = "agent_automations"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
    private val serializer = ListSerializer(AgentAutomation.serializer())
    private val lock = Any()
    private val mutable = MutableStateFlow<List<AgentAutomation>>(emptyList())
    @Volatile private var loaded = false

    fun flow(preferences: SharedPreferences? = Prefs.localAgentPreferences()): StateFlow<List<AgentAutomation>> {
        all(preferences)
        return mutable.asStateFlow()
    }

    fun all(preferences: SharedPreferences? = Prefs.localAgentPreferences()): List<AgentAutomation> = synchronized(lock) {
        if (!loaded) {
            mutable.value = decode(runCatching { preferences?.getString(KEY, null) }.getOrNull())
            loaded = preferences != null
        }
        mutable.value
    }

    fun get(id: String): AgentAutomation? = all().firstOrNull { it.id == id }

    /** 新增或替换；超过上限的新增返回 false。 */
    fun upsert(automation: AgentAutomation, preferences: SharedPreferences? = Prefs.localAgentPreferences()): Boolean =
        mutate(preferences) { current ->
            val index = current.indexOfFirst { it.id == automation.id }
            when {
                index >= 0 -> current.toMutableList().apply { set(index, automation) }
                current.size >= AgentAutomation.MAX_AUTOMATIONS -> null
                else -> current + automation
            }
        }

    fun delete(id: String, preferences: SharedPreferences? = Prefs.localAgentPreferences()): Boolean =
        mutate(preferences) { current -> current.filterNot { it.id == id }.takeIf { it.size != current.size } }

    fun recordRun(
        id: String,
        at: Long,
        status: String,
        summary: String,
        preferences: SharedPreferences? = Prefs.localAgentPreferences(),
    ): Boolean = mutate(preferences) { current ->
        current.map { automation ->
            if (automation.id != id) {
                automation
            } else {
                automation.copy(
                    lastRunAt = at,
                    lastStatus = status,
                    lastSummary = summary.take(SUMMARY_CHARS),
                    // 单次任务执行后自动停用，列表里保留结果供查看。
                    enabled = automation.enabled && automation.trigger !is AutomationTrigger.Once,
                )
            }
        }
    }

    /** 标记开始运行：通知触发的冷却从开始时计算，避免同一批通知连续触发。 */
    fun markStarted(id: String, at: Long, preferences: SharedPreferences? = Prefs.localAgentPreferences()): Boolean =
        mutate(preferences) { current -> current.map { if (it.id == id) it.copy(lastRunAt = at) else it } }

    private fun mutate(
        preferences: SharedPreferences?,
        transform: (List<AgentAutomation>) -> List<AgentAutomation>?,
    ): Boolean = synchronized(lock) {
        val prefs = preferences ?: return false
        val next = transform(all(prefs)) ?: return false
        val saved = runCatching { prefs.edit().putString(KEY, json.encodeToString(serializer, next)).commit() }
            .getOrDefault(false)
        if (saved) mutable.value = next
        saved
    }

    internal fun decode(raw: String?): List<AgentAutomation> =
        raw?.takeIf(String::isNotBlank)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    internal fun encode(automations: List<AgentAutomation>): String = json.encodeToString(serializer, automations)

    private const val SUMMARY_CHARS = 300
}
