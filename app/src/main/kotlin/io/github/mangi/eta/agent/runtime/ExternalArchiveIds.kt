package io.github.mangi.eta.agent.runtime

/**
 * 外部入口归档会话的稳定编号。App 导入归档时据此合并到同一会话，结果通知据此直接打开该会话；
 * 两处必须使用同一算法。
 */
internal object ExternalArchiveIds {
    const val EXTERNAL_PREFIX = "archive-"
    const val ASSISTANT_PREFIX = "assistant-"
    private const val MAX_SOURCE_TAG_CHARS = 16

    /** 厂商入口的编号带上来源，侧栏据此归组；Eta 语音助手沿用原编号规则。 */
    fun conversationId(source: String, conversationKey: String): String {
        if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) {
            return ASSISTANT_PREFIX + stableId("$source:$conversationKey")
        }
        return "$EXTERNAL_PREFIX${sourceTag(source)}-" + stableId("$source:$conversationKey")
    }

    /** 旧版本按不带来源的编号归档。 */
    fun legacyConversationId(source: String, conversationKey: String): String =
        EXTERNAL_PREFIX + stableId("$source:$conversationKey")

    fun sourceTag(source: String): String = source.filter { it.isLetterOrDigit() || it == '_' }.take(MAX_SOURCE_TAG_CHARS)

    private fun stableId(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
