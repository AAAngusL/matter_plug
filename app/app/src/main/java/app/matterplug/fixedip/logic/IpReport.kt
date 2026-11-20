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

    /** The message for a started alert, a reminder (AlertMessages makes the reminder) and a recovery. */
    fun text(phase: AlertPhase, s: IpSummary?): String {
        if (s == null) return "Matter Plug: the phone's Wi-Fi address."
        if (phase == AlertPhase.Recovered) return recoveredText(s.pinned)
        return when (s.reason) {
            DriftReason.AddressChanged ->
                "Matter Plug: the phone's Wi-Fi address changed from ${s.pinned} to ${s.current ?: "unknown"}. The dialer may not reach it."
            DriftReason.GatewayChanged ->
                "Matter Plug: the phone's Wi-Fi gateway changed. Its address is ${s.current ?: "unknown"}. The dialer may not reach it."
            DriftReason.NoWifi -> "Matter Plug: the phone has had no Wi-Fi for 1 minute. The dialer cannot reach it."
        }
    }

    fun recoveredText(pinned: String): String = "Matter Plug: the phone's Wi-Fi address is back at $pinned."
}
