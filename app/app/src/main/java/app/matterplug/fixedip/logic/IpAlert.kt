package app.matterplug.fixedip.logic

import app.matterplug.guard.logic.AlertPhase

/** What to do about an alert after a new state (docs/FIXED-IP.md section 7). The same action covers the HTTP alert and the SMS. */
sealed interface IpAlertAction {
    data object None : IpAlertAction
    data class Start(val reason: DriftReason) : IpAlertAction
    data object End : IpAlertAction
}

object IpAlert {
    /**
     * @param open an alert has started and has not ended
     *
     * A start needs a Drift, or a NoWifi whose wait is over. An open alert ends only on proof, a Match (Battery Guard's rule too): leaving Wi-Fi, another network or an unpinned state keep it open. A state change inside an open alert (say, a drift that
     * becomes no Wi-Fi) is not a second start; the alert server's own reminders carry it.
     */
    fun next(open: Boolean, state: IpState): IpAlertAction = when {
        open -> if (state == IpState.Match) IpAlertAction.End else IpAlertAction.None
        state is IpState.Drift -> IpAlertAction.Start(state.reason)
        state is IpState.NoWifi && state.due -> IpAlertAction.Start(DriftReason.NoWifi)
        else -> IpAlertAction.None
    }
}

/** An open alert as it is kept between reads: when it started, when the server was last told, and why. */
data class IpAlertMemo(val startedAtMs: Long, val lastSentAtMs: Long, val reason: DriftReason)

object IpAlertPolicy {
    /** The alert server's reminders come on Battery Guard's cadence (section 7); the SMS never repeats. */
    const val REMINDER_MS = 60L * 60 * 1000

    /**
     * Moves the alert on one step. Returns the new memo (null when no alert is open) and the phase to send: Started, Reminder
     * (HTTP only, never an SMS) or Recovered; null when nothing is due.
     */
    fun step(memo: IpAlertMemo?, state: IpState, nowMs: Long): Pair<IpAlertMemo?, AlertPhase?> =
        when (val action = IpAlert.next(memo != null, state)) {
            is IpAlertAction.Start -> IpAlertMemo(nowMs, nowMs, action.reason) to AlertPhase.Started
            IpAlertAction.End -> null to AlertPhase.Recovered
            IpAlertAction.None ->
                if (memo != null && nowMs - memo.lastSentAtMs >= REMINDER_MS) memo.copy(lastSentAtMs = nowMs) to AlertPhase.Reminder
                else memo to null
        }

    fun encode(m: IpAlertMemo?): String = m?.let { "${it.startedAtMs},${it.lastSentAtMs},${it.reason.wire}" } ?: ""

    /** Null for an empty or damaged value: a damaged memo reads as "no alert open", so a still-present fault starts a new alert. */
    fun decode(text: String?): IpAlertMemo? {
        val p = text?.split(',') ?: return null
        if (p.size != 3) return null
        val started = p[0].toLongOrNull() ?: return null
        val last = p[1].toLongOrNull() ?: return null
        val reason = DriftReason.entries.firstOrNull { it.wire == p[2] } ?: return null
        return IpAlertMemo(started, last, reason)
    }
}
