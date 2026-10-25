package io.github.mangi.eta.ui.screens.browser

import android.net.Uri
import android.util.Patterns

private val AddressScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/** 地址栏输入才参与搜索判断，Agent 的导航工具仍按原始 URL 执行。 */
internal fun resolveBrowserAddress(input: String): String? {
    val value = input.trim().takeIf { it.isNotEmpty() } ?: return null
    if (AddressScheme.containsMatchIn(value)) return value

    val address = Uri.parse("https://$value")
    val authority = address.encodedAuthority.orEmpty()
    if (value.none(Char::isWhitespace) &&
        (Patterns.WEB_URL.matcher(authority).matches() || address.host.equals("localhost", ignoreCase = true))
    ) {
        return address.toString()
    }
    return Uri.Builder()
        .scheme("https")
        .authority("www.google.com")
        .path("search")
        .appendQueryParameter("q", value)
        .build()
        .toString()
}
