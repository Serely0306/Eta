package io.github.mangi.eta.agent.tool

/**
 * 工具单项开关的事实源：工具页卡片对应的工具族、工具之间的调用依赖，以及 Runtime 实际停用的集合。
 * 类别开关仍是总开关；这里只在其基础上进一步收窄。
 */
internal object AgentToolToggles {
    /** 一张卡片代表的全部工具；坐标/节点两种形态和旧入口随主工具一起开关。 */
    private val families: Map<String, Set<String>> = mapOf(
        "tap_area" to setOf("tap_area", "tap"),
        "long_press" to setOf("long_press", "long_press_element"),
        "scroll" to setOf("scroll", "scroll_element"),
        "type_text" to setOf("type_text", "input_text", "replace_text", "clear_text", "paste_text"),
        "wait_for_text" to setOf("wait_for_text", "wait_for_package"),
    )

    private val screenDependentTools = setOf(
        "tap", "tap_area", "tap_element", "long_press", "long_press_element", "swipe",
        "scroll", "scroll_element", "type_text", "input_text", "replace_text", "clear_text", "paste_text",
    )

    /**
     * 被依赖工具停用后，依赖方拿不到所需的 observation_id、检查结果或正文，随之停用。
     * 后台命令同样执行任意命令，关闭终端命令时一并停用，避免绕过终端开关。
     */
    private val dependencies: Map<String, String> = buildMap {
        screenDependentTools.forEach { put(it, "observe_screen") }
        put("skills_install_from_github", "skills_inspect_github")
        put("skills_read_resource", "skills_read")
        put("terminal_job", "terminal")
        put("terminal_daemon", "terminal")
    }

    fun family(toolName: String): Set<String> = families[toolName] ?: setOf(toolName)

    fun dependencyOf(toolName: String): String? = dependencies[toolName]

    /** 用户关闭的工具，连同因依赖缺失而无法工作的工具。 */
    fun effective(userDisabled: Set<String>): Set<String> {
        if (userDisabled.isEmpty()) return emptySet()
        return userDisabled + dependencies.filterValues { it in userDisabled }.keys
    }
}
