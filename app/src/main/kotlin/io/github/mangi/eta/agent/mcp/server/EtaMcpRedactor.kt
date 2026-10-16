package io.github.mangi.eta.agent.mcp.server

import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * MCP 出口的规则脱敏与可逆占位：工具结果中的手机号、邮箱、身份证号、银行卡号与验证码替换为
 * `[PHONE_1]` 这类占位符，原值只保存在手机内存中；电脑端在参数里传回占位符时还原后再执行。
 *
 * - 同一个值始终得到同一个占位符；编号在进程内只增不减，被淘汰的旧占位符不会指向别的值。
 * - 规则只覆盖格式固定的号码类信息，姓名、地址等需要语义判断的内容不在范围内。
 */
internal class EtaMcpRedactor(private val capacity: Int = DEFAULT_CAPACITY) {
    enum class Kind { EMAIL, ID_CARD, BANK_CARD, PHONE, OTP }

    class UnknownPlaceholderException(val placeholders: List<String>) :
        IllegalArgumentException("unknown placeholders: ${placeholders.joinToString()}")

    private class Entry(val key: String, val value: String)

    /** `KIND:规范化原值` → 占位符；按访问顺序排列，超出容量时淘汰最久未用的。 */
    private val byValue = LinkedHashMap<String, String>(16, 0.75f, true)
    private val byPlaceholder = HashMap<String, Entry>()
    private val counters = IntArray(Kind.entries.size)

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /** JSON 文本只处理字符串值（以及整体像号码的数字值），结构保持不变；其余按纯文本处理。 */
    @Synchronized
    fun redactText(text: String): String {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = runCatching {
                val tokener = JSONTokener(text)
                // JSON 之后还有其他输出时按纯文本处理，避免重新序列化时丢掉尾部。
                tokener.nextValue().takeIf { tokener.nextClean() == '\u0000' }
            }.getOrNull()
            if (parsed is JSONObject || parsed is JSONArray) {
                var changed = false
                val redacted = mapStrings(parsed) { value ->
                    redactPlain(value).also { if (it != value) changed = true }
                }
                return if (changed) redacted.toString() else text
            }
        }
        return redactPlain(text)
    }

    /** 还原参数中的占位符；出现未知占位符时整体拒绝，避免把字面占位符当成真实号码执行。 */
    @Synchronized
    fun restoreArguments(arguments: JSONObject): JSONObject {
        val unknown = linkedSetOf<String>()
        val restored = mapStrings(arguments) { value -> restorePlain(value, unknown) } as JSONObject
        if (unknown.isNotEmpty()) throw UnknownPlaceholderException(unknown.toList())
        return restored
    }

    private fun redactPlain(text: String): String {
        if (text.isEmpty()) return text
        val spans = findSpans(text)
        if (spans.isEmpty()) return text
        val out = StringBuilder(text.length)
        var cursor = 0
        for (span in spans) {
            out.append(text, cursor, span.start)
            out.append(placeholderFor(span.kind, span.value))
            cursor = span.end
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    private fun restorePlain(text: String, unknown: MutableSet<String>): String {
        if ('[' !in text) return text
        return PLACEHOLDER.replace(text) { match ->
            val entry = byPlaceholder[match.value]
            if (entry == null) {
                unknown += match.value
                match.value
            } else {
                byValue[entry.key]
                entry.value
            }
        }
    }

    private class Span(val start: Int, val end: Int, val kind: Kind, val value: String)

    /** 按优先级收集互不重叠的命中，先到的规则优先，避免占位符内的数字再被别的规则命中。 */
    private fun findSpans(text: String): Collection<Span> {
        val accepted = TreeMap<Int, Span>()
        fun overlaps(start: Int, end: Int): Boolean {
            val before = accepted.floorEntry(start)?.value
            val after = accepted.ceilingEntry(start)?.value
            return (before != null && before.end > start) || (after != null && after.start < end)
        }
        for (rule in RULES) {
            for (match in rule.pattern.findAll(text)) {
                val group = match.groups[rule.group] ?: continue
                val start = group.range.first
                val end = group.range.last + 1
                if (overlaps(start, end)) continue
                val value = rule.normalize(group.value) ?: continue
                accepted[start] = Span(start, end, rule.kind, value)
            }
        }
        return accepted.values
    }

    private fun placeholderFor(kind: Kind, value: String): String {
        val key = "${kind.name}:$value"
        byValue[key]?.let { return it }
        val number = ++counters[kind.ordinal]
        val placeholder = "[${kind.name}_$number]"
        byValue[key] = placeholder
        byPlaceholder[placeholder] = Entry(key, value)
        while (byValue.size > capacity) {
            val eldest = byValue.entries.first()
            byValue.remove(eldest.key)
            byPlaceholder.remove(eldest.value)
        }
        return placeholder
    }

    private fun mapStrings(value: Any?, transform: (String) -> String): Any? = when (value) {
        is JSONObject -> JSONObject().also { copy ->
            value.keys().forEach { key -> copy.put(key, mapStrings(value.opt(key), transform)) }
        }
        is JSONArray -> JSONArray().also { copy ->
            for (index in 0 until value.length()) copy.put(mapStrings(value.opt(index), transform))
        }
        is String -> transform(value)
        // 整体像号码的整数（如以数字存储的手机号）同样替换，替换后变为字符串。
        is Long, is Int -> value.toString().let { digits -> transform(digits).takeIf { it != digits } ?: value }
        else -> value
    }

    private class Rule(
        val kind: Kind,
        val pattern: Regex,
        /** 返回规范化后的原值，用于去重与还原；校验不通过时返回 null。 */
        val normalize: (String) -> String?,
        val group: Int = 0,
    )

    internal companion object {
        const val DEFAULT_CAPACITY = 4096

        val PLACEHOLDER = Regex("""\[(?:EMAIL|ID_CARD|BANK_CARD|PHONE|OTP)_\d+]""")

        // 数字类规则以 ASCII 字母数字为边界，避免命中标识符、十六进制与 base64 中的数字串。
        private const val LEFT = """(?<![0-9A-Za-z_])"""
        private const val RIGHT = """(?![0-9A-Za-z_])"""

        private fun digitsOnly(value: String) = value.filter(Char::isDigit)

        private fun phone(value: String): String {
            val digits = digitsOnly(value)
            return if (value.trimStart().startsWith("+")) "+$digits" else digits
        }

        private val RULES = listOf(
            Rule(
                Kind.EMAIL,
                Regex("""(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}(?![A-Za-z0-9-])"""),
                { it },
            ),
            Rule(
                Kind.ID_CARD,
                Regex("""$LEFT[1-9]\d{5}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx]$RIGHT"""),
                { value -> value.uppercase().takeIf(::validIdCard) },
            ),
            Rule(
                Kind.BANK_CARD,
                Regex("""$LEFT(?:\d{16,19}|\d{4}(?:[ -]\d{4}){3}(?:[ -]\d{1,3})?)$RIGHT"""),
                { value -> digitsOnly(value).takeIf { it.length in 16..19 && luhn(it) } },
            ),
            // 中国大陆手机号，可带 +86/0086 与空格、连字符分组；统一为 11 位，不同写法共用一个占位符。
            Rule(
                Kind.PHONE,
                Regex("""(?<![0-9A-Za-z_+])(?:(?:\+|00)86[ -]?)?1[3-9]\d(?:[ -]?\d{4}){2}$RIGHT"""),
                { value -> digitsOnly(value).takeLast(11) },
            ),
            // 带区号的固话，要求连字符，避免命中普通数字。
            Rule(Kind.PHONE, Regex("""${LEFT}0\d{2,3}-\d{7,8}$RIGHT"""), { it }),
            // 其他国家的 E.164 号码，要求以 + 开头且不带分隔符。
            Rule(Kind.PHONE, Regex("""(?<![0-9A-Za-z_+])\+[1-9]\d{7,14}$RIGHT"""), ::phone),
            // 关键词在前：“验证码是 123456”“verification code: 123456”。
            Rule(
                Kind.OTP,
                Regex(
                    """(?i)(?:验证码|校验码|动态码|动态密码|短信码|verification code|security code|one-time code|passcode|\bOTP\b)[^\d\n]{0,12}?(?<!\d)(\d{4,8})(?!\d)""",
                ),
                ::digitsOnly,
                group = 1,
            ),
            // 号码在前：“123456 是您的验证码”。
            Rule(
                Kind.OTP,
                Regex("""(?<![0-9A-Za-z_])(\d{4,8})(?!\d)\s*(?:是|为)?(?:您|你)?的?(?:验证码|校验码|动态码|动态密码)"""),
                ::digitsOnly,
                group = 1,
            ),
        )

        private val ID_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        private const val ID_CHECK = "10X98765432"

        fun validIdCard(value: String): Boolean {
            if (value.length != 18) return false
            val sum = (0 until 17).sumOf { index -> (value[index] - '0') * ID_WEIGHTS[index] }
            return ID_CHECK[sum % 11] == value[17]
        }

        fun luhn(digits: String): Boolean {
            var sum = 0
            digits.reversed().forEachIndexed { index, char ->
                var digit = char - '0'
                if (index % 2 == 1) {
                    digit *= 2
                    if (digit > 9) digit -= 9
                }
                sum += digit
            }
            return sum % 10 == 0
        }
    }
}
