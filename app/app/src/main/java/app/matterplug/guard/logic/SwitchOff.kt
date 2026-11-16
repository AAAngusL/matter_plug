package app.matterplug.guard.logic

import app.matterplug.cellular.logic.CellularSettings

/** Which switch closed an alert without a recovery (RECOVERY-SIGNALS.md 2.4). [phrase] starts the cancelled message. */
enum class CancelledBy(val wire: String, val phrase: String) {
    GuardOff("guard_off", "Battery Guard was turned off"),
    AlertsOff("alerts_off", "Battery Guard alerts were turned off"),
    CellularOff("cellular_off", "Cellular Check was turned off"),
    CellularAlertsOff("cellular_alerts_off", "Cellular alerts were turned off"),
}

/** What a change of a feature's switches does to its alerts. */
object SwitchOff {
    /**
     * [cancelledBy]: the open alerts end with cancelled (only when the server was being told, alerts on before).
     * [reset]: the alert state is cleared, also when alerts are turned back on, so alerts tracked while they were off
     * never reach the server as a stray reminder.
     */
    data class Change(val cancelledBy: CancelledBy?, val reset: Boolean)

    fun guard(old: GuardSettings, new: GuardSettings): Change =
        change(old.enabled, new.enabled, old.alertsEnabled, new.alertsEnabled, CancelledBy.GuardOff, CancelledBy.AlertsOff)

    fun cellular(old: CellularSettings, new: CellularSettings): Change =
        change(old.enabled, new.enabled, old.alertsEnabled, new.alertsEnabled, CancelledBy.CellularOff, CancelledBy.CellularAlertsOff)

    private fun change(
        wasOn: Boolean, isOn: Boolean, alertsWere: Boolean, alertsAre: Boolean, off: CancelledBy, alertsOff: CancelledBy,
    ): Change {
        val turnedOff = wasOn && !isOn
        val alertsChanged = alertsWere != alertsAre
        val by = when {
            !wasOn || !alertsWere -> null
            turnedOff -> off
            !alertsAre -> alertsOff
            else -> null
        }
        return Change(by, turnedOff || alertsChanged)
    }

    /** One cancelled request per open alert, with that alert's own fields. */
    fun cancelled(active: List<ActiveAlert>, by: CancelledBy, battery: Int, power: Power, nowMs: Long): List<AlertEvent> =
        active.map { AlertEvent(it.kind, AlertPhase.Cancelled, battery, power, it.wantedOn, nowMs, it.detail, cancelledBy = by) }
}
