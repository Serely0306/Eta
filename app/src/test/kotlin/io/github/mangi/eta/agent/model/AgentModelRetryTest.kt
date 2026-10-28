package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class AgentModelRetryTest {
    @Test
    fun retriesAreBoundedByTheSettingAndBackOffFromOneSecond() {
        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry(maxRetries = { 5 }, jitter = { 0.0 }) { _, delay -> delays += delay }
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(retry, provider { _, _ -> calls++; throw SocketTimeoutException("timeout") })
        }
        assertEquals(6, calls)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), delays)
        assertTrue(failure.message.orEmpty().contains("已重试 5 次"))
        assertFalse(failure.retryable)
        delays.clear()
        calls = 0
        val result = complete(retry, provider { _, _ ->
            if (calls++ == 0) throw IOException("connection reset")
            response()
        })
        assertEquals(2, result.round)
        assertEquals(listOf(1_000L), delays)
    }

    @Test
    fun zeroRetriesFailsOnFirstTransientFailure() {
        val retry = AgentModelRetry(maxRetries = { 0 }) { _, _ -> fail("不应重试") }
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(retry, provider { _, _ -> calls++; throw SocketTimeoutException() })
        }
        assertEquals(1, calls)
        assertTrue(failure.message.orEmpty().contains("未开启失败重试"))
    }

    @Test
    fun retryEventCarriesReasonAndHonorsRetryAfter() {
        val delays = mutableListOf<Long>()
        val events = mutableListOf<AgentEvent>()
        val retry = AgentModelRetry(maxRetries = { 5 }, jitter = { 0.0 }) { _, delay -> delays += delay }
        var calls = 0
        complete(retry, provider { _, _ ->
            if (calls++ == 0) throw AgentModelFailure.http(429, "", retryAfterMs = 7_000)
            response()
        }, onEvent = events::add)
        assertEquals(listOf(7_000L), delays)
        val scheduled = events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single()
        assertEquals(1, scheduled.attempt)
        assertEquals(5, scheduled.maxAttempts)
        assertEquals("HTTP_429", scheduled.reasonCode)
        assertTrue(scheduled.reasonMessage.contains("HTTP 429"))
        assertTrue(scheduled.displayMessage, scheduled.displayMessage.contains("（1/5）"))
        assertTrue(scheduled.displayMessage.contains("HTTP 429"))
        assertTrue(scheduled.displayMessage.contains("7 秒后"))
    }

    @Test
    fun cancellationDuringBackoffStopsBeforeAnotherRequest() {
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            complete(
                AgentModelRetry { control, _ -> control.cancel() },
                provider { _, _ -> calls++; throw SocketTimeoutException() },
                controller,
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun callbackFailuresAreNotReplayedButHostedToolFailuresAre() {
        val noRetry = AgentModelRetry { _, _ -> fail("不应重试") }
        val callbackFailure = IOException("checkpoint write failed")
        val thrown = assertThrows(IOException::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.RequestStarted)
                response()
            }, onProviderEvent = { _, _ -> throw callbackFailure })
        }
        assertSame(callbackFailure, thrown)

        var calls = 0
        val result = complete(AgentModelRetry(jitter = { 0.0 }) { _, _ -> }, provider { _, emit ->
            emit(ProviderEvent.HostedToolStarted("search-$calls", "web_search"))
            if (calls++ == 0) throw SocketTimeoutException()
            response()
        })
        assertEquals(2, calls)
        assertEquals(2, result.round)
    }

    @Test
    fun classifiesByExclusion() {
        for (status in listOf(408, 409, 425, 429, 500, 502, 503, 504, 520, 522, 524, 529)) {
            assertTrue("HTTP $status", AgentModelFailure.http(status, "").retryable)
        }
        for (status in listOf(400, 401, 402, 403, 404, 413, 422)) {
            assertFalse("HTTP $status", AgentModelFailure.http(status, "").retryable)
        }
        assertFalse(AgentModelFailure.http(429, """{"error":{"code":"insufficient_quota"}}""").retryable)
        assertNull(AgentModelFailure.transport(SSLHandshakeException("certificate")))
        assertNull(AgentModelFailure.transport(org.json.JSONException("invalid JSON")))
        assertTrue(AgentModelFailure.transport(SSLException("connection reset during read"))!!.retryable)
        assertTrue(AgentModelFailure.transport(ProtocolException("unexpected end of stream"))!!.retryable)
        assertTrue(AgentModelFailure.stream(JSONObject().put("type", "overloaded_error"), "过载").retryable)
        assertTrue(AgentModelFailure.stream(JSONObject().put("type", "upstream_error"), "上游错误").retryable)
        assertTrue(AgentModelFailure.stream(JSONObject().put("code", "503"), "暂时不可用").retryable)
        assertTrue(AgentModelFailure.stream(JSONObject(), "未知错误").retryable)
        assertFalse(AgentModelFailure.stream(JSONObject().put("type", "authentication_error"), "认证失败").retryable)
        assertFalse(AgentModelFailure.stream(JSONObject().put("type", "invalid_request_error"), "参数无效").retryable)
        assertFalse(AgentModelFailure.stream(JSONObject().put("code", "400"), "参数无效").retryable)
        assertFalse(AgentModelFailure.http(503, "secret request text").message.orEmpty().contains("secret"))
    }

    @Test
    fun parsesRetryAfterHeaders() {
        assertEquals(1_500L, AgentModelFailure.parseRetryAfter("1500", "9"))
        assertEquals(3_000L, AgentModelFailure.parseRetryAfter(null, "3"))
        val now = 1_445_412_480_000L // Wed, 21 Oct 2015 07:28:00 GMT
        assertEquals(5_000L, AgentModelFailure.parseRetryAfter(null, "Wed, 21 Oct 2015 07:28:05 GMT", now))
        assertNull(AgentModelFailure.parseRetryAfter(null, "soon"))
        assertNull(AgentModelFailure.parseRetryAfter(null, null))
    }

    @Test
    fun delaysAreCapped() {
        assertEquals(1_200L, AgentModelRetry.delayMs(1, null, 0.2))
        assertEquals(30_000L, AgentModelRetry.delayMs(10, null, 0.0))
        assertEquals(60_000L, AgentModelRetry.delayMs(1, 90_000L, 0.0))
        assertEquals(0L, AgentModelRetry.delayMs(3, 0L, 0.0))
    }

    @Test
    fun retryingRepeatsTransientFailuresOnlyAndReportsEachAttempt() {
        val attempts = mutableListOf<Int>()
        val retry = AgentModelRetry(maxRetries = { 5 }, jitter = { 0.0 }) { _, _ -> }
        var calls = 0
        val value = retry.retrying(AgentRunController(), onRetry = { attempt, _, _, _ -> attempts += attempt }) {
            if (calls++ < 2) throw AgentModelFailure.http(502, "")
            "摘要"
        }
        assertEquals("摘要", value)
        assertEquals(listOf(1, 2), attempts)

        calls = 0
        val invalid = assertThrows(AgentModelFailure::class.java) {
            retry.retrying(AgentRunController()) { calls++; throw AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "无效") }
        }
        assertEquals("CONTEXT_SUMMARY_INVALID", invalid.code)
        assertEquals(1, calls)
    }

    private fun complete(
        retry: AgentModelRetry,
        provider: AgentProviderClient,
        controller: AgentRunController = AgentRunController(),
        onProviderEvent: (Int, ProviderEvent) -> Unit = { _, _ -> },
        onEvent: (AgentEvent) -> Unit = {},
    ) = retry.complete(
        initialRound = 1,
        request = ProviderRequest(
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""),
            JSONArray(), JSONArray(),
        ),
        provider = provider,
        controller = controller,
        onEvent = onEvent,
        onProviderEvent = onProviderEvent,
        discardAttemptReasoning = {},
    )

    private fun response() = ProviderResponse(JSONObject().put("content", "完成").put("finish_reason", "stop"))

    private fun provider(action: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ) = action(request, onEvent)
        }
}
