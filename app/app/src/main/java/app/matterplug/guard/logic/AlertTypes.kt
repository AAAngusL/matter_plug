package app.matterplug.guard.logic

import app.matterplug.cellular.logic.CellularSummary

enum class AlertKind(val wire: String) {
    NotCharging("not_charging"), PlugUnreachable("plug_unreachable"), GuardDegraded("guard_degraded"),
    CellularDown("cellular_down"),      // Cellular Check (docs/CELLULAR-CHECK.md section 8)
    CellularStatus("cellular_status"),  // Cellular Check's own status report (docs/SIGNAL-STRENGTH.md section 6)
    Status("status"), Test("test")
}

enum class AlertPhase(val wire: String) {
    Started("started"), Reminder("reminder"), Recovered("recovered"), Status("status"), Test("test")
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
}

data class AlertEvent(
    val kind: AlertKind, val phase: AlertPhase,
    val battery: Int, val power: Power, val wantedOn: Boolean?, val atMs: Long,
    val detail: String? = null,
    val nextCheckInMin: Int? = null,    // status reports only
    val cellular: CellularSummary? = null,      // Cellular Check's requests only: cellular_status and its alerts
)
