package app.matterplug.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.matterplug.alarm.runAsync
import android.util.Log
import app.matterplug.cellular.CellularRunner
import kotlinx.coroutines.CancellationException

/**
 * Receives Battery Guard's alarm: Cellular Check's run (which does nothing while it is off), then Battery Guard's check.
 * One chain of exact alarms for both features while Battery Guard is on; see CellularScheduler. The cellular run goes
 * first so the check's status report carries this check's signal reading, not the previous one (SIGNAL-STRENGTH.md 6).
 */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context, "Guard", "check") {
        try {
            CellularRunner.run(it)
        } catch (e: Exception) {      // a cellular failure must never cost Battery Guard its check
            if (e is CancellationException) throw e
            Log.e("Guard", "cellular run failed; Battery Guard checks on", e)
        }
        GuardRunner.onAlarm(it)
    }
}
