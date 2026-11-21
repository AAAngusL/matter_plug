package app.matterplug.sms

/** Whether an alert SMS reached any number, as the wire and the screen name it. */
enum class SmsOutcome(val wire: String) { Sent("sent"), Failed("failed") }

/** Judges one alert SMS sent to several numbers (docs/FIXED-IP.md section 6). Pure, so it is unit-tested. */
object SmsResult {
    /** The step counts as sent when at least one number accepted its message; no numbers, or none accepted, is failed. */
    fun outcome(accepted: List<Boolean>): SmsOutcome = if (accepted.any { it }) SmsOutcome.Sent else SmsOutcome.Failed

    /** The line under "Last SMS": when, what and, if it failed, why. Stored as `atMs|outcome|detail`. */
    fun encode(atMs: Long, outcome: SmsOutcome, detail: String?): String = "$atMs|${outcome.wire}|${detail.orEmpty()}"

    data class Last(val atMs: Long, val outcome: SmsOutcome, val detail: String?)

    fun decode(text: String?): Last? {
        val p = text?.split('|', limit = 3) ?: return null
        if (p.size != 3) return null
        val at = p[0].toLongOrNull() ?: return null
        val outcome = SmsOutcome.entries.firstOrNull { it.wire == p[1] } ?: return null
        return Last(at, outcome, p[2].ifEmpty { null })
    }
}
