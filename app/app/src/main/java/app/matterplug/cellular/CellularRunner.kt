package app.matterplug.cellular

import android.content.Context
import android.util.Log
import app.matterplug.alarm.ExactAlarm
import app.matterplug.cellular.logic.CellularAlertPolicy
import app.matterplug.cellular.logic.CellularLog
import app.matterplug.cellular.logic.CellularReport
import app.matterplug.cellular.logic.CellularSettings
import app.matterplug.cellular.logic.CellularSummary
import app.matterplug.cellular.logic.CellularVerdict
import app.matterplug.cellular.logic.Layer
import app.matterplug.cellular.logic.LayerResult
import app.matterplug.cellular.logic.LoopbackJudge
import app.matterplug.cellular.logic.LoopbackOutcome
import app.matterplug.cellular.logic.LoopbackPlan
import app.matterplug.cellular.logic.LoopbackTrigger
import app.matterplug.alerts.AlertChannel
import app.matterplug.alerts.Delivery
import app.matterplug.battery.BatteryReader
import app.matterplug.guard.CheckSchedule
import app.matterplug.alerts.AlertNotifier
import app.matterplug.alerts.FeatureSender
import app.matterplug.alerts.logic.AlertEvent
import app.matterplug.alerts.logic.AlertKind
import app.matterplug.alerts.logic.AlertPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID

/**
 * Cellular Check's own alarm (request code 2). Used only while Battery Guard is off: while it is on, the cellular run
 * rides on Battery Guard's alarm (`checks.CheckReceiver`), so the app never keeps two exact-alarm chains, which together would
 * exceed what Android allows an idle phone (about one exact alarm per app every 9 minutes).
 */
object CellularScheduler {
    /** The receiver in `checks` that runs the steps; named, not imported, so `cellular` stays below `checks`. */
    const val RECEIVER = "app.matterplug.checks.CellularReceiver"
    val alarm = ExactAlarm(requestCode = 2, receiverClassName = RECEIVER, tag = "Cellular")
}

/**
 * One cellular run: the passive layers, then a loopback when the plan (or Run now) asks for one. The store work happens
 * under the lock; the HTTP delivery of an alert happens after it, within a budget that suits the caller.
 */
object CellularRunner {
    private const val TAG = "Cellular"
    private const val RETRY_MS = 5 * 60_000L
    /** From an alarm receiver, as Battery Guard's checks; the loopback service can wait longer. */
    private const val RECEIVER_BUDGET_MS = 5_000L
    private const val SERVICE_BUDGET_MS = 15_000L
    private val mutex = Mutex()

    /** What a run sends after the lock is released, under Cellular Check's own switches: an alert step and its status report. */
    private data class Outgoing(val alert: AlertEvent?, val status: AlertEvent?, val sender: FeatureSender)

    /** Returns the delivery this run made, or null when it had nothing to send (the caller hands it to the console heartbeat when it is due). */
    suspend fun run(ctx: Context): Delivery? {
        val out = mutex.withLock { runLocked(ctx) } ?: return null
        if (out.alert == null && out.status == null) return null
        return Delivery(AlertChannel.deliver(ctx, out.sender, listOfNotNull(out.alert), status = out.status, budgetMs = RECEIVER_BUDGET_MS))
    }

    private suspend fun runLocked(ctx: Context): Outgoing? {
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
        val schedule = CheckSchedule.now(ctx)
        val intervalMin = schedule.intervalMin
        // While Battery Guard is on, its alarm brings the next run; its next check time is then the next run time.
        val nextAt = if (schedule.guardOn == true) {
            schedule.guardNextCheckAt
        } else {
            try { CellularScheduler.alarm.scheduleIn(ctx, intervalMin * 60_000L) } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "next run not scheduled", e)
                null
            }
        }

        val now = System.currentTimeMillis()
        val reading = CellularReader.read(ctx)
        val results = CellularVerdict.passive(reading).toMutableMap()
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
        store.recordRun(now, results, nextAt, reading.lte)
        // Reminders while down; during a test the test's end sends it instead (RECOVERY-SIGNALS.md 2.3).
        val alert = if (LoopbackPlan.reminderWaits(state)) null else alertStep(ctx, store, state.down, now)
        // Cellular Check's own status report, whether Battery Guard is on or off (draft 5): its own switch decides.
        val status = if (settings.statusEnabled) statusReport(ctx, store, intervalMin, now) else null
        return Outgoing(alert, status, senderOf(settings))
    }

    private fun senderOf(s: CellularSettings) = FeatureSender(s.alertsEnabled, s.statusEnabled)

    /** cellular_status: the same request shape as Battery Guard's status report, with this run's cellular object. */
    private suspend fun statusReport(ctx: Context, store: CellularStore, intervalMin: Int, now: Long): AlertEvent {
        return AlertChannel.event(ctx, AlertKind.CellularStatus, AlertPhase.Status, now, extra = summaryOf(store), nextCheckInMin = intervalMin)
    }

    /**
     * Called by LoopbackService when the loopback is over. A null [outcome] means the test could not run (an app error,
     * or the service's time limit): it is released without counting, because it says nothing about SMS.
     */
    suspend fun finishLoopback(ctx: Context, trigger: LoopbackTrigger, outcome: LoopbackOutcome?) {
        val alert = mutex.withLock { finishLocked(ctx, trigger, outcome) }
        if (alert != null) {
            AlertChannel.deliver(ctx, senderOf(CellularStore(ctx).readSettings()), listOf(alert), status = null, budgetMs = SERVICE_BUDGET_MS)
        }
        if (CellularStore(ctx).manualRequested()) startSoon(ctx)      // Run now was tapped while this one was waiting
    }

    private suspend fun finishLocked(ctx: Context, trigger: LoopbackTrigger, outcome: LoopbackOutcome?): AlertEvent? {
        val store = CellularStore(ctx)
        if (outcome == null) {
            store.writeState(LoopbackPlan.released(store.readState()))
            store.recordLayer(Layer.Sms, LayerResult.Fail("${trigger.wire}: test SMS could not run"))
            Log.w(TAG, "loopback could not run: released, not counted")
            // The run held its reminder back for this test: send it now, so a test that cannot run never mutes reminders.
            if (!store.readSettings().enabled) return null
            return alertStep(ctx, store, store.readState().down, System.currentTimeMillis())
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
        val (memo, phase) = CellularAlertPolicy.step(store.alert.read(), down, now)
        store.alert.write(memo)
        if (phase == null) return null
        val summary = summaryOf(store)
        val event = AlertChannel.event(ctx, AlertKind.CellularDown, phase, now, extra = summary)
        Log.i(TAG, "alert: kind=${event.kind.wire} phase=${phase.wire} failed_layer=${summary.failedLayer?.wire}")
        AlertNotifier.post(ctx, listOf(event))
        return event
    }

    /**
     * A switch closed the cellular_down alert (RECOVERY-SIGNALS.md 2.4): removes its notification and, when [by] is
     * set, queues cancelled behind the waiting requests and starts the flush job. Never throws.
     */
    suspend fun closed(ctx: Context, closed: CellularStore.Closed) {
        if (!closed.wasOpen) return
        try {
            AlertNotifier.cancel(ctx, listOf(AlertKind.CellularDown))
            val by = closed.cancelledBy ?: return
            AlertChannel.queueCancelled(ctx, AlertKind.CellularDown, by, extra = summaryOf(CellularStore(ctx)))
            Log.i(TAG, "alert: kind=cellular_down phase=cancelled by=${by.wire}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "cancelled not queued", e)
        }
    }

    private suspend fun summaryOf(store: CellularStore): CellularSummary {
        val status = store.status.first()
        return CellularReport.summary(status.results, store.readState().lastTrigger, status.signal)
    }

    /**
     * A run in 1 s through Cellular Check's own alarm, for starts in the background: Battery Guard switching off (its
     * alarm stops bringing cellular runs, so the own chain takes over), or a Run now kept while a test SMS was waiting.
     */
    fun startSoon(ctx: Context) {
        CellularScheduler.alarm.scheduleIn(ctx, 1_000)
    }

    fun stop(ctx: Context) {
        CellularScheduler.alarm.cancel(ctx)
    }
}
