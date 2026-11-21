package app.matterplug.fixedip.logic

import app.matterplug.guard.logic.AlertJson
import app.matterplug.guard.logic.AlertPhase

/** Whether the SMS for this step went out. Known before the HTTP request is built, because the SMS is sent first (docs/FIXED-IP.md section 7). */
enum class SmsOutcome(val wire: String) { Sent("sent"), Failed("failed") }

/** The `ip` object of an ip_drift request, and the numbers the texts use. [current] is null when there is no Wi-Fi address. */
data class IpSummary(val pinned: String, val current: String?, val ssid: String?, val reason: DriftReason, val sms: SmsOutcome)

/** The wire form and the texts of ip_drift. The SMS and the `message` field say the same thing (section 7). */
object IpReport {
    /** `{"pinned":…,"current":…,"ssid":…,"reason":…,"sms":…}`; current and ssid only when known. */
    fun json(s: IpSummary): String = listOfNotNull(
        "\"pinned\":${AlertJson.q(s.pinned)}",
        s.current?.let { "\"current\":${AlertJson.q(it)}" },
        s.ssid?.let { "\"ssid\":${AlertJson.q(it)}" },
        "\"reason\":${AlertJson.q(s.reason.wire)}",
        "\"sms\":${AlertJson.q(s.sms.wire)}",
    ).joinToString(",", "{", "}")

    /**
     * The message for a started alert, a reminder (AlertMessages makes the reminder) and a recovery, at most 100 characters even with
     * two 15-character addresses. The battery goes last; [battery] of 0 or less means it could not be read, and the sentence is left out.
     */
    fun text(phase: AlertPhase, s: IpSummary?, battery: Int): String {
        val tail = if (battery > 0) " Battery $battery%." else ""
        if (s == null) return "Matter Plug: IP problem.$tail"
        if (phase == AlertPhase.Recovered) return "Matter Plug: IP is back at ${s.pinned}.$tail"
        val now = s.current ?: "unknown"
        return when (s.reason) {
            DriftReason.AddressChanged -> "Matter Plug: IP changed from ${s.pinned} to $now. Dialer may lose it.$tail"
            DriftReason.GatewayChanged -> "Matter Plug: IP gateway changed. IP is $now. Dialer may not reach it.$tail"
            DriftReason.NoWifi -> "Matter Plug: no Wi-Fi for 1 minute. Dialer cannot reach it.$tail"
        }
    }
}
