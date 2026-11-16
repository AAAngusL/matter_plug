package app.matterplug.guard.ui

import app.matterplug.guard.GuardStatus
import app.matterplug.guard.logic.ActiveAlert
import app.matterplug.guard.logic.AlertKind
import app.matterplug.guard.logic.BatteryStatus
import app.matterplug.guard.logic.Power

/**
 * One line under the controls, as data. No Android imports, so it is unit-tested; GuardSection turns each line
 * into text with string resources (no user-visible text is written in Kotlin). Times are already formatted
 * by the caller (device 12 or 24-hour setting).
 */
sealed interface StatusLine {
    data object NoCheckYet : StatusLine
    /** [level] or [power] null: the battery could not be read at that check. [nextTime] null: none scheduled. */
    data class LastCheck(val time: String, val level: Int?, val power: Power?, val nextTime: String?) : StatusLine
    data class LastSwitch(val on: Boolean, val level: Int, val time: String) : StatusLine
    data object NoAlerts : StatusLine
    data class PlugReached(val time: String, val on: Boolean?) : StatusLine      // PLUG-WATCH.md section 7
    data class PlugMissed(val sinceTime: String) : StatusLine
    /** One per active alert. [detail] only for the third alert; [wantedOn] only for PlugUnreachable. */
    data class Alert(val kind: AlertKind, val sinceTime: String, val lastSentTime: String, val wantedOn: Boolean?, val detail: String?) : StatusLine
    data class DeliveryNone(val waiting: Int) : StatusLine
    data class DeliveryOk(val time: String, val waiting: Int) : StatusLine
    data class DeliveryFailed(val time: String, val error: String?, val waiting: Int) : StatusLine
    data class RunError(val text: String) : StatusLine
    data object ExactAlarmWarning : StatusLine
}

object StatusText {
    /**
     * @param enabled Battery Guard switch; with it off, the check, switch and alert lines are not shown, only delivery
     * @param exactAlarmsAllowed false adds the warning (only while enabled)
     */
    fun lines(
        status: GuardStatus,
        enabled: Boolean,
        exactAlarmsAllowed: Boolean,
        formatTime: (Long) -> String,
    ): List<StatusLine> {
        val out = mutableListOf<StatusLine>()
        val s = status

        val checkAt = s.lastCheckAt
        if (checkAt == null) {
            out += StatusLine.NoCheckYet
        } else {
            // A next check time is only meaningful while enabled; the store clears it when Battery Guard is switched off.
            val power = s.lastPlugged?.let { Power(it, s.lastBatteryStatus ?: BatteryStatus.Unknown) }
            out += StatusLine.LastCheck(formatTime(checkAt), s.lastLevel, power, if (enabled) s.nextCheckAt?.let(formatTime) else null)
        }
        val swAt = s.lastSwitchAt; val swOn = s.lastSwitchOn; val swLevel = s.lastSwitchLevel
        if (swAt != null && swOn != null && swLevel != null) out += StatusLine.LastSwitch(swOn, swLevel, formatTime(swAt))

        if (enabled) {      // PLUG-WATCH.md section 7: a miss wins over the last reach
            val p = s.plug
            val missed = p.missedSince; val reachedAt = p.reachedAt
            if (missed != null) out += StatusLine.PlugMissed(formatTime(missed))
            else if (reachedAt != null) out += StatusLine.PlugReached(formatTime(reachedAt), p.on)
        }

        if (s.alertState.active.isEmpty()) out += StatusLine.NoAlerts
        else for (a: ActiveAlert in s.alertState.active) {
            out += StatusLine.Alert(a.kind, formatTime(a.startedAtMs), formatTime(a.lastSentAtMs), a.wantedOn, a.detail)
        }

        val ok = s.lastDeliveryOkAt; val fail = s.lastDeliveryFailAt
        out += when {
            fail != null && (ok == null || fail >= ok) -> StatusLine.DeliveryFailed(formatTime(fail), s.lastDeliveryError, s.queueSize)
            ok != null -> StatusLine.DeliveryOk(formatTime(ok), s.queueSize)
            else -> StatusLine.DeliveryNone(s.queueSize)
        }

        s.lastRunError?.let { out += StatusLine.RunError(it) }
        if (enabled && !exactAlarmsAllowed) out += StatusLine.ExactAlarmWarning
        return out
    }
}
