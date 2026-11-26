package app.matterplug.alerts.logic

/** What a change of a feature's switches does to its alerts. */
object SwitchOff {
    /**
     * [cancelledBy]: the open alerts end with cancelled (only when the server was being told, alerts on before).
     * [reset]: the alert state is cleared, also when alerts are turned back on, so alerts tracked while they were off
     * never reach the server as a stray reminder.
     */
    data class Change(val cancelledBy: CancelledBy?, val reset: Boolean)

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
}
