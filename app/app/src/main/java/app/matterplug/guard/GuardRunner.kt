package app.matterplug.guard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.matterplug.data.PlugRepository
import app.matterplug.guard.logic.AlertEvent
import app.matterplug.heartbeat.HeartbeatRunner
import app.matterplug.guard.logic.AlertKind
import app.matterplug.guard.logic.AlertPhase
import app.matterplug.guard.logic.AlertPolicy
import app.matterplug.guard.logic.BatteryStatus
import app.matterplug.guard.logic.Power
import app.matterplug.guard.logic.CheckDecision
import app.matterplug.guard.logic.CheckEvent
import app.matterplug.guard.logic.CheckLog
import app.matterplug.guard.logic.GuardAction
import app.matterplug.guard.logic.GuardPolicy
import app.matterplug.guard.logic.GuardSettings
import app.matterplug.matter.PlugCommander
import app.matterplug.matter.SwitchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The check. [onAlarm] reads, reschedules, records and logs the decision, and starts [ActionService] only when a switch
 * is needed; a check with action Nothing starts no service and touches no Matter class (B3, B11). [work] runs inside
 * the service, does the switch and the power wait. Both end in [finish] (alert policy, notifications, HTTP delivery and the status report).
 */
object GuardRunner {
    private const val TAG = "Guard"

    /** Used only when the settings could not be read at all, so the chain of alarms does not end. */
    private const val RETRY_MS = 5 * 60_000L

    /** One run at a time. `work` takes the same lock. */
    private val mutex = Mutex()

    /** From CheckReceiver, inside goAsync. Never throws (except cancellation). */
    suspend fun onAlarm(ctx: Context) {
        mutex.withLock { runCheck(ctx) }
    }

    /** From ActionService, inside withTimeout(120_000). Never throws (except cancellation); always reschedules. */
    suspend fun work(ctx: Context) {
        mutex.withLock { runWork(ctx) }
    }

    /**
     * The settings when Battery Guard is on, else null. When they cannot be read it is unknown whether it is on, so the
     * chain of alarms is kept alive with a retry; a later run that reads "off" ends it.
     */
    private suspend fun enabledSettingsOrNull(ctx: Context, store: GuardStore, what: String): GuardSettings? {
        val settings = try {
            store.readSettings()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "$what: settings unreadable, retrying in 5 min", e)
            try { CheckScheduler.scheduleIn(ctx, RETRY_MS) } catch (e2: Exception) { Log.e(TAG, "retry not scheduled", e2) }
            return null
        }
        if (!settings.enabled) {
            Log.i(TAG, "$what skipped: Battery Guard is off")
            return null
        }
        return settings
    }

    private suspend fun runCheck(ctx: Context) {
        val store = GuardStore(ctx)

        val settings = enabledSettingsOrNull(ctx, store, "check") ?: return

        var nextAt: Long? = null
        try {
            val reading = try { BatteryReader.read(ctx) } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "battery read failed", e)
                null
            }
            val plugged = reading?.pluggedIn
            val power = reading?.power
            val intervalMin = settings.intervalMin

            // B4: the next alarm first, so nothing below can end the chain.
            nextAt = try {
                CheckScheduler.scheduleIn(ctx, intervalMin * 60_000L)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "next alarm not scheduled", e)
                noteError(store, "next check not scheduled")
                null
            }

            val now = System.currentTimeMillis()
            try {
                store.recordCheck(now, reading?.level, power, nextAt)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "recordCheck failed", e)
            }

            val fault = degradationReason(ctx, reading)
            val action = if (reading == null) GuardAction.Nothing
            else GuardPolicy.decide(reading.level, reading.pluggedIn, settings)

            if (reading == null) noteError(store, CheckLog.FAULT_BATTERY_UNREADABLE)

            Log.i(TAG, CheckLog.line(reading?.level, plugged, action, fault, intervalMin, nextAt))

            // PLUG-WATCH.md section 4: with no switch to make, a read-only check that the charger plug still answers.
            val plugCheck: PlugStep.PlugCheck? = if (action == GuardAction.Nothing && reading != null) PlugStep.readPlug(ctx, store) else null
            val plugReached: Boolean? = (plugCheck as? PlugStep.PlugCheck.Read)?.reached
            val noPlug = plugCheck == PlugStep.PlugCheck.None

            var serviceFailed = false
            if (action != GuardAction.Nothing) {
                if (ActionService.start(ctx)) return      // the service does the rest (work, finish), including the next alarm
                // ActionService.start already wrote recordRunError("service could not start")
                serviceFailed = true
                Log.w(TAG, CheckLog.line(reading?.level, plugged, action, CheckDecision.FAULT_SERVICE, intervalMin, nextAt))
            }
            val events = CheckDecision.alarmEvents(action, reading != null, fault, serviceFailed, plugReached, noPlug)
            val clean = fault == null && !serviceFailed && nextAt != null
            val queueEmpty = store.readQueue().isEmpty()
            if (CheckDecision.nothingToDo(settings, store.readAlertState(), queueEmpty, events)) {
                if (clean) noteError(store, null)         // rule 1: no service, no Matter, no notification work
                return
            }
            finish(ctx, settings, reading, events, budgetMs = 5_000)      // B11
            if (clean) noteError(store, null)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "check failed", e)
            noteError(store, "check failed")
            if (nextAt == null) {      // failed before the alarm was set: set it now so the chain continues
                try { CheckScheduler.scheduleIn(ctx, RETRY_MS) } catch (e2: Exception) { Log.e(TAG, "retry not scheduled", e2) }
            }
        }
    }

    private suspend fun runWork(ctx: Context) {
        val store = GuardStore(ctx)

        val settings = enabledSettingsOrNull(ctx, store, "work") ?: return

        var reading: BatteryReading? = null
        var finalReading: BatteryReading? = null
        var action = GuardAction.Nothing
        var outcome = "none"
        try {
            val r: BatteryReading? = PlugStep.safeRead(ctx)       // B5: the service reads for itself
            reading = r
            // The service is running: proof that it can start (RECOVERY-SIGNALS.md 2.2).
            val events = mutableListOf<CheckEvent>(CheckEvent.ServiceStarted)
            if (r == null) {
                outcome = "battery unreadable"
                noteError(store, CheckLog.FAULT_BATTERY_UNREADABLE)
            } else {
                val decided = GuardPolicy.decide(r.level, r.pluggedIn, settings)
                action = decided
                outcome = when (decided) {
                    GuardAction.Nothing -> {
                        events += CheckEvent.NoSwitchNeeded
                        when (val c = PlugStep.readPlug(ctx, store)) {      // PLUG-WATCH.md section 4: also when the service ran
                            PlugStep.PlugCheck.None -> events += CheckEvent.NoPlug
                            PlugStep.PlugCheck.Unknown -> {}
                            is PlugStep.PlugCheck.Read -> events += CheckEvent.PlugRead(c.reached)
                        }
                        "no switch needed"
                    }
                    GuardAction.TurnOn, GuardAction.TurnOff -> PlugStep.doSwitch(ctx, store, decided == GuardAction.TurnOn, r.level, events)
                }
            }
            val fault = degradationReason(ctx, r)
            if (fault != null) events += CheckEvent.Degraded(fault)
            // Read AFTER the power wait, so a PowerWait(false) is not followed by a plugged-in state in the same call (G1 note 8).
            val finalR: BatteryReading? = PlugStep.safeRead(ctx) ?: r
            finalReading = finalR
            finish(ctx, settings, finalR, events, budgetMs = 60_000)
            if (fault == null) noteError(store, null)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "work failed", e)
            outcome = "work failed"
            noteError(store, "check failed")
        } finally {
            // Runs on cancellation (the 120 s limit) too, so the chain of alarms goes on.
            withContext(NonCancellable) { rescheduleAfterWork(ctx, store, settings, reading, finalReading, action, outcome) }
        }
    }

    /** "battery unreadable" when there is no reading, else "exact alarms not allowed" when they are not allowed, else null. */
    private fun degradationReason(ctx: Context, reading: BatteryReading?): String? {
        val exactAllowed = try { CheckScheduler.canScheduleExact(ctx) } catch (e: Exception) { false }
        return CheckLog.degradationReason(reading != null, exactAllowed)
    }

    /**
     * Alert policy, stored state, notifications (always), then HTTP delivery and the status report within [budgetMs]
     * (5 s from onAlarm, B11; 60 s from the service).
     */
    private suspend fun finish(ctx: Context, settings: GuardSettings, reading: BatteryReading?, events: List<CheckEvent>, budgetMs: Long) {
        val store = GuardStore(ctx)
        val last = if (reading == null) store.status.first() else null
        val level = reading?.level ?: last?.lastLevel ?: 0
        // Unread battery: the last plugged-in state, but the status is not known now (ALERT-API.md section 3).
        val power = reading?.power ?: Power(last?.lastPlugged ?: false, BatteryStatus.Unknown)
        val result = AlertPolicy.apply(store.readAlertState(), events, level, power, System.currentTimeMillis())
        store.writeAlertState(result.state)
        for (e in result.events) Log.i(TAG, "alert: kind=${e.kind.wire} phase=${e.phase.wire} battery=${e.battery} plugged=${e.power.pluggedIn} status=${e.power.status.wire}")
        GuardNotifier.post(ctx, result.events)                                   // always
        // B12: the status report is built here and sent once by deliver (never queued). It needs statusEnabled, not alertsEnabled.
        val status: AlertEvent? = if (settings.statusEnabled) {
            AlertEvent(AlertKind.Status, AlertPhase.Status, level, power, null, System.currentTimeMillis(),
                nextCheckInMin = settings.intervalMin)
        } else null
        val firstPush = HttpAlerter.deliver(ctx, store, result.events, status, budgetMs)         // 5.6: never throws, stays inside budgetMs
        HeartbeatRunner.afterCheck(ctx, firstPush)      // Battery Guard's check carries the console heartbeat when it is on
    }

    /** The interval follows the plugged-in state read AFTER the switch (5.5). Never throws except for cancellation (not possible in NonCancellable). */
    private suspend fun rescheduleAfterWork(
        ctx: Context, store: GuardStore, settings: GuardSettings,
        before: BatteryReading?, finalReading: BatteryReading?, action: GuardAction, outcome: String,
    ) {
        // The final reading of the run when there is one (taken after the power wait), else read again.
        val after: BatteryReading? = finalReading ?: PlugStep.safeRead(ctx) ?: before
        val intervalMin = settings.intervalMin
        val nextAt: Long? = try {
            CheckScheduler.scheduleIn(ctx, intervalMin * 60_000L)
        } catch (e: Exception) {
            Log.e(TAG, "next alarm not scheduled after work", e)
            try { CheckScheduler.scheduleIn(ctx, RETRY_MS) } catch (e2: Exception) { Log.e(TAG, "retry not scheduled", e2) }
            noteError(store, "next check not scheduled")
            null
        }
        try {
            store.recordCheck(System.currentTimeMillis(), after?.level, after?.power, nextAt)
        } catch (e: Exception) {
            Log.e(TAG, "recordCheck failed", e)
        }
        val fault = degradationReason(ctx, after)
        Log.i(TAG, "work: " + CheckLog.line(after?.level, after?.pluggedIn, action, fault, intervalMin, nextAt) + " outcome=$outcome")
    }

    private suspend fun noteError(store: GuardStore, text: String?) {
        try {
            store.recordRunError(text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "recordRunError failed", e)
        }
    }
}
