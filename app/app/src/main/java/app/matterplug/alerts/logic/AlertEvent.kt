package app.matterplug.alerts.logic

import app.matterplug.battery.Power

enum class AlertKind(val wire: String) {
    NotCharging("not_charging"), PlugUnreachable("plug_unreachable"), GuardDegraded("guard_degraded"),
    CellularDown("cellular_down"),      // Cellular Check (docs/CELLULAR-CHECK.md section 8)
    CellularStatus("cellular_status"),  // Cellular Check's own status report (docs/SIGNAL-STRENGTH.md section 6)
    IpDrift("ip_drift"),                // Fixed IP: the Wi-Fi address changed or Wi-Fi is lost (docs/FIXED-IP.md section 7)
    Status("status"), Test("test")
}

enum class AlertPhase(val wire: String) {
    Started("started"), Reminder("reminder"), Recovered("recovered"), Cancelled("cancelled"), Status("status"), Test("test")
}

data class AlertEvent(
    val kind: AlertKind, val phase: AlertPhase,
    val battery: Int, val power: Power, val wantedOn: Boolean?, val atMs: Long,
    val detail: String? = null,
    val nextCheckInMin: Int? = null,    // status reports only
    val cancelledBy: CancelledBy? = null,       // cancelled only (RECOVERY-SIGNALS.md 2.4)
    val extra: AlertExtra? = null,              // a feature's own part: Cellular Check's cellular object, Fixed IP's ip object
)

/**
 * A feature's part of an alert: its object in the request (`"<jsonKey>":<json()>`, after next_check_in_min) and the messages it owns for
 * its started, reminder, recovered and status events. A feature that sends alerts implements this, so the alert types, the JSON and the
 * texts know no feature, and a new one changes none of them.
 */
interface AlertExtra {
    val jsonKey: String
    fun json(): String
    fun text(e: AlertEvent): String
}
