package app.matterplug.checks

import android.content.Context
import android.util.Log
import app.matterplug.cellular.CellularRunner
import app.matterplug.fixedip.IpRunner
import app.matterplug.guard.GuardRunner
import kotlinx.coroutines.CancellationException

/**
 * What runs at every check of the app's one alarm chain: each feature's step, then Battery Guard's own check when [run] is told it is on
 * (its alarm is the chain; while it is off, Cellular Check's alarm is). A step that fails is logged and never costs the others their turn.
 * The cellular step goes first so the check's status report carries this check's signal reading, not the previous one (SIGNAL-STRENGTH.md 6).
 * A new feature that reads at every check is one more line in [steps].
 */
object CheckChain {
    private const val TAG = "Guard"

    private class Step(val name: String, val run: suspend (Context) -> Unit)

    private val steps = listOf(
        Step("cellular run") { ctx -> ConsoleHeartbeat.afterCellularRun(ctx, CellularRunner.run(ctx)) },
        Step("fixed ip read", IpRunner::readAndRestartWatch),
    )

    suspend fun run(ctx: Context, withGuard: Boolean) {
        for (step in steps) {
            try {
                step.run(ctx)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "${step.name} failed; the check goes on", e)
            }
        }
        if (withGuard) GuardRunner.onAlarm(ctx, startSwitchService = ActionService::start)?.let { ConsoleHeartbeat.afterCheck(ctx, it) }
    }
}
