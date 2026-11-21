package app.matterplug.guard.logic

/** Which switch closed an alert without a recovery (RECOVERY-SIGNALS.md 2.4). [cause] starts the cancelled message. */
enum class CancelledBy(val wire: String, val cause: String) {
    GuardOff("guard_off", "Battery Guard turned off"),
    AlertsOff("alerts_off", "Battery Guard alerts turned off"),
    CellularOff("cellular_off", "Cellular Check turned off"),
    CellularAlertsOff("cellular_alerts_off", "Cellular alerts turned off"),
    IpOff("ip_off", "Fixed IP turned off"),
    IpUnpinned("ip_unpinned", "Fixed IP network unpinned"),
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

    /** What a change of one feature's two switches does to its open alert; each feature names its own [off] and [alertsOff] values. */
    fun change(
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
