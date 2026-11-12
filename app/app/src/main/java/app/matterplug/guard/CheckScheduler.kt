package app.matterplug.guard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/** The one alarm of Battery Guard. The same PendingIntent replaces the previous alarm, so there is never more than one. */
object CheckScheduler {
    private const val TAG = "Guard"

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    /** Returns the wall-clock time the check will run at, for the status line. Replaces any earlier alarm. */
    fun scheduleIn(context: Context, delayMs: Long): Long {
        val am = alarmManager(context)
        val at = SystemClock.elapsedRealtime() + delayMs
        val pi = pending(context)
        var exact = canScheduleExact(context)
        if (exact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (e: SecurityException) {      // permission revoked between the check and the call
                Log.w(TAG, "exact alarm refused, using an inexact one", e)
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
        context.applicationContext, 1, Intent(context.applicationContext, CheckReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
