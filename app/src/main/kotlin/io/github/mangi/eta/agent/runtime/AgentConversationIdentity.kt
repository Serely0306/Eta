package io.github.mangi.eta.agent.runtime

/**
 * 入口、归档与浏览器共同使用的会话身份。外部入口沿用本分支已发布的归档编号（[ExternalArchiveIds]，
 * 带来源标记），运行登记、会话浏览器与 App 侧导入的会话因此是同一个 ID。
 */
internal object AgentConversationIdentity {
    const val EXTERNAL_ARCHIVE_PREFIX = ExternalArchiveIds.EXTERNAL_PREFIX

    fun fromHandoff(handoff: AgentRuntimeWire.EntryHandoff?, fallbackRunId: String): String {
        if (handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            AgentUiHandoffPayload.from(handoff.payload).conversationId.takeIf(String::isNotBlank)?.let { return it }
        } else if (handoff != null) {
            AgentExternalArchivePayload.from(handoff.payload)?.let { payload ->
                return archiveConversationId(handoff.source, payload.conversationKey)
            }
        }
        return "run:$fallbackRunId"
    }

    fun archiveConversationId(source: String, conversationKey: String): String =
        ExternalArchiveIds.conversationId(source, conversationKey)
}
