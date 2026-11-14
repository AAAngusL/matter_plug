package app.matterplug.cellular

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.matterplug.alarm.ExactAlarm
import app.matterplug.alarm.runAsync
import app.matterplug.cellular.logic.CellularLog
import app.matterplug.cellular.logic.CellularVerdict
import app.matterplug.guard.GuardStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The alarm of Cellular Check (request code 2), at Battery Guard's Check every interval. */
object CellularScheduler {
    val alarm = ExactAlarm(requestCode = 2, receiver = CellularReceiver::class.java, tag = "Cellular")
}

/** Receives the Cellular Check alarm. */
class CellularReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context, "Cellular", "run") { CellularRunner.run(it) }
}

/** One cellular run (C2: the passive layers). Schedules the next alarm first, so nothing below can end the chain. */
object CellularRunner {
    private const val TAG = "Cellular"
    private const val RETRY_MS = 5 * 60_000L
    private val mutex = Mutex()

    suspend fun run(ctx: Context) = mutex.withLock { runLocked(ctx) }

    private suspend fun runLocked(ctx: Context) {
        val store = CellularStore(ctx)
        val settings = try { store.readSettings() } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "settings unreadable, retrying in 5 min", e)
            CellularScheduler.alarm.scheduleIn(ctx, RETRY_MS)
            return
        }
        if (!settings.enabled) {
            Log.i(TAG, "run skipped: Cellular Check is off")
            return
        }
        val intervalMin = try { GuardStore(ctx).readSettings().intervalMin } catch (e: Exception) {
            if (e is CancellationException) throw e
            15
        }
        val nextAt = try { CellularScheduler.alarm.scheduleIn(ctx, intervalMin * 60_000L) } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "next run not scheduled", e)
            null
        }
        val results = CellularVerdict.passive(CellularReader.read(ctx))
        Log.i(TAG, CellularLog.line(results) + " next_in_min=$intervalMin")
        store.recordRun(System.currentTimeMillis(), results, nextAt)
    }

    /** Switch on, or "Run cellular check now": a run in 1 s through the alarm, so the chain starts from there. */
    fun startSoon(ctx: Context) {
        CellularScheduler.alarm.scheduleIn(ctx, 1_000)
    }

    fun stop(ctx: Context) {
        CellularScheduler.alarm.cancel(ctx)
    }
}
