package app.matterplug.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.matterplug.alarm.runAsync

/** Receives Battery Guard's alarm: the features' steps, then Battery Guard's check (see [CheckChain]). */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context, "Guard", "check") {
        CheckChain.run(it, withGuard = true)
    }
}
