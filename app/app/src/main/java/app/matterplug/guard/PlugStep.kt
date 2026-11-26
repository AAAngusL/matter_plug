package app.matterplug.guard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.matterplug.data.PlugRepository
import app.matterplug.guard.logic.CheckEvent
import app.matterplug.matter.PlugCommander
import app.matterplug.matter.SwitchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import app.matterplug.battery.BatteryReader

/** What a check does with the charger plug and the battery reading: read the plug, switch it, wait for power. Records what it saw in [GuardStore]. */
internal object PlugStep {
    private const val TAG = "Guard"
    private const val POWER_WAIT_MS = 30_000L
    private const val POWER_POLL_MS = 2_000L

    /**
     * PLUG-WATCH.md section 5: connect, read On/Off, release, within PlugCommander's 8-second limit, and record it for the
     * Battery Guard tab. Returns what the check saw: [CheckEvent.NoPlug] when there is no charger plug to watch,
     * [CheckEvent.PlugRead] when there is one, and null when the lookup failed, so nothing is said about the plug.
     * Never throws except for cancellation.
     */
    suspend fun readPlug(ctx: Context, store: GuardStore): CheckEvent? {
        val plug = try { PlugRepository(ctx).charger() } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "charger plug unreadable", e)
            return null
        } ?: run {
            safely("clearPlugSighting", Unit) { store.clearPlugSighting() }
            return CheckEvent.NoPlug
        }
        val t0 = SystemClock.elapsedRealtime()
        val on: Boolean? = PlugCommander(ctx).readOnOff(plug)
        Log.i(TAG, "plug read: ${on?.let { if (it) "on" else "off" } ?: "not reached"} in ${SystemClock.elapsedRealtime() - t0} ms")
        recordPlugSafely(store, on != null, on)
        return CheckEvent.PlugRead(on != null)
    }

    private suspend fun recordPlugSafely(store: GuardStore, reached: Boolean, on: Boolean?) {
        safely("recordPlug", Unit) { store.recordPlug(reached, on, System.currentTimeMillis()) }
    }

    /**
     * True as soon as the phone reports plugged in, false after [timeoutMs]. Polls every 2 s with delay(), so it can be
     * cancelled; 30 s fits inside the service's 120 s limit (connect 25 + command 10 + read-back 10 + this 30).
     */
    private suspend fun waitForPower(ctx: Context, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (BatteryReader.readOrNull(ctx)?.pluggedIn == true) return true
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) return false
            delay(minOf(POWER_POLL_MS, left))
        }
    }


    /** What a switch did: the text for the log line, and the events the alert policy is to see, in order. */
    class SwitchRun(val outcome: String, val events: List<CheckEvent>)

    /** Throws only on cancellation or an unexpected error (the caller logs it). */
    suspend fun doSwitch(ctx: Context, store: GuardStore, on: Boolean, level: Int): SwitchRun {
        val events = mutableListOf<CheckEvent>()
        val plug = PlugRepository(ctx).charger()      // Battery Guard acts on the charger plug
        val result: SwitchResult = if (plug == null) SwitchResult.NotReached("no plug") else PlugCommander(ctx).switchTo(plug, on)
        val what = if (on) "on" else "off"
        return when (result) {
            is SwitchResult.Confirmed -> {
                store.recordSwitch(System.currentTimeMillis(), on, level)
                recordPlugSafely(store, reached = true, on = on)
                Log.i(TAG, "switch $what confirmed at $level%")
                events += CheckEvent.SwitchConfirmed(on)
                if (on) {
                    val powered = waitForPower(ctx, POWER_WAIT_MS)
                    Log.i(TAG, "power wait: plugged=$powered")
                    events += CheckEvent.PowerWait(powered)
                }
                SwitchRun("switch $what Confirmed", events)
            }
            is SwitchResult.NotReached -> {
                Log.w(TAG, "switch $what failed: ${result.reason}")
                recordPlugSafely(store, reached = false, on = null)
                events += CheckEvent.SwitchFailed(on)
                SwitchRun("switch $what NotReached(${result.reason})", events)
            }
            is SwitchResult.Unconfirmed -> {
                Log.w(TAG, "switch $what unconfirmed: read-back differs")
                recordPlugSafely(store, reached = true, on = null)      // the plug accepted the command, so it was reached; its state is unknown
                events += CheckEvent.SwitchFailed(on)
                SwitchRun("switch $what Unconfirmed", events)
            }
        }
    }
}
