package app.matterplug.alerts.logic

object AlertAddress {
    fun isValid(text: String): Boolean {
        val t = text.trim()
        if (!(t.startsWith("http://") || t.startsWith("https://"))) return false
        return try { !java.net.URI(t).host.isNullOrBlank() } catch (e: Exception) { false }
    }

    /** The host part only, for log lines (the full address is never logged). Null when there is none. */
    fun hostOf(text: String): String? = try { java.net.URI(text.trim()).host?.takeIf { it.isNotBlank() } } catch (e: Exception) { null }
}
