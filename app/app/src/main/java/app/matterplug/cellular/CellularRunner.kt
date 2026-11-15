package app.matterplug.cellular

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.matterplug.alarm.ExactAlarm
import app.matterplug.alarm.runAsync
import app.matterplug.cellular.logic.CellularAlertPolicy
import app.matterplug.cellular.logic.CellularLog
import app.matterplug.cellular.logic.CellularReport
import app.matterplug.cellular.logic.CellularSummary
import app.matterplug.cellular.logic.CellularVerdict
import app.matterplug.cellular.logic.Layer
import app.matterplug.cellular.logic.LayerResult
import app.matterplug.cellular.logic.LoopbackJudge
import app.matterplug.cellular.logic.LoopbackOutcome
import app.matterplug.cellular.logic.LoopbackPlan
import app.matterplug.cellular.logic.LoopbackTrigger
import app.matterplug.guard.BatteryReader
import app.matterplug.guard.GuardNotifier
import app.matterplug.guard.GuardStore
import app.matterplug.guard.HttpAlerter
import app.matterplug.guard.logic.AlertEvent
import app.matterplug.guard.logic.AlertKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID

/**
 * Cellular Check's own alarm (request code 2). Used only while Battery Guard is off: while it is on, the cellular run
 * rides on Battery Guard's alarm (CheckReceiver), so the app never keeps two exact-alarm chains, which together would
 * exceed what Android allows an idle phone (about one exact alarm per app every 9 minutes).
 */
object CellularScheduler {
    val alarm = ExactAlarm(requestCode = 2, receiver = CellularReceiver::class.java, tag = "Cellular")
}

/** Receives Cellular Check's own alarm. */
class CellularReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context, "Cellular", "run") { CellularRunner.run(it) }
}

/**
 * One cellular run: the passive layers, then a loopback when the plan (or Run now) asks for one. The store work happens
 * under the lock; the HTTP delivery of an alert happens after it, within a budget that suits the caller.
 */
object CellularRunner {
    private const val TAG = "Cellular"
    private const val RETRY_MS = 5 * 60_000L
    /** From an alarm receiver, as Battery Guard's checks (its B11); the loopback service can wait longer. */
    private const val RECEIVER_BUDGET_MS = 5_000L
    private const val SERVICE_BUDGET_MS = 15_000L
    private val mutex = Mutex()

    suspend fun run(ctx: Context) {
        val alert = mutex.withLock { runLocked(ctx) }
        alert?.let { deliver(ctx, it, RECEIVER_BUDGET_MS) }
    }

    private suspend fun runLocked(ctx: Context): AlertEvent? {
        val store = CellularStore(ctx)
        val settings = try { store.readSettings() } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "settings unreadable, retrying in 5 min", e)
            CellularScheduler.alarm.scheduleIn(ctx, RETRY_MS)
            return null
        }
        if (!settings.enabled) {
            Log.i(TAG, "run skipped: Cellular Check is off")
            return null
        }
        val guard = try { GuardStore(ctx).readSettings() } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val intervalMin = guard?.intervalMin ?: 15
        // While Battery Guard is on, its alarm brings the next run; its next check time is then the next run time.
        val nextAt = if (guard?.enabled == true) {
            try { GuardStore(ctx).status.first().nextCheckAt } catch (e: Exception) { if (e is CancellationException) throw e; null }
        } else {
            try { CellularScheduler.alarm.scheduleIn(ctx, intervalMin * 60_000L) } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "next run not scheduled", e)
                null
            }
        }

        val now = System.currentTimeMillis()
        val results = CellularVerdict.passive(CellularReader.read(ctx)).toMutableMap()
        val failed = CellularVerdict.firstFailed(results)
        var state = LoopbackPlan.afterPassive(LoopbackPlan.clearStale(store.readState(), now), failed)
        val trigger = LoopbackPlan.next(state, failed, now, ZoneId.systemDefault(), settings.smsTestTime, store.manualRequested())
        if (trigger == LoopbackTrigger.Manual) store.clearManual()       // only now: a request made while one was waiting is kept
        if (trigger != null) {
            // Not failed loopbacks: the check cannot send. Shown on the SMS line, not counted towards the alert.
            val cannot = when {
                !settings.ownNumberValid -> "own number not set"
                !LoopbackSender.canSend(ctx) -> "SMS permission missing"
                else -> null
            }
            if (cannot != null) {
                results[Layer.Sms] = LayerResult.Fail(cannot)
            } else {
                val token = UUID.randomUUID().toString().filter { it.isLetterOrDigit() }.take(6)
                val started = LoopbackPlan.started(state, trigger, failed, now)
                store.writeState(started)
                if (LoopbackService.start(ctx, trigger, settings.ownNumber, token)) {
                    state = started
                    store.recordSent(now)
                    Log.i(TAG, "loopback started: trigger=${trigger.wire}")
                } else {
                    // Undone, so the next run tries again; shown, so the user can see the test is not running.
                    state = LoopbackPlan.released(started)
                    results[Layer.Sms] = LayerResult.Fail("test SMS could not start")
                }
            }
        }
        store.writeState(state)
        Log.i(TAG, CellularLog.line(results) + " next_in_min=$intervalMin")
        store.recordRun(now, results, nextAt)
        return alertStep(ctx, store, state.down, now)                   // reminders while down
    }

    /**
     * Called by LoopbackService when the loopback is over. A null [outcome] means the test could not run (an app error,
     * or the service's time limit): it is released without counting, because it says nothing about SMS.
     */
    suspend fun finishLoopback(ctx: Context, trigger: LoopbackTrigger, outcome: LoopbackOutcome?) {
        val alert = mutex.withLock { finishLocked(ctx, trigger, outcome) }
        alert?.let { deliver(ctx, it, SERVICE_BUDGET_MS) }
        if (CellularStore(ctx).manualRequested()) startSoon(ctx)      // Run now was tapped while this one was waiting
    }

    private suspend fun finishLocked(ctx: Context, trigger: LoopbackTrigger, outcome: LoopbackOutcome?): AlertEvent? {
        val store = CellularStore(ctx)
        if (outcome == null) {
            store.writeState(LoopbackPlan.released(store.readState()))
            store.recordLayer(Layer.Sms, LayerResult.Fail("${trigger.wire}: test SMS could not run"))
            Log.w(TAG, "loopback could not run: released, not counted")
            return null
        }
        val result = LoopbackJudge.result(trigger, outcome)
        val state = LoopbackPlan.finished(store.readState(), trigger, LoopbackJudge.passed(outcome), System.currentTimeMillis())
        store.writeState(state)
        store.recordLayer(Layer.Sms, result)
        Log.i(TAG, CellularLog.line(mapOf(Layer.Sms to result)) + " failed_in_a_row=${state.failedInARow} down=${state.down}")
        // Switched off while this one was waiting: switching off ended the alert, so none is started or recovered now.
        if (!store.readSettings().enabled) return null
        return alertStep(ctx, store, state.down, System.currentTimeMillis())     // started or recovered
    }

    /**
     * Moves the cellular_down alert on (CellularAlertPolicy) and posts the phone notification. Returns the event to
     * deliver over HTTP after the lock is released, or null when there is nothing to send.
     */
    private suspend fun alertStep(ctx: Context, store: CellularStore, down: Boolean, now: Long): AlertEvent? {
        val (memo, phase) = CellularAlertPolicy.step(store.readAlert(), down, now)
        store.writeAlert(memo)
        if (phase == null) return null
        val summary = summaryOf(store)
        val battery = try { BatteryReader.read(ctx) } catch (e: Exception) { null }
        val event = AlertEvent(AlertKind.CellularDown, phase, battery?.level ?: 0, battery?.pluggedIn ?: false, null, now,
            cellular = summary)
        Log.i(TAG, "alert: kind=${event.kind.wire} phase=${phase.wire} failed_layer=${summary.failedLayer?.wire}")
        GuardNotifier.post(ctx, listOf(event))
        return event
    }

    /** Through Battery Guard's queue and alert address; needs "Send alerts", not Battery Guard on. */
    private suspend fun deliver(ctx: Context, event: AlertEvent, budgetMs: Long) =
        HttpAlerter.deliver(ctx, GuardStore(ctx), listOf(event), status = null, budgetMs = budgetMs, fromGuard = false)

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

    /**
     * A run in 1 s through Cellular Check's own alarm: switching it on, Run now, or Battery Guard switching off (its
     * alarm stops bringing cellular runs, so the own chain takes over from there).
     */
    fun startSoon(ctx: Context) {
        CellularScheduler.alarm.scheduleIn(ctx, 1_000)
    }

    fun stop(ctx: Context) {
        CellularScheduler.alarm.cancel(ctx)
    }
}
