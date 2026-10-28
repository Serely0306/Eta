package io.github.mangi.eta.agent.model

import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** 服务商在失败响应里要求的等待时长，交给重试退避使用。 */
internal fun Response.retryAfterMs(): Long? =
    AgentModelFailure.parseRetryAfter(header("retry-after-ms"), header("Retry-After"))

/**
 * Provider 边界只分类失败；重试预算与上下文由 Loop 持有。可重试按排除法判断（AG-26）：
 * 认证、额度、参数、不存在、内容过滤与上下文超限之外的失败都可重试。
 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
    /** 服务商通过 `Retry-After` / `retry-after-ms` 指定的等待时长。 */
    val retryAfterMs: Long? = null,
) : IllegalStateException(message, cause) {
    companion object {
        private val retryableClientStatus = setOf(408, 409, 425, 429)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        /** 流里明确表示请求本身有问题、重发也不会好的错误；其余（含中转站的 upstream_error）都重试。 */
        private val nonRetryableStreamCodes = setOf(
            "invalid_request_error", "invalid_request", "invalid_api_key", "authentication_error",
            "permission_error", "permission_denied", "not_found_error", "model_not_found",
            "content_filter", "content_policy_violation",
            "INVALID_ARGUMENT", "PERMISSION_DENIED", "NOT_FOUND", "FAILED_PRECONDITION", "UNAUTHENTICATED",
        )

        private val invalidApiKeyReasons = setOf("API_KEY_INVALID", "API_KEY_EXPIRED")

        fun isRetryableStatus(status: Int): Boolean = status in retryableClientStatus || status in 500..599

        /** `retry-after-ms`（毫秒）优先，其次 `Retry-After`（秒数或 HTTP 日期）；无法解析时返回 null。 */
        fun parseRetryAfter(retryAfterMs: String?, retryAfter: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
            retryAfterMs?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { return it.toLong() }
            val value = retryAfter?.trim()?.takeIf(String::isNotEmpty) ?: return null
            value.toDoubleOrNull()?.takeIf { it >= 0 }?.let { return (it * 1000).toLong() }
            return runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time
            }.getOrNull()?.let { (it - nowMillis).coerceAtLeast(0) }
        }

        fun http(
            status: Int,
            body: String,
            includeParameterDetails: Boolean = false,
            retryAfterMs: Long? = null,
        ): AgentModelFailure {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            authenticationFailure(error)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "模型上下文超过容量限制。",
            )
            val permanent = isPermanent(error, body)
            if (status == 400 && includeParameterDetails && !permanent) {
                ProviderParameterDiagnostics.describe(error)?.let { details ->
                    return AgentModelFailure("HTTP_400", false, "模型请求参数无效（HTTP 400；$details），请检查模型配置。")
                }
            }
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = isRetryableStatus(status) && !permanent,
                retryAfterMs = retryAfterMs,
                message = if (permanent) "模型接口额度或计费受限（HTTP $status），请检查服务商账户。"
                else when (status) {
                    400 -> "模型请求参数无效（HTTP 400），请检查模型配置。"
                    401 -> "模型接口认证失败（HTTP 401），请检查 API Key。"
                    403 -> "模型接口拒绝访问（HTTP 403），请检查账户与模型权限。"
                    404 -> "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。"
                    429 -> "模型接口暂时限流（HTTP 429）。"
                    else -> "模型接口返回 HTTP $status"
                },
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            authenticationFailure(error)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "模型上下文超过容量限制。",
            )
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optString("status"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            val nonRetryable = codes.any { code ->
                code in nonRetryableStreamCodes || code.toIntOrNull()?.let { it in 400..499 && !isRetryableStatus(it) } == true
            }
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !nonRetryable && !isPermanent(error, error.optString("message")),
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟，" +
                    "或上下文压缩总耗时超过 ${AgentHttpClient.COMPACTION_CALL_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            // 握手失败多为证书或协议配置问题，重发也不会好；连接中途的 SSL 与协议异常按断线重试。
            is SSLHandshakeException -> null
            is SSLException, is ProtocolException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中途断开（SSL 或协议异常），请检查网络与服务商状态。", failure,
            )
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            else -> null
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            if (listOf(error.optString("code"), error.optString("type")).any {
                it in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long")
            }) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("exceeds the context window") || message.contains("input token count exceeds") ||
                message.contains("input token count") && message.contains("exceeds the maximum")
        }

        private fun authenticationFailure(error: JSONObject?): AgentModelFailure? {
            if (error == null) return null
            val details = error.optJSONArray("details")
            val invalidKey = (0 until (details?.length() ?: 0)).any { index ->
                details?.optJSONObject(index)?.optString("reason") in invalidApiKeyReasons
            }
            return if (invalidKey || error.optString("status") == "UNAUTHENTICATED") {
                AgentModelFailure("PROVIDER_AUTH_FAILED", false, "模型接口认证失败，请检查 API Key。")
            } else null
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
