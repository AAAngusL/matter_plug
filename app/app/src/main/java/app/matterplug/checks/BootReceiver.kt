package app.matterplug.checks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.matterplug.alarm.runAsync
import app.matterplug.cellular.CellularScheduler
import app.matterplug.cellular.CellularStore
import app.matterplug.fixedip.IpStore
import app.matterplug.fixedip.IpWatch
import app.matterplug.guard.CheckScheduler
import app.matterplug.guard.FlushJobService
import app.matterplug.guard.GuardStore

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

        runAsync(context, TAG, "boot handling") { app ->
            val store = GuardStore(app)
            val enabled = store.readSettings().enabled
            val waiting = store.readQueue().isNotEmpty()
            Log.i(TAG, "boot: action=$action enabled=$enabled waiting=$waiting")
            if (enabled) CheckScheduler.scheduleIn(app, FIRST_CHECK_MS)
            if (waiting) FlushJobService.schedule(app)
            // Cellular Check has its own alarm chain; restart it the same way.
            val cellular = CellularStore(app).readSettings().enabled
            Log.i(TAG, "boot: cellular=$cellular")
            if (cellular) CellularScheduler.alarm.scheduleIn(app, FIRST_CHECK_MS)
            // Fixed IP: its service holds the Wi-Fi callback and the boot grace, so it starts again after a restart or an update.
            val fixedIp = IpStore(app).readSettings().enabled
            Log.i(TAG, "boot: fixedIp=$fixedIp")
            if (fixedIp) IpWatch.enable(app)
        }
    }

    private companion object {
        const val TAG = "Guard"
        const val FIRST_CHECK_MS = 5_000L
        /** AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31); the string is used so minSdk 28 needs no check. */
        const val ACTION_EXACT_ALARM_STATE = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
