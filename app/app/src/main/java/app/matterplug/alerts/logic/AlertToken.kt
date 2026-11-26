package app.matterplug.alerts.logic

/**
 * The value of the Authorization header for [token], or null when no header is to be sent (blank token).
 * The token is trimmed; it is sent as a bearer token. Never log the result.
 */
fun authorizationHeader(token: String): String? {
    val t = token.trim()
    return if (t.isEmpty()) null else "Bearer $t"
}

/** The PostResult for a response with status [code]: 401 and 403 carry the error token "rejected". */
fun resultForCode(code: Int): PostResult =
    PostResult(code, if (code == 401 || code == 403) DeliveryError.REJECTED else null)

/** True when a token is set and the address is plain http (not https): the token could be read on the network. */
fun tokenOverPlainHttp(token: String, address: String): Boolean =
    token.isNotBlank() && address.trim().startsWith("http://", ignoreCase = true)
