package io.github.mangi.eta.agent.mcp.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaMcpRedactorTest {
    private val redactor = EtaMcpRedactor()

    @Test
    fun phoneVariantsShareOnePlaceholderAndRestoreToElevenDigits() {
        val text = redactor.redactText("13812345678 / +86 138-1234-5678 / 138 1234 5678 / 0086 13812345678")
        assertEquals("[PHONE_1] / [PHONE_1] / [PHONE_1] / [PHONE_1]", text)
        val restored = redactor.restoreArguments(JSONObject().put("to", "[PHONE_1]"))
        assertEquals("13812345678", restored.getString("to"))
    }

    @Test
    fun coversEmailIdCardBankCardLandlineAndForeignNumbers() {
        val text = redactor.redactText(
            "mail a.b+c@example.com.cn id 11010519491231002X card 4111 1111 1111 1111 tel 010-12345678 intl +447911123456",
        )
        assertEquals(
            "mail [EMAIL_1] id [ID_CARD_1] card [BANK_CARD_1] tel [PHONE_1] intl [PHONE_2]",
            text,
        )
        assertEquals("4111111111111111", redactor.restoreArguments(JSONObject().put("c", "[BANK_CARD_1]")).getString("c"))
    }

    @Test
    fun verificationCodesAreMaskedOnlyNextToKeywords() {
        assertEquals("您的验证码是 [OTP_1]，5 分钟内有效", redactor.redactText("您的验证码是 482913，5 分钟内有效"))
        assertEquals("[OTP_1]是您的验证码", redactor.redactText("482913是您的验证码"))
        assertEquals("Your verification code: [OTP_2]", redactor.redactText("Your verification code: 7781"))
        assertEquals("订单号 482913 已发货", redactor.redactText("订单号 482913 已发货"))
    }

    @Test
    fun leavesTimestampsIdentifiersAndInvalidNumbersAlone() {
        val text = "ts=1760600000000 id=a13812345678 hex=4111111111111111ab card=4111111111111112 id2=110105194912310021x"
        assertEquals(text, redactor.redactText(text))
        assertFalse(redactor.redactText("110105194912310021").contains("[ID_CARD"))
    }

    @Test
    fun jsonKeepsStructureAndMasksStringAndNumericValues() {
        val raw = """{"ok":true,"phone":"13812345678","n":13812345678,"list":["a@b.com",3],"note":null}"""
        val redacted = JSONObject(redactor.redactText(raw))
        assertEquals("[PHONE_1]", redacted.getString("phone"))
        assertEquals("[PHONE_1]", redacted.getString("n"))
        assertEquals("[EMAIL_1]", redacted.getJSONArray("list").getString(0))
        assertEquals(3, redacted.getJSONArray("list").getInt(1))
        assertTrue(redacted.isNull("note"))

        val untouched = """{"ok":true,"count":3}"""
        assertEquals(untouched, redactor.redactText(untouched))
        // JSON 之后还有输出时按纯文本处理，尾部不丢。
        assertEquals("""{"a":"[PHONE_1]"} tail""", redactor.redactText("""{"a":"13812345678"} tail"""))
    }

    @Test
    fun restoreRewritesNestedStringsAndRejectsUnknownPlaceholders() {
        redactor.redactText("13812345678 a@b.com")
        val restored = redactor.restoreArguments(
            JSONObject()
                .put("body", "call [PHONE_1] or mail [EMAIL_1]")
                .put("nested", JSONObject().put("list", org.json.JSONArray().put("[PHONE_1]"))),
        )
        assertEquals("call 13812345678 or mail a@b.com", restored.getString("body"))
        assertEquals("13812345678", restored.getJSONObject("nested").getJSONArray("list").getString(0))

        val failure = runCatching { redactor.restoreArguments(JSONObject().put("to", "[PHONE_9] [OTP_3]")) }
            .exceptionOrNull() as? EtaMcpRedactor.UnknownPlaceholderException
        assertEquals(listOf("[PHONE_9]", "[OTP_3]"), failure?.placeholders)
    }

    @Test
    fun evictedPlaceholdersAreNeverReusedForAnotherValue() {
        val small = EtaMcpRedactor(capacity = 2)
        assertEquals("[PHONE_1]", small.redactText("13800000001"))
        assertEquals("[PHONE_2]", small.redactText("13800000002"))
        assertEquals("[PHONE_3]", small.redactText("13800000003"))
        assertTrue(runCatching { small.restoreArguments(JSONObject().put("to", "[PHONE_1]")) }.isFailure)
        val again = small.redactText("13800000001")
        assertNotEquals("[PHONE_1]", again)
        assertEquals("13800000003", small.restoreArguments(JSONObject().put("to", "[PHONE_3]")).getString("to"))
    }
}
