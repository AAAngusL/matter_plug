package app.matterplug.guard.logic

import app.matterplug.alerts.logic.AlertEvent
import app.matterplug.alerts.logic.AlertPhase
import app.matterplug.alerts.logic.CancelledBy
import app.matterplug.alerts.logic.SwitchOff
import app.matterplug.battery.Power

/** What a change of Battery Guard's switches does to its alerts (the rule itself is [SwitchOff.change]). */
object GuardSwitchOff {
    fun change(old: GuardSettings, new: GuardSettings): SwitchOff.Change =
        SwitchOff.change(old.enabled, new.enabled, old.alertsEnabled, new.alertsEnabled, CancelledBy.GuardOff, CancelledBy.AlertsOff)

    /** One cancelled request per open alert, with that alert's own fields. */
    fun cancelled(active: List<ActiveAlert>, by: CancelledBy, battery: Int, power: Power, nowMs: Long): List<AlertEvent> =
        active.map { AlertEvent(it.kind, AlertPhase.Cancelled, battery, power, it.wantedOn, nowMs, it.detail, cancelledBy = by) }
}
