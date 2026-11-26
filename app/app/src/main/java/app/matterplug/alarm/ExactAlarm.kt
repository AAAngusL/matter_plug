package app.matterplug.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * One repeating chain of exact alarms delivered to the receiver named [receiverClassName] (a name, so the code that owns the alarm
 * need not import the receiver, which sits in a higher layer). The same PendingIntent replaces the previous alarm, so a
 * chain never has more than one pending. Battery Guard and Cellular Check each own one, with their own [requestCode].
 */
class ExactAlarm(private val requestCode: Int, private val receiverClassName: String, private val tag: String) {

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    /** Returns the wall-clock time the alarm will fire at, for the status line. Replaces any earlier alarm of this chain. */
    fun scheduleIn(context: Context, delayMs: Long): Long {
        val am = alarmManager(context)
        val at = SystemClock.elapsedRealtime() + delayMs
        val pi = pending(context)
        var exact = canScheduleExact(context)
        if (exact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (e: SecurityException) {      // permission revoked between the check and the call
                Log.w(tag, "exact alarm refused, using an inexact one", e)
                exact = false
            }
        }
        if (!exact) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)   // inexact fallback; the status line warns
        return System.currentTimeMillis() + delayMs
    }

    fun cancel(context: Context) {
        alarmManager(context).cancel(pending(context))
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext, requestCode, Intent().setClassName(context.applicationContext, receiverClassName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
