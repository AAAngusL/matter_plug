package app.matterplug.guard

import android.content.Context
import app.matterplug.alarm.ExactAlarm

/** The one alarm of Battery Guard (request code 1). */
object CheckScheduler {
    private val alarm = ExactAlarm(requestCode = 1, receiver = CheckReceiver::class.java, tag = "Guard")

    fun canScheduleExact(context: Context): Boolean = alarm.canScheduleExact(context)

    /** Returns the wall-clock time the check will run at, for the status line. Replaces any earlier alarm. */
    fun scheduleIn(context: Context, delayMs: Long): Long = alarm.scheduleIn(context, delayMs)

    fun cancel(context: Context) = alarm.cancel(context)
}
