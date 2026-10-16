package io.github.mangi.eta.agent.mcp.server

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolToggles
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 把 Eta 自己的本地工具开放给电脑端：工具目录与模型看到的一致，同样遵守工具设置里的分类开关、
 * 单项开关与 Root/无障碍等运行条件；执行走 [AgentLocalTools]，与 Agent run 共用屏幕和浏览器租约。
 */
internal class EtaMcpDeviceToolHost(context: Context) : EtaMcpToolHost {
    private val appContext = context.applicationContext
    private val tasks = sharedTaskManager(appContext)

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
        val tools = AgentLocalTools(
            context = appContext,
            logger = AndroidAgentLogger,
            browserRunId = sessionKey,
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
                val effective = if (EtaMcpPrivacySettings.load().allowImages) arguments else withoutScreenshot(name, arguments)
                val result = tools.execute(
                    AgentModelClient.ToolCall(
                        id = "mcp-" + UUID.randomUUID().toString(),
                        name = name,
                        argumentsJson = effective.toString(),
                    ),
                )
                return outcomeOf(result)
            }

            override fun close() = tools.close()
        }
    }

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
                ).also { taskManager = it }
            }

        private val IMAGE_ONLY_TOOLS = setOf("read_image")

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
