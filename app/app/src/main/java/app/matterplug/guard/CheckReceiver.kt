package app.matterplug.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives the one alarm. goAsync keeps the process alive while the check runs. */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                GuardRunner.onAlarm(app)
            } catch (e: Throwable) {      // GuardRunner catches its own errors; this is the last line of defence
                Log.e("Guard", "check failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
