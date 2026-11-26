package app.matterplug.guard.logic

import app.matterplug.alerts.logic.AlertEvent
import app.matterplug.alerts.logic.AlertKind
import app.matterplug.alerts.logic.AlertPhase
import app.matterplug.battery.BatteryStatus
import app.matterplug.battery.Power

data class AlertResult(val state: AlertState, val events: List<AlertEvent>)

object AlertPolicy {
    const val REMINDER_MS = 60L * 60 * 1000
    const val FAILS_BEFORE_ALERT = 2          // the same count for the faulty-check alert

    fun apply(
        state: AlertState, checkEvents: List<CheckEvent>,
        battery: Int, power: Power, nowMs: Long,
    ): AlertResult {
        var failed = state.failedChecks
        var degraded = state.degradedChecks
        var sawDegraded = false
        var serviceStarted = false
        val active = state.active.toMutableList()
        val out = mutableListOf<AlertEvent>()

        fun isActive(k: AlertKind) = active.any { it.kind == k }
        fun start(k: AlertKind, wantedOn: Boolean?, detail: String? = null) {
            active += ActiveAlert(k, nowMs, nowMs, wantedOn, detail)
            out += AlertEvent(k, AlertPhase.Started, battery, power, wantedOn, nowMs, detail)
        }
        fun recover(k: AlertKind) {
            val a = active.firstOrNull { it.kind == k } ?: return
            active.remove(a)
            out += AlertEvent(k, AlertPhase.Recovered, battery, power, a.wantedOn, nowMs, a.detail)
        }

        fun reached() {
            failed = 0
            recover(AlertKind.PlugUnreachable)
        }
        /** A switch failure and a missed read share one count and one alert; [wantedOn] is null for a read. */
        fun missed(wantedOn: Boolean?) {
            failed += 1
            if (failed >= FAILS_BEFORE_ALERT && !isActive(AlertKind.PlugUnreachable)) start(AlertKind.PlugUnreachable, wantedOn)
        }

        for (e in checkEvents) {
            when (e) {
                // PLUG-WATCH.md section 4: only reaching the plug ends the alert; no switch needed says nothing about it.
                CheckEvent.NoSwitchNeeded -> {}
                // No charger plug (unpaired or removed): there is nothing to reach, so end the count and the alert.
                CheckEvent.NoPlug -> reached()
                is CheckEvent.SwitchConfirmed -> reached()
                is CheckEvent.SwitchFailed -> missed(e.wantedOn)
                is CheckEvent.PlugRead -> if (e.reached) reached() else missed(null)
                is CheckEvent.PowerWait -> {
                    if (!e.pluggedIn && !isActive(AlertKind.NotCharging)) start(AlertKind.NotCharging, null)
                }
                CheckEvent.ServiceStarted -> serviceStarted = true
                is CheckEvent.Degraded -> {
                    sawDegraded = true
                    degraded += 1
                    val i = active.indexOfFirst { it.kind == AlertKind.GuardDegraded }
                    if (i >= 0) active[i] = active[i].copy(detail = e.reason)     // the reason changed: the next reminder names it
                    else if (degraded >= FAILS_BEFORE_ALERT) start(AlertKind.GuardDegraded, null, e.reason)
                }
            }
        }
        // RECOVERY-SIGNALS.md 2.2: each reason needs its own proof. Battery and exact alarms are checked at every check;
        // the service only when one was started, so a check that did not try says nothing about it.
        if (!sawDegraded) {
            degraded = 0
            val a = active.firstOrNull { it.kind == AlertKind.GuardDegraded }
            if (a != null && (a.detail != CheckDecision.FAULT_SERVICE || serviceStarted)) recover(AlertKind.GuardDegraded)
        }
        // RECOVERY-SIGNALS.md 2.1: plugged in, and the battery taking charge (or full).
        if (power.pluggedIn && (power.status == BatteryStatus.Charging || power.status == BatteryStatus.Full)) {
            recover(AlertKind.NotCharging)
        }

        // Reminders: an alert started in this call has lastSentAtMs = now, so it is not reminded.
        for (i in active.indices) {
            val a = active[i]
            if (nowMs - a.lastSentAtMs >= REMINDER_MS) {
                active[i] = a.copy(lastSentAtMs = nowMs)
                out += AlertEvent(a.kind, AlertPhase.Reminder, battery, power, a.wantedOn, nowMs, a.detail)
            }
        }
        return AlertResult(AlertState(failed, degraded, active.toList()), out)
    }
}
