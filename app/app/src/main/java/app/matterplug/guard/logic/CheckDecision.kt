package app.matterplug.guard.logic

/** Pure helpers for the check events (BATTERY-GUARD-IMPLEMENTATION.md 5.5) and the notification ids (5.7). */
object CheckDecision {
    const val FAULT_SERVICE = "service could not start"

    /**
     * The events `onAlarm` passes to `AlertPolicy`. NoSwitchNeeded when nothing is to be switched and the battery was
     * read. At most ONE Degraded event, because AlertPolicy counts every Degraded event as one faulty check:
     * "service could not start" wins over [fault] when the service was refused.
     */
    fun alarmEvents(
        action: GuardAction, batteryRead: Boolean, fault: String?, serviceFailed: Boolean, plugEvent: CheckEvent? = null,
    ): List<CheckEvent> {
        val out = mutableListOf<CheckEvent>()
        if (action == GuardAction.Nothing && batteryRead) out += CheckEvent.NoSwitchNeeded
        if (plugEvent != null) out += plugEvent       // NoPlug or PlugRead, from a read-only check of the plug (PLUG-WATCH.md section 4)
        val reason = if (serviceFailed) FAULT_SERVICE else fault
        if (reason != null) out += CheckEvent.Degraded(reason)
        return out
    }

    /**
     * True when the check ends here: events are exactly [NoSwitchNeeded], with or without a plug read that reached the
     * plug, or a note that there is no plug (PLUG-WATCH.md), the stored alert state has no failed or
     * faulty checks and no active alerts, the waiting list is empty and status reports are off (a quiet check).
     */
    fun nothingToDo(settings: GuardSettings, state: AlertState, queueEmpty: Boolean, events: List<CheckEvent>): Boolean =
        events.filterNot { it == CheckEvent.PlugRead(true) || it == CheckEvent.NoPlug } == listOf<CheckEvent>(CheckEvent.NoSwitchNeeded) &&
            state.failedChecks == 0 && state.degradedChecks == 0 && state.active.isEmpty() &&
            queueEmpty && !settings.statusEnabled

    /** One notification id per alert kind, so a reminder or a recovery replaces the earlier notification of that kind. */
    fun notificationId(kind: AlertKind): Int = when (kind) {
        AlertKind.NotCharging -> 4201
        AlertKind.PlugUnreachable -> 4202
        AlertKind.GuardDegraded -> 4203
        AlertKind.Status -> 4204
        AlertKind.Test -> 4205
        AlertKind.CellularDown -> 4206
        AlertKind.CellularStatus -> 4207
        AlertKind.IpDrift -> 4208
    }
}
