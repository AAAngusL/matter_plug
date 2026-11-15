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
import app.matterplug.cellular.logic.CellularCodec
import app.matterplug.cellular.logic.CellularSettings
import app.matterplug.cellular.logic.CellularState
import app.matterplug.cellular.logic.CellularStateCodec
import app.matterplug.cellular.logic.SentLog
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
)

/** Cellular Check's own DataStore file ("cellular"). Nothing of it is kept in "guard" or "plugs". */
class CellularStore(context: Context) {
    private val store = context.applicationContext.cellularDataStore

    val settings: Flow<CellularSettings> = store.data.map { readSettings(it) }

    val status: Flow<CellularStatus> = store.data.map { p ->
        CellularStatus(p[LAST_RUN_AT], p[NEXT_RUN_AT], CellularCodec.decode(p[RESULTS]), SentLog.decode(p[SENT_LOG]))
    }

    suspend fun readSettings(): CellularSettings = readSettings(store.data.first())

    /** Applies [transform] and writes the result, coerced, in one edit. Switching off clears the next run time. */
    suspend fun update(transform: (CellularSettings) -> CellularSettings) {
        store.edit { p ->
            val old = readSettings(p)
            val new = transform(old).coerced()
            p[ENABLED] = new.enabled
            p[OWN_NUMBER] = new.ownNumber
            p[SMS_TEST_MINUTES] = new.smsTestMinutes
            if (old.enabled && !new.enabled) p.remove(NEXT_RUN_AT)
        }
    }

    /** Keeps the results of layers that did not run this time (the SMS layer, when no loopback was due). A null next time removes it. */
    suspend fun recordRun(atMs: Long, results: Map<Layer, LayerResult>, nextAtMs: Long?) {
        store.edit { p ->
            p[LAST_RUN_AT] = atMs
            p[RESULTS] = CellularCodec.encode(CellularCodec.decode(p[RESULTS]) + results)
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
        store.edit { p -> p[RESULTS] = CellularCodec.encode(CellularCodec.decode(p[RESULTS]) + (layer to result)) }
    }

    suspend fun recordSent(at: Long) {
        store.edit { p -> p[SENT_LOG] = SentLog.encode(SentLog.add(SentLog.decode(p[SENT_LOG]), at)) }
    }

    private fun readSettings(p: Preferences): CellularSettings = CellularSettings(
        enabled = p[ENABLED] ?: false,
        ownNumber = p[OWN_NUMBER] ?: "",
        smsTestMinutes = p[SMS_TEST_MINUTES] ?: CellularSettings.DEFAULT_TEST_MINUTES,
    ).coerced()

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val OWN_NUMBER = stringPreferencesKey("own_number")
        val SMS_TEST_MINUTES = intPreferencesKey("sms_test_minutes")
        val LAST_RUN_AT = longPreferencesKey("last_run_at")
        val NEXT_RUN_AT = longPreferencesKey("next_run_at")
        val RESULTS = stringPreferencesKey("results")
        val STATE = stringPreferencesKey("loopback_state")
        val MANUAL = booleanPreferencesKey("manual_requested")
        val SENT_LOG = stringPreferencesKey("sent_log")
    }
}
