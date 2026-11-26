package app.matterplug.guard

import android.content.Context
import app.matterplug.alarm.ExactAlarm

/** The one alarm of Battery Guard (request code 1). */
object CheckScheduler {
    /** The receiver in `checks` that runs the check; named, not imported, so `guard` stays below `checks`. */
    const val CHECK_RECEIVER = "app.matterplug.checks.CheckReceiver"

    private val alarm = ExactAlarm(requestCode = 1, receiverClassName = CHECK_RECEIVER, tag = "Guard")

    fun canScheduleExact(context: Context): Boolean = alarm.canScheduleExact(context)

    /** Returns the wall-clock time the check will run at, for the status line. Replaces any earlier alarm. */
    fun scheduleIn(context: Context, delayMs: Long): Long = alarm.scheduleIn(context, delayMs)

    fun cancel(context: Context) = alarm.cancel(context)
}
