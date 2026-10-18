package io.github.mangi.eta.agent.model

/** 用量记录的归属：哪个入口、哪次 run、哪个模型会话；子 Agent 的请求带序号。 */
internal data class ModelUsageContext(
    val source: String,
    val runId: String,
    val subagentIndex: Int? = null,
    val sessionId: String = "",
)

/**
 * 当前线程上正在执行的 run。Runtime 在调用 [AgentModelClient.complete] 时设置，Provider 出口的记录器读取；
 * 同一 run 的压缩请求与主循环在同一线程，子 Agent 在自己的线程上重新设置并带序号。
 * 没有设置时（例如语音转写润色）记录为未知入口。
 */
internal object ModelUsageScope {
    private val current = ThreadLocal<ModelUsageContext?>()

    fun current(): ModelUsageContext? = current.get()

    fun <T> run(context: ModelUsageContext?, block: () -> T): T {
        val previous = current.get()
        current.set(context)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }
}
