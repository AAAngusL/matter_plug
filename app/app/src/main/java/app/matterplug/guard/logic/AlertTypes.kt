package app.matterplug.guard.logic

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

/** wantedOn: for PlugUnreachable, true if the switch that failed was On (battery low), false if Off (battery high); null when it
 *  started from a plug read with no switch wanted (PLUG-WATCH.md), and for other kinds.
 *  detail: for GuardDegraded, the reason ("exact alarms not allowed", "battery unreadable", "service could not start"); null otherwise. */
data class ActiveAlert(
    val kind: AlertKind, val startedAtMs: Long, val lastSentAtMs: Long,
    val wantedOn: Boolean?, val detail: String? = null,
)

data class AlertState(
    val failedChecks: Int = 0,          // consecutive checks where a needed switch failed
    val degradedChecks: Int = 0,        // consecutive checks that found a fault of Battery Guard itself
    val active: List<ActiveAlert> = emptyList(),
)

/** What one check observed, in order. A switch On that is confirmed is followed by a PowerWait. */
sealed interface CheckEvent {
    data object NoSwitchNeeded : CheckEvent
    data object NoPlug : CheckEvent                                   // no charger plug to watch: nothing can be unreachable
    data class PlugRead(val reached: Boolean) : CheckEvent             // PLUG-WATCH.md: a read-only check of the charger plug
    data class SwitchConfirmed(val on: Boolean) : CheckEvent
    data class SwitchFailed(val wantedOn: Boolean) : CheckEvent      // not reached, or not confirmed
    data class PowerWait(val pluggedIn: Boolean) : CheckEvent         // result of the 30-second wait
    data class Degraded(val reason: String) : CheckEvent              // a fault of Battery Guard itself
    data object ServiceStarted : CheckEvent                            // the switching service ran: proof it can start
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
