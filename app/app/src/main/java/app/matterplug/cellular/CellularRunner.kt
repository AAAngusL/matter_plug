package app.matterplug.cellular

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.matterplug.alarm.ExactAlarm
import app.matterplug.alarm.runAsync
import app.matterplug.cellular.logic.CellularLog
import app.matterplug.cellular.logic.CellularAlertPolicy
import app.matterplug.cellular.logic.CellularReport
import app.matterplug.cellular.logic.CellularSummary
import app.matterplug.cellular.logic.CellularVerdict
import app.matterplug.guard.BatteryReader
import app.matterplug.guard.GuardNotifier
import app.matterplug.guard.HttpAlerter
import app.matterplug.guard.logic.AlertEvent
import app.matterplug.guard.logic.AlertKind
import kotlinx.coroutines.flow.first
import app.matterplug.cellular.logic.DeliveryStatus
import app.matterplug.cellular.logic.Layer
import app.matterplug.cellular.logic.LayerResult
import app.matterplug.cellular.logic.LoopbackJudge
import app.matterplug.cellular.logic.LoopbackOutcome
import app.matterplug.cellular.logic.LoopbackPlan
import app.matterplug.cellular.logic.LoopbackTrigger
import app.matterplug.cellular.logic.SentStatus
import java.time.ZoneId
import java.util.UUID
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

/**
 * One cellular run: the passive layers, then a loopback when the plan (or Run now) asks for one. Schedules the next
 * alarm first, so nothing below can end the chain.
 */
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
        val now = System.currentTimeMillis()
        val results = CellularVerdict.passive(CellularReader.read(ctx)).toMutableMap()
        val failed = CellularVerdict.firstFailed(results)
        var state = LoopbackPlan.afterPassive(LoopbackPlan.clearStale(store.readState(), now), failed)
        val trigger = LoopbackPlan.next(state, failed, now, ZoneId.systemDefault(), settings.smsTestTime, store.manualRequested())
        if (trigger == LoopbackTrigger.Manual) store.clearManual()       // only now: a request made while one was waiting is kept
        if (trigger != null) {
            if (settings.ownNumber.isEmpty() || !LoopbackSender.canSend(ctx)) {
                // Not a failed loopback: the check cannot send. Shown on the SMS line, not counted towards the alert.
                results[Layer.Sms] = LayerResult.Fail(if (settings.ownNumber.isEmpty()) "no own number" else "SMS permission missing")
            } else {
                val token = UUID.randomUUID().toString().filter { it.isLetterOrDigit() }.take(6)
                state = LoopbackPlan.started(state, trigger, failed, now)
                store.writeState(state)
                if (LoopbackService.start(ctx, trigger, settings.ownNumber, token)) {
                    store.recordSent(now)
                    Log.i(TAG, "loopback started: trigger=${trigger.wire}")
                } else {
                    state = state.copy(inProgress = false, startedAt = null)      // not counted
                }
            }
        }
        store.writeState(state)
        Log.i(TAG, CellularLog.line(results) + " next_in_min=$intervalMin")
        store.recordRun(now, results, nextAt)
        alertStep(ctx, store, state.down, now)            // reminders while down
    }

    /** Called by LoopbackService when the loopback is over. A null outcome (cut off, or an error) is recorded as not sent. */
    suspend fun finishLoopback(ctx: Context, trigger: LoopbackTrigger, outcome: LoopbackOutcome?) = mutex.withLock {
        val store = CellularStore(ctx)
        val o = outcome ?: LoopbackOutcome(SentStatus.NoAnswer, DeliveryStatus.NoReport, received = false)
        val result = LoopbackJudge.result(trigger, o)
        val state = LoopbackPlan.finished(store.readState(), trigger, LoopbackJudge.passed(o), System.currentTimeMillis())
        store.writeState(state)
        store.recordLayer(Layer.Sms, result)
        Log.i(TAG, CellularLog.line(mapOf(Layer.Sms to result)) + " failed_in_a_row=${state.failedInARow} down=${state.down}")
        alertStep(ctx, store, state.down, System.currentTimeMillis())     // started or recovered
        if (store.manualRequested()) startSoon(ctx)       // Run now was tapped while this one was waiting
    }

    /**
     * Moves the cellular_down alert on (CellularAlertPolicy) and, when it says so, posts the phone notification and
     * delivers the request through Battery Guard's queue and alert address. Needs "Send alerts", not Battery Guard on.
     */
    private suspend fun alertStep(ctx: Context, store: CellularStore, down: Boolean, now: Long) {
        val (memo, phase) = CellularAlertPolicy.step(store.readAlert(), down, now)
        store.writeAlert(memo)
        if (phase == null) return
        val summary = summaryOf(store)
        val battery = try { BatteryReader.read(ctx) } catch (e: Exception) { null }
        val event = AlertEvent(AlertKind.CellularDown, phase, battery?.level ?: 0, battery?.pluggedIn ?: false, null, now,
            cellular = summary)
        Log.i(TAG, "alert: kind=${event.kind.wire} phase=${phase.wire} failed_layer=${summary.failedLayer?.wire}")
        GuardNotifier.post(ctx, listOf(event))
        HttpAlerter.deliver(ctx, GuardStore(ctx), listOf(event), status = null, budgetMs = 15_000, fromGuard = false)
    }

    private suspend fun summaryOf(store: CellularStore): CellularSummary =
        CellularReport.summary(store.status.first().results, store.readState().lastTrigger)

    /** For Battery Guard's status report: the latest cellular results, or null while Cellular Check is off. */
    suspend fun currentSummary(ctx: Context): CellularSummary? = try {
        val store = CellularStore(ctx)
        if (store.readSettings().enabled) summaryOf(store) else null
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    /** "Run cellular check now": a run with a loopback, in 1 s. */
    suspend fun runNowWithLoopback(ctx: Context) {
        CellularStore(ctx).requestManual()
        startSoon(ctx)
    }

    /** Switch on, or "Run cellular check now": a run in 1 s through the alarm, so the chain starts from there. */
    fun startSoon(ctx: Context) {
        CellularScheduler.alarm.scheduleIn(ctx, 1_000)
    }

    fun stop(ctx: Context) {
        CellularScheduler.alarm.cancel(ctx)
    }
}
