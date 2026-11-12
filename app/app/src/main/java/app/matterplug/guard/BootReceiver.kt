package app.matterplug.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * After a restart, an app update or a change of the exact-alarm permission (5.8): if Battery Guard is on, schedule the
 * normal check alarm 5 s from now (B3: the check then runs through CheckReceiver; no service is started here), and if
 * alerts are waiting, schedule the flush job. Before the first unlock after a reboot this is not called at all (screen lock).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_EXACT_ALARM_STATE
        ) return

        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = GuardStore(app)
                val enabled = store.readSettings().enabled
                val waiting = store.readQueue().isNotEmpty()
                Log.i(TAG, "boot: action=$action enabled=$enabled waiting=$waiting")
                if (enabled) CheckScheduler.scheduleIn(app, FIRST_CHECK_MS)
                if (waiting) FlushJobService.schedule(app)
            } catch (e: Throwable) {      // never let the receiver crash
                Log.e(TAG, "boot handling failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "Guard"
        const val FIRST_CHECK_MS = 5_000L
        /** AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31); the string is used so minSdk 28 needs no check. */
        const val ACTION_EXACT_ALARM_STATE = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
