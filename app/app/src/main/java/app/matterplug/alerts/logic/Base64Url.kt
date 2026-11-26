package app.matterplug.alerts.logic

import java.util.Base64

private val B64_ENC = Base64.getUrlEncoder().withoutPadding()
private val B64_DEC = Base64.getUrlDecoder()

internal fun b64(s: String): String = B64_ENC.encodeToString(s.toByteArray(Charsets.UTF_8))
internal fun unb64(s: String): String = String(B64_DEC.decode(s), Charsets.UTF_8)
