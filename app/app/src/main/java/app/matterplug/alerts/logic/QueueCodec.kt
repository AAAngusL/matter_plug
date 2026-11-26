package app.matterplug.alerts.logic

/** Each body is URL-safe Base64 without padding, entries joined by a newline, oldest first. */
object QueueCodec {
    fun encode(bodies: List<String>): String = bodies.joinToString("\n") { b64(it) }

    fun decode(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val out = mutableListOf<String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            try { out += unb64(line) } catch (e: Exception) { /* skip a damaged entry */ }
        }
        return out
    }
}
