package app.matterplug.guard.logic

object AlertJson {
    /** ISO 8601 with the UTC offset and whole seconds, e.g. 2026-11-11T09:47:12-05:00. */
    fun isoTime(atMs: Long, zone: java.time.ZoneId): String =
        java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .format(java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(atMs), zone).withNano(0))

    /** Keys in this order: alert, state, message, battery, charging, time, and next_check_in_min only when it is not null (status reports). */
    fun encode(e: AlertEvent, message: String, time: String): String =
        """{"alert":${q(e.kind.wire)},"state":${q(e.phase.wire)},"message":${q(message)},""" +
            """"battery":${e.battery},"charging":${e.pluggedIn},"time":${q(time)}""" +
            (e.nextCheckInMin?.let { ""","next_check_in_min":$it""" } ?: "") + "}"

    /** JSON string with quotes, escaping backslash, quote and control characters. */
    fun q(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
