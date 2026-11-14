package app.matterplug.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs [block] off the main thread while goAsync keeps the process alive, and never lets an error escape the receiver.
 * Used by every receiver of the app (the two alarm chains and the restart receiver).
 */
fun BroadcastReceiver.runAsync(context: Context, tag: String, what: String, block: suspend (Context) -> Unit) {
    val pending = goAsync()
    val app = context.applicationContext
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block(app)
        } catch (e: Throwable) {      // the blocks catch their own errors; this is the last line of defence
            Log.e(tag, "$what failed", e)
        } finally {
            pending.finish()
        }
    }
}
