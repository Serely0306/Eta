package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentTaskPlan
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.AgentUserQuestion
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements

/**
 * 子 Agent 的共用约定。AG-19 起子 Agent 是会话级的（[AgentSubagentManager]，工具见 [AgentSubagentTools]）；
 * [TOOL_NAME] 是旧版批量派出工具的名字，只用来显示旧会话里的记录。
 *
 * 子 Agent 不能操作界面（屏幕只有一块，由主 Agent 串行使用）、不能提问、不能再派子 Agent。
 */
internal object AgentSubagentTool {
    /** 旧版 `run_subagents`：新版本不再提供，旧会话的工具行照旧显示。 */
    const val TOOL_NAME = "run_subagents"
    const val MAX_RESULT_CHARS = 8_000

    /** 只属于主 Agent 的协作工具；列入停用集合，子 Agent 的提示词也不会提到它们。 */
    val COORDINATOR_TOOLS: Set<String> =
        setOf(AgentUserQuestion.TOOL_NAME, AgentTaskPlan.TOOL_NAME, TOOL_NAME) + AgentSubagentTools.TOOL_NAMES

    /**
     * 子 Agent 不开放的目录工具：需要手机操作权或无障碍的界面工具、拉起前台界面的工具、
     * 释放操作权（子 Agent 从不持有）、主 run 的会话浏览器，以及 Skill 管理。
     */
    fun excludedTools(catalogNames: Iterable<String>): Set<String> = catalogNames.filterTo(mutableSetOf()) { name ->
        AgentToolRequirements.requiresGuiControl(name) || AgentToolRequirements.requiresAccessibility(name) ||
            AgentToolRequirements.hasForegroundIntentFallback(name) || name == RELEASE_GUI_TOOL ||
            name == BROWSER_TOOL || name.startsWith("skills_")
    }

    fun catalogNames(capabilities: AgentToolCapabilities): List<String> {
        val catalog = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            capabilities = capabilities,
        )
        return (0 until catalog.length()).mapNotNull { catalog.optJSONObject(it)?.optJSONObject("function")?.optString("name") }
    }

    /** 子 Agent 第一个回合的任务说明；之后的回合直接用主 Agent 发来的消息。 */
    fun subagentPrompt(task: String, context: String): String = buildString {
        append("你是 Eta 主 Agent 派出的子 Agent，负责下面这项子任务，完成后直接给出结论。主 Agent 之后可能继续给你发消息，请记住本任务的上下文。\n")
        append("- 只用当前公开的工具独立完成；你不能操作手机界面，也不能向用户提问。")
        append("需要界面操作或用户决定的部分，在结论里写明，由主 Agent 处理。\n")
        append("- 你看不到主对话，只依据下面的任务与背景工作，不要假设未提供的信息。\n")
        append("- 工作中可能收到标注为“主 Agent 补充”的消息，据此调整，不必从头开始。\n")
        append("- 你最后一段不再调用工具的回复就是本次结论，会原样交给主 Agent；中途的说明不算结论。\n")
        append("- terminal_job、terminal_daemon 启动的后台任务在你结束后仍会继续运行；只在确有必要时使用，并在结论里写明任务 id。\n")
        append("- 结论要具体：给出关键事实、文件路径、命令输出要点或来源链接，并说明不确定之处；不寒暄，不复述任务。\n\n")
        append("子任务：\n").append(task)
        if (context.isNotBlank()) append("\n\n背景：\n").append(context)
    }

    private const val RELEASE_GUI_TOOL = "release_gui"
    private const val BROWSER_TOOL = "browser_use"
}
