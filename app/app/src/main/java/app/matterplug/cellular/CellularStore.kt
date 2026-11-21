package app.matterplug.cellular

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.matterplug.cellular.logic.CellularAlert
import app.matterplug.cellular.logic.CellularAlertCodec
import app.matterplug.cellular.logic.CellularCodec
import app.matterplug.cellular.logic.CellularSettings
import app.matterplug.cellular.logic.CellularState
import app.matterplug.cellular.logic.switchOff
import app.matterplug.cellular.logic.CellularStateCodec
import app.matterplug.guard.logic.CancelledBy
import app.matterplug.guard.logic.SwitchOff
import app.matterplug.cellular.logic.SentLog
import app.matterplug.cellular.logic.SignalReading
import app.matterplug.cellular.logic.SignalReadingCodec
import app.matterplug.cellular.logic.Layer
import app.matterplug.cellular.logic.LayerResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cellularDataStore: DataStore<Preferences> by preferencesDataStore(name = "cellular")

/** What the Cellular Check screen shows besides the settings: the last run and when the next one is due. */
data class CellularStatus(
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
    val results: Map<Layer, LayerResult> = emptyMap(),
    val sentTimes: List<Long> = emptyList(),
    /** When the SMS line's result was recorded (a loopback, or "cannot send"); the passive lines use [lastRunAt]. */
    val smsAt: Long? = null,
    /** The LTE reading of the last run, as numbers (docs/SIGNAL-STRENGTH.md); null with no LTE cell. */
    val signal: SignalReading? = null,
)

/** Cellular Check's own DataStore file ("cellular"). Nothing of it is kept in "guard" or "plugs". */
class CellularStore(context: Context) {
    private val store = context.applicationContext.cellularDataStore

    val settings: Flow<CellularSettings> = store.data.map { readSettings(it) }

    val status: Flow<CellularStatus> = store.data.map { p ->
        CellularStatus(p[LAST_RUN_AT], p[NEXT_RUN_AT], CellularCodec.decode(p[RESULTS]), SentLog.decode(p[SENT_LOG]), p[SMS_AT],
            SignalReadingCodec.decode(p[SIGNAL_READING]))
    }

    suspend fun readSettings(): CellularSettings = readSettings(store.data.first())

    /**
     * Applies [transform] and writes the result, coerced, in one edit. Switching off clears the next run time.
     * The switches' effect on the alert (RECOVERY-SIGNALS.md 2.4, SwitchOff): either switch off, or Send cellular alerts
     * back on, clears it and restarts its count, so a fault is confirmed again. Returns whether an alert was open and,
     * when the server was being told about it, which switch closed it: the caller then sends cancelled.
     */
    suspend fun update(transform: (CellularSettings) -> CellularSettings): Closed {
        var closed = Closed(false, null)
        store.edit { p ->
            val old = readSettings(p)
            val new = transform(old).coerced()
            p[ENABLED] = new.enabled
            p[OWN_NUMBER] = new.ownNumber
            p[SMS_TEST_MINUTES] = new.smsTestMinutes
            p[ALERTS_ENABLED] = new.alertsEnabled
            p[STATUS_ENABLED] = new.statusEnabled
            val change = old.switchOff(new)
            if (change.reset) {
                val open = CellularAlertCodec.decode(p[ALERT]) != null
                closed = Closed(open, if (open) change.cancelledBy else null)
                p.remove(ALERT)
                if (new.enabled) {
                    val s = CellularStateCodec.decode(p[STATE])
                    p[STATE] = CellularStateCodec.encode(s.copy(failedInARow = 0, confirmedFault = null))
                }
            }
            if (old.enabled && !new.enabled) {
                // Like Battery Guard (BATTERY-GUARD.md 5.2): switching off forgets the loopback state.
                p.remove(NEXT_RUN_AT)
                p.remove(STATE)
            }
        }
        return closed
    }

    /** [wasOpen]: the alert was on and is now cleared. [cancelledBy]: send cancelled for it. */
    data class Closed(val wasOpen: Boolean, val cancelledBy: CancelledBy?)

    /** Keeps the results of layers that did not run this time (the SMS layer, when no loopback was due). A null next time removes it. */
    suspend fun recordRun(atMs: Long, results: Map<Layer, LayerResult>, nextAtMs: Long?, signal: SignalReading?) {
        store.edit { p ->
            if (signal == null) p.remove(SIGNAL_READING) else p[SIGNAL_READING] = SignalReadingCodec.encode(signal)
            p[LAST_RUN_AT] = atMs
            p[RESULTS] = CellularCodec.encode(CellularCodec.decode(p[RESULTS]) + results)
            if (results.containsKey(Layer.Sms)) p[SMS_AT] = atMs
            if (nextAtMs == null) p.remove(NEXT_RUN_AT) else p[NEXT_RUN_AT] = nextAtMs
        }
    }

    suspend fun readState(): CellularState = CellularStateCodec.decode(store.data.first()[STATE])
    suspend fun writeState(s: CellularState) { store.edit { it[STATE] = CellularStateCodec.encode(s) } }

    /** "Run cellular check now" asks for a loopback whatever the state; it stays asked until a manual loopback starts. */
    suspend fun requestManual() { store.edit { it[MANUAL] = true } }
    suspend fun manualRequested(): Boolean = store.data.first()[MANUAL] ?: false
    suspend fun clearManual() { store.edit { it.remove(MANUAL) } }

    /** Records one layer's result without touching the run time (used when a loopback finishes). */
    suspend fun recordLayer(layer: Layer, result: LayerResult) {
        store.edit { p ->
            p[RESULTS] = CellularCodec.encode(CellularCodec.decode(p[RESULTS]) + (layer to result))
            if (layer == Layer.Sms) p[SMS_AT] = System.currentTimeMillis()
        }
    }

    suspend fun readAlert(): CellularAlert? = CellularAlertCodec.decode(store.data.first()[ALERT])
    suspend fun writeAlert(a: CellularAlert?) { store.edit { if (a == null) it.remove(ALERT) else it[ALERT] = CellularAlertCodec.encode(a) } }

    suspend fun recordSent(at: Long) {
        store.edit { p -> p[SENT_LOG] = SentLog.encode(SentLog.add(SentLog.decode(p[SENT_LOG]), at)) }
    }

    private fun readSettings(p: Preferences): CellularSettings = CellularSettings(
        enabled = p[ENABLED] ?: false,
        ownNumber = p[OWN_NUMBER] ?: "",
        smsTestMinutes = p[SMS_TEST_MINUTES] ?: CellularSettings.DEFAULT_TEST_MINUTES,
        alertsEnabled = p[ALERTS_ENABLED] ?: false,
        statusEnabled = p[STATUS_ENABLED] ?: false,
    ).coerced()

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val OWN_NUMBER = stringPreferencesKey("own_number")
        val SMS_TEST_MINUTES = intPreferencesKey("sms_test_minutes")
        val ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")
        val STATUS_ENABLED = booleanPreferencesKey("status_enabled")
        val LAST_RUN_AT = longPreferencesKey("last_run_at")
        val NEXT_RUN_AT = longPreferencesKey("next_run_at")
        val RESULTS = stringPreferencesKey("results")
        val STATE = stringPreferencesKey("loopback_state")
        val MANUAL = booleanPreferencesKey("manual_requested")
        val SENT_LOG = stringPreferencesKey("sent_log")
        val ALERT = stringPreferencesKey("alert")
        val SMS_AT = longPreferencesKey("sms_at")
        val SIGNAL_READING = stringPreferencesKey("signal_reading")
    }
}
