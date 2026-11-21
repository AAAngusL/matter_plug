package app.matterplug.fixedip

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.matterplug.fixedip.logic.IpAlertPolicy
import app.matterplug.fixedip.logic.IpReport
import app.matterplug.fixedip.logic.IpState
import app.matterplug.fixedip.logic.IpSummary
import app.matterplug.fixedip.logic.IpVerdict
import app.matterplug.fixedip.logic.SmsOutcome
import app.matterplug.fixedip.logic.SmsResult
import app.matterplug.guard.BatteryReader
import app.matterplug.guard.FlushJobService
import app.matterplug.guard.GuardNotifier
import app.matterplug.guard.GuardStore
import app.matterplug.guard.HttpAlerter
import app.matterplug.guard.logic.AlertEvent
import app.matterplug.guard.logic.AlertKind
import app.matterplug.guard.logic.AlertPhase
import app.matterplug.guard.logic.BatteryStatus
import app.matterplug.guard.logic.CancelledBy
import app.matterplug.guard.logic.Power
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One Fixed IP read: reads the Wi-Fi, judges it, moves the alert on and sends what is due (docs/FIXED-IP.md sections 7 and 8).
 * The SMS goes first and is not held up by the HTTP request, which for a lost Wi-Fi can only wait in the waiting list.
 */
object IpRunner {
    private const val TAG = "FixedIp"
    private val UNREAD_POWER = Power(false, BatteryStatus.Unknown)
    private const val SERVICE_BUDGET_MS = 15_000L
    private val mutex = Mutex()

    /** What one read leaves to do after the lock: the HTTP request, and when to read again (the end of the 1-minute wait). */
    private data class Outcome(val event: AlertEvent?, val nextAtMs: Long?)

    /** Returns the wall-clock time the wait for a lost Wi-Fi ends, so the caller reads again then; null when nothing is waiting. */
    suspend fun evaluate(ctx: Context): Long? {
        val out = mutex.withLock { evaluateLocked(ctx) }
        out.event?.let { HttpAlerter.deliver(ctx, GuardStore(ctx), listOf(it), status = null, budgetMs = SERVICE_BUDGET_MS,
            cellular = HttpAlerter.SenderSwitches(alertsOn = true, statusOn = false)) }
        return out.nextAtMs
    }

    private suspend fun evaluateLocked(ctx: Context): Outcome {
        val store = IpStore(ctx)
        val settings = try { store.readSettings() } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "settings unreadable", e)
            return Outcome(null, null)
        }
        val pinned = settings.pinned
        if (!settings.enabled || pinned == null) return Outcome(null, null)

        val reading = IpReader.read(ctx)
        val now = System.currentTimeMillis()
        val bootAt = now - SystemClock.elapsedRealtime()
        val lostNow = !reading.wifi || reading.address == null
        val since = store.readNoWifiSince()
        val newSince = if (lostNow) (since ?: now) else null
        if (newSince != since) store.writeNoWifiSince(newSince)

        val state = IpVerdict.judge(pinned, reading, newSince, bootAt, now)
        val memo = store.readAlert()
        val (newMemo, phase) = IpAlertPolicy.step(memo, state, now)
        store.writeAlert(newMemo)
        val nextAt = (state as? IpState.NoWifi)?.takeIf { !it.due }?.dueAtMs
        Log.i(TAG, "read: state=${state.javaClass.simpleName} phase=${phase?.wire} open=${newMemo != null}")
        if (phase == null) return Outcome(null, nextAt)

        // The reason is the open alert's: a Recovered step has no memo left, so it uses the one just closed.
        val reason = (newMemo ?: memo)!!.reason
        val base = IpSummary(pinned.address, reading.address, pinned.ssid, reason, SmsOutcome.Failed)
        // The battery is read first: the SMS carries it, and the SMS and the request's message say the same thing.
        val battery = try { BatteryReader.read(ctx) } catch (e: Exception) { if (e is CancellationException) throw e; null }
        val sms = if (phase == AlertPhase.Reminder) (store.readLastSms()?.outcome ?: SmsOutcome.Failed)
        else sendSms(ctx, store, settings.recipients, IpReport.text(phase, base, battery?.level ?: 0), now)
        val event = AlertEvent(AlertKind.IpDrift, phase, battery?.level ?: 0, battery?.power ?: UNREAD_POWER, null, now, ip = base.copy(sms = sms))
        GuardNotifier.post(ctx, listOf(event))
        return Outcome(event, nextAt)
    }

    /** One SMS to every number, recorded for the screen. The reminder never sends one (section 7). */
    private suspend fun sendSms(ctx: Context, store: IpStore, numbers: List<String>, text: String, now: Long): SmsOutcome {
        val sent = IpSms.send(ctx, numbers, text)
        val outcome = SmsResult.outcome(sent.accepted)
        store.recordSms(now, outcome, if (outcome == SmsOutcome.Failed) sent.failure else null)
        Log.i(TAG, "sms: numbers=${numbers.size} outcome=${outcome.wire} failure=${sent.failure}")
        return outcome
    }

    /**
     * The switch was turned off, or the network unpinned ([by]), while an alert was open: the notification goes and one cancelled is
     * queued behind the waiting requests (R4). No SMS: the person who switched it off knows. Never throws.
     */
    suspend fun closed(ctx: Context, closed: IpStore.Closed, by: CancelledBy = CancelledBy.IpOff) {
        if (!closed.wasOpen) return
        try {
            GuardNotifier.cancel(ctx, listOf(AlertKind.IpDrift))
            val battery = try { BatteryReader.read(ctx) } catch (e: Exception) { null }
            val e = AlertEvent(AlertKind.IpDrift, AlertPhase.Cancelled, battery?.level ?: 0, battery?.power ?: UNREAD_POWER, null,
                System.currentTimeMillis(), cancelledBy = by)
            GuardStore(ctx).enqueue(listOf(HttpAlerter.body(e)), guardItems = false)
            FlushJobService.schedule(ctx)
            Log.i(TAG, "alert: kind=ip_drift phase=cancelled by=${by.wire}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "cancelled not queued", e)
        }
    }

    /** The backstop the two alarm chains call: a read, and the service started again if it has died. */
    suspend fun onChain(ctx: Context) {
        val settings = IpStore(ctx).readSettings()
        if (!settings.enabled) return
        IpWatchService.start(ctx)
        evaluate(ctx)
    }
}
