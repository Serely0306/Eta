package io.github.mangi.eta.agent.mcp.server

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolToggles
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.usage.ModelUsageLedger
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 把 Eta 自己的本地工具开放给电脑端：工具目录与模型看到的一致，同样遵守工具设置里的分类开关、
 * 单项开关与 Root/无障碍等运行条件；执行走 [AgentLocalTools]，与 Agent run 共用手机操作权，
 * 浏览器使用电脑端会话自己的页面。
 */
internal class EtaMcpDeviceToolHost(context: Context) : EtaMcpToolHost {
    private val appContext = context.applicationContext
    private val tasks = sharedTaskManager(appContext)
    /** 与 Runtime 的各个 run 共用的手机操作权（进程内唯一）。 */
    private val guiCoordinator = GuiControlCoordinator.forApplication(appContext)

    override fun listTools(): List<EtaMcpTool> = deviceTools() + tasks.tools()

    private fun deviceTools(): List<EtaMcpTool> {
        val permissions = permissions()
        val disabled = AgentToolToggles.effective(permissions.disabledTools)
        val catalog = AgentToolCatalog.build(
            terminalTools = permissions.terminalTools,
            browserTools = permissions.browserTools,
            deviceDirectTools = permissions.deviceDirectTools,
            deviceSensitiveReadTools = permissions.deviceSensitiveReadTools,
            deviceSensitiveActionTools = permissions.deviceSensitiveActionTools,
            memoryTools = memoryEnabled(),
            capabilities = AgentToolCapabilities.capture(appContext),
        )
        val allowImages = EtaMcpPrivacySettings.load().allowImages
        return buildList {
            for (index in 0 until catalog.length()) {
                val function = catalog.optJSONObject(index)?.optJSONObject("function") ?: continue
                val name = function.optString("name")
                if (name.isBlank() || name in disabled || !exposed(name)) continue
                // 不允许返回图片时，只用于读取图片的工具对电脑端没有意义。
                if (!allowImages && name in IMAGE_ONLY_TOOLS) continue
                add(
                    EtaMcpTool(
                        name = name,
                        description = function.optString("description"),
                        inputSchema = function.optJSONObject("parameters") ?: JSONObject().put("type", "object"),
                    ),
                )
            }
        }
    }

    override fun openSession(sessionKey: String): EtaMcpToolHost.Session {
        // 电脑端直调与 Runtime 的各个 run 共用进程内的手机操作权：界面工具先取得操作权，
        // 调用 release_gui 或空闲一段时间后释放；交接后旧观察失效，须重新 observe_screen。
        val guiOwnerId = "mcp:$sessionKey"
        val controller = AgentRunController()
        val guiControl = guiCoordinator.openRun(guiOwnerId, controller)
        val pendingRelease = AtomicReference<ScheduledFuture<*>?>(null)
        val tools = AgentLocalTools(
            context = appContext,
            logger = AndroidAgentLogger,
            browserRunId = sessionKey,
            guiControl = guiControl,
            browserToolsEnabled = { permissions().browserTools },
            terminalToolsEnabled = { permissions().terminalTools },
            deviceDirectToolsEnabled = { permissions().deviceDirectTools },
            deviceSensitiveReadToolsEnabled = { permissions().deviceSensitiveReadTools },
            deviceSensitiveActionToolsEnabled = { permissions().deviceSensitiveActionTools },
            memoryToolsEnabled = ::memoryEnabled,
            disabledTools = { AgentToolToggles.effective(Prefs.disabledTools()) },
            beforeToolExecution = ::ensureAccessibility,
        )
        return object : EtaMcpToolHost.Session {
            override fun call(name: String, arguments: JSONObject): EtaMcpToolOutcome {
                if (tasks.handles(name)) {
                    val result = tasks.call(name, arguments)
                    return EtaMcpToolOutcome(text = result.toString(), isError = !result.optBoolean("ok", false))
                }
                if (name == RELEASE_GUI && guiCoordinator.state.value.ownerRunId != guiOwnerId) {
                    // 没有持有手机时无需释放；直接执行会先排队等待手机。
                    pendingRelease.getAndSet(null)?.cancel(false)
                    return EtaMcpToolOutcome(text = JSONObject().put("ok", true).put("tool", RELEASE_GUI).toString())
                }
                val needsPhone = AgentToolRequirements.requiresGuiControl(name) && name != RELEASE_GUI
                if (needsPhone) {
                    pendingRelease.getAndSet(null)?.cancel(false)
                    if (!awaitPhoneAvailable(guiOwnerId)) return phoneBusyOutcome()
                }
                val effective = if (EtaMcpPrivacySettings.load().allowImages) arguments else withoutScreenshot(name, arguments)
                val result = tools.execute(
                    AgentModelClient.ToolCall(
                        id = "mcp-" + UUID.randomUUID().toString(),
                        name = name,
                        argumentsJson = effective.toString(),
                    ),
                )
                if (needsPhone) {
                    // 电脑端可能不调用 release_gui；空闲后自动交还，避免会话开着就一直占着手机。
                    pendingRelease.getAndSet(
                        idleReleaser.schedule(Runnable { guiControl.release() }, GUI_IDLE_RELEASE_MS, TimeUnit.MILLISECONDS),
                    )?.cancel(false)
                }
                return outcomeOf(result)
            }

            override fun close() {
                pendingRelease.getAndSet(null)?.cancel(false)
                tools.close()
                controller.cancel()
            }
        }
    }

    /** 手机被 Eta 的任务操作或已交还用户时有界等待，超时返回忙碌，避免电脑端的请求一直挂起。 */
    private fun awaitPhoneAvailable(ownerId: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + GUI_WAIT_MS
        while (true) {
            val state = guiCoordinator.state.value
            if (!state.userPaused && (state.ownerRunId == null || state.ownerRunId == ownerId)) return true
            if (SystemClock.uptimeMillis() >= deadline) return false
            Thread.sleep(GUI_POLL_MS)
        }
    }

    private fun phoneBusyOutcome() = EtaMcpToolOutcome(
        text = JSONObject().put("ok", false).put("code", "SCREEN_BUSY")
            .put("message", "手机正在被 Eta 的任务操作或已交给用户，等待后仍未释放，本次未执行；可先做不需要界面的步骤，稍后再试。")
            .toString(),
        isError = true,
    )

    private fun ensureAccessibility(toolName: String): ToolExecutionDecision {
        if (!AgentToolRequirements.requiresAccessibility(toolName)) return ToolExecutionDecision.Allow
        val accessibility = AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
        return if (accessibility.available) {
            ToolExecutionDecision.Allow
        } else {
            ToolExecutionDecision.Reject(code = accessibility.code, message = accessibility.message)
        }
    }

    private fun permissions() = AgentRuntimePolicy.permissions(Prefs.localAgentPreferences())

    private fun memoryEnabled(): Boolean = runBlocking { AgentMemoryRepository.isEnabled() }

    internal companion object {
        @Volatile private var taskManager: EtaMcpTaskManager? = null

        /** 委托任务跨 MCP 会话与服务重启保留，进程内共用一份。 */
        private fun sharedTaskManager(context: Context): EtaMcpTaskManager =
            taskManager ?: synchronized(this) {
                taskManager ?: EtaMcpTaskManager(
                    runner = EtaMcpRuntimeTaskRunner(context),
                    models = { runBlocking { EtaMcpModelAccess.choices() } },
                    runUsage = { taskId -> ModelUsageLedger.runSummary(taskId)?.toJson() },
                ).also { taskManager = it }
            }

        private val IMAGE_ONLY_TOOLS = setOf("read_image")
        private const val RELEASE_GUI = "release_gui"
        private const val GUI_WAIT_MS = 60_000L
        private const val GUI_POLL_MS = 250L
        private const val GUI_IDLE_RELEASE_MS = 45_000L

        private val idleReleaser = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "eta-mcp-gui-release").apply { isDaemon = true }
        }

        /** Skill 工具服务于 Eta 自身的 Agent 指令体系，对电脑端没有意义，不开放。 */
        fun exposed(name: String): Boolean = !name.startsWith("skills_")

        /** 不允许返回图片时根本不截图；只要截图不要 UI 树的观察改为只取 UI 树，避免返回空观察。 */
        fun withoutScreenshot(name: String, arguments: JSONObject): JSONObject {
            if (name != "observe_screen" || !arguments.optBoolean("include_screenshot", false)) return arguments
            return JSONObject(arguments.toString())
                .put("include_screenshot", false)
                .put("include_ui_tree", true)
        }

        fun outcomeOf(result: AgentModelClient.ToolResult): EtaMcpToolOutcome {
            val failed = runCatching { JSONObject(result.content).optBoolean("ok", true) == false }
                .getOrDefault(false)
            val images = result.images.mapNotNull { image ->
                val reference = image.reference
                if (!reference.startsWith("data:")) return@mapNotNull null
                val comma = reference.indexOf(',')
                if (comma < 0 || !reference.substring(0, comma).endsWith(";base64")) return@mapNotNull null
                EtaMcpToolOutcome.Image(base64 = reference.substring(comma + 1), mimeType = image.mimeType)
            }
            return EtaMcpToolOutcome(text = result.content, images = images, isError = failed)
        }
    }
}
