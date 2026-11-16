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
                is CheckEvent.SwitchConfirmed -> reached()
                is CheckEvent.SwitchFailed -> missed(e.wantedOn)
                is CheckEvent.PlugRead -> if (e.reached) reached() else missed(null)
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
