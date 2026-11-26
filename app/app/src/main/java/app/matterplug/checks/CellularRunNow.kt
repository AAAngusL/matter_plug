package app.matterplug.checks

import android.content.Context
import android.util.Log
import app.matterplug.cellular.CellularRunner
import app.matterplug.cellular.CellularStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Run cellular check now", or switching the check on: the run happens at once, not through the alarm. Android may
 * hold a one-second exact alarm back (its limit on exact alarms), and a second tap would replace the first before
 * it fired. [start]'s `withLoopback` asks for a manual test SMS; it stays asked if one is still waiting.
 */
object CellularRunNow {
    /** Outlives the screen: a run started from it finishes even if the user leaves the screen at once. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(ctx: Context, withLoopback: Boolean) {
        val app = ctx.applicationContext
        appScope.launch {
            try {
                if (withLoopback) CellularStore(app).requestManual()
                ConsoleHeartbeat.afterCellularRun(app, CellularRunner.run(app))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("Cellular", "run now failed", e)
            }
        }
    }
}
