package app.matterplug.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.matterplug.alarm.runAsync

/** Receives the one alarm of Battery Guard. */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context, "Guard", "check") { GuardRunner.onAlarm(it) }
}
