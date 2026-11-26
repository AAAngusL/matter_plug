package app.matterplug.checks

import android.content.Context
import android.util.Log
import app.matterplug.cellular.CellularRunner
import app.matterplug.cellular.CellularStore
import app.matterplug.guard.GuardControl
import kotlinx.coroutines.CancellationException

/** Switching Battery Guard off for the whole app: Battery Guard's own part ([GuardControl]) and the hand-over of its alarm to Cellular Check. */
object GuardSwitch {
    suspend fun off(ctx: Context) {
        GuardControl.disable(ctx)
        // Battery Guard's alarm also brought Cellular Check's runs; its own alarm takes over now.
        try {
            if (CellularStore(ctx).readSettings().enabled) CellularRunner.startSoon(ctx)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("Checks", "cellular alarm not restarted", e)
        }
    }
}
