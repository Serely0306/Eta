package io.github.mangi.eta.agent.device

import android.app.NotificationManager
import android.content.Context
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Eta 操作屏幕期间暂停横幅通知，避免弹出的通知遮住正在操作的界面；通知仍照常进入通知栏。
 *
 * - 有 Root 时临时关闭全局横幅设置 `heads_up_notifications_enabled`，结束后写回原值；
 * - 没有 Root 但有勿扰权限时临时切到“仅限优先事项”，结束后仅在用户未改动时恢复原模式。
 *
 * 接管期以手机操作权为准：某个 run 持有操作权（覆盖观察、推理、动作与验证）且没有交还用户时
 * 视为接管，由 Runtime 在操作权变化时通知。原值持久化在私有配置里，进程意外退出后下次启动先恢复，
 * 避免横幅一直被关。所有操作在单线程上串行。
 */
internal object ScreenTakeoverNotificationGuard {
    private const val PREFS = "eta_takeover_notifications"
    private const val KEY_ROOT_PREVIOUS = "root_heads_up_previous"
    private const val KEY_DND_PREVIOUS = "dnd_previous_filter"
    private const val UNSET = "null"
    private const val SETTING = "heads_up_notifications_enabled"

    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "eta-takeover-guard").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null
    @Volatile private var takeoverActive = false

    fun install(context: Context) {
        appContext = context.applicationContext
        // 进程刚启动时不可能处于接管期：上次残留的屏蔽先恢复。Root 探测是异步的，稍后再试一次。
        worker.execute { appContext?.let(::restore) }
        worker.schedule({ if (!takeoverActive) appContext?.let(::restore) }, 15, TimeUnit.SECONDS)
    }

    /** Runtime 的手机操作权变化：有 run 持有且没有交还用户时为 true。 */
    fun onGuiControlChanged(active: Boolean) {
        if (takeoverActive == active) return
        takeoverActive = active
        worker.execute(::evaluate)
    }

    private fun evaluate() {
        val context = appContext ?: return
        val active = takeoverActive && Prefs.isEnabled(Prefs.Keys.AGENT_SUPPRESS_HEADS_UP)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val suppressed = prefs.contains(KEY_ROOT_PREVIOUS) || prefs.contains(KEY_DND_PREVIOUS)
        if (active && !suppressed) suppress(context)
        if (!active && suppressed) restore(context)
    }

    private fun suppress(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (RootAccess.isGranted) {
            BoundedRootCommandExecutor(AndroidAgentLogger).use { root ->
                val read = root.execute("settings get global $SETTING", timeoutMillis = COMMAND_TIMEOUT_MS)
                if (!read.ok) return@use
                val previous = read.stdout.trim().takeIf { it.matches(DIGITS) } ?: UNSET
                if (!prefs.edit().putString(KEY_ROOT_PREVIOUS, previous).commit()) return@use
                if (!root.execute("settings put global $SETTING 0", timeoutMillis = COMMAND_TIMEOUT_MS).ok) {
                    prefs.edit().remove(KEY_ROOT_PREVIOUS).commit()
                } else {
                    AndroidAgentLogger.info("Screen takeover: heads-up notifications paused")
                }
            }
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        val previous = manager.currentInterruptionFilter
        // 用户本来就开着勿扰时不再改动。
        if (previous != NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (!prefs.edit().putInt(KEY_DND_PREVIOUS, previous).commit()) return
        runCatching { manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
            .onFailure { prefs.edit().remove(KEY_DND_PREVIOUS).commit() }
    }

    private fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ROOT_PREVIOUS, null)?.let { previous ->
            if (!RootAccess.isGranted) return@let
            val command = if (previous == UNSET) "settings delete global $SETTING" else "settings put global $SETTING $previous"
            val restored = BoundedRootCommandExecutor(AndroidAgentLogger).use { it.execute(command, timeoutMillis = COMMAND_TIMEOUT_MS).ok }
            if (restored) {
                prefs.edit().remove(KEY_ROOT_PREVIOUS).commit()
                AndroidAgentLogger.info("Screen takeover: heads-up notifications restored")
            }
        }
        if (prefs.contains(KEY_DND_PREVIOUS)) {
            val previous = prefs.getInt(KEY_DND_PREVIOUS, NotificationManager.INTERRUPTION_FILTER_ALL)
            val manager = context.getSystemService(NotificationManager::class.java)
            // 期间用户自己改过勿扰模式时保留用户的选择。
            if (manager != null && manager.isNotificationPolicyAccessGranted &&
                manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
            ) {
                runCatching { manager.setInterruptionFilter(previous) }
            }
            prefs.edit().remove(KEY_DND_PREVIOUS).commit()
        }
    }

    private const val COMMAND_TIMEOUT_MS = 5_000L
    private val DIGITS = Regex("\\d+")
}
