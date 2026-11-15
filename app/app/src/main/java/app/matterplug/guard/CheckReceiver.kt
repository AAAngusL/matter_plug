package app.matterplug.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.matterplug.alarm.runAsync
import app.matterplug.cellular.CellularRunner

/**
 * Receives Battery Guard's alarm: its check, then Cellular Check's run (which does nothing while it is off). One chain
 * of exact alarms for both features while Battery Guard is on; see CellularScheduler.
 */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context, "Guard", "check") {
        GuardRunner.onAlarm(it)
        CellularRunner.run(it)
    }
}
