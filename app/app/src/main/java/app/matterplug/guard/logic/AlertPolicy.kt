package app.matterplug.guard.logic

data class AlertResult(val state: AlertState, val events: List<AlertEvent>)

object AlertPolicy {
    const val REMINDER_MS = 60L * 60 * 1000
    const val FAILS_BEFORE_ALERT = 2          // the same count for the faulty-check alert

    fun apply(
        state: AlertState, checkEvents: List<CheckEvent>,
        battery: Int, pluggedIn: Boolean, nowMs: Long,
    ): AlertResult {
        var failed = state.failedChecks
        var degraded = state.degradedChecks
        var sawDegraded = false
        val active = state.active.toMutableList()
        val out = mutableListOf<AlertEvent>()

        fun isActive(k: AlertKind) = active.any { it.kind == k }
        fun start(k: AlertKind, wantedOn: Boolean?, detail: String? = null) {
            active += ActiveAlert(k, nowMs, nowMs, wantedOn, detail)
            out += AlertEvent(k, AlertPhase.Started, battery, pluggedIn, wantedOn, nowMs, detail)
        }
        fun recover(k: AlertKind) {
            val a = active.firstOrNull { it.kind == k } ?: return
            active.remove(a)
            out += AlertEvent(k, AlertPhase.Recovered, battery, pluggedIn, a.wantedOn, nowMs, a.detail)
        }

        for (e in checkEvents) {
            when (e) {
                CheckEvent.NoSwitchNeeded, is CheckEvent.SwitchConfirmed -> {
                    failed = 0
                    recover(AlertKind.PlugUnreachable)
                }
                is CheckEvent.SwitchFailed -> {
                    failed += 1
                    if (failed >= FAILS_BEFORE_ALERT && !isActive(AlertKind.PlugUnreachable)) {
                        start(AlertKind.PlugUnreachable, e.wantedOn)
                    }
                }
                is CheckEvent.PowerWait -> {
                    if (!e.pluggedIn && !isActive(AlertKind.NotCharging)) start(AlertKind.NotCharging, null)
                }
                is CheckEvent.Degraded -> {
                    sawDegraded = true
                    degraded += 1
                    if (degraded >= FAILS_BEFORE_ALERT && !isActive(AlertKind.GuardDegraded)) {
                        start(AlertKind.GuardDegraded, null, e.reason)
                    }
                }
            }
        }
        if (!sawDegraded) {
            degraded = 0
            recover(AlertKind.GuardDegraded)
        }
        if (pluggedIn) recover(AlertKind.NotCharging)

        // Reminders: an alert started in this call has lastSentAtMs = now, so it is not reminded.
        for (i in active.indices) {
            val a = active[i]
            if (nowMs - a.lastSentAtMs >= REMINDER_MS) {
                active[i] = a.copy(lastSentAtMs = nowMs)
                out += AlertEvent(a.kind, AlertPhase.Reminder, battery, pluggedIn, a.wantedOn, nowMs, a.detail)
            }
        }
        return AlertResult(AlertState(failed, degraded, active.toList()), out)
    }
}
