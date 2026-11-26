package app.matterplug.checks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.matterplug.alarm.runAsync

/** Receives Cellular Check's own alarm: the features' steps, with no Battery Guard check (it is off; see [CheckChain]). */
class CellularReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context, "Cellular", "run") {
        CheckChain.run(it, withGuard = false)
    }
}
