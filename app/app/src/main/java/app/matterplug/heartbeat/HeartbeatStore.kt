package app.matterplug.heartbeat

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.matterplug.heartbeat.logic.Heartbeat
import app.matterplug.heartbeat.logic.HeartbeatSettings
import app.matterplug.heartbeat.logic.HeartbeatDue
import app.matterplug.heartbeat.logic.HeartbeatState
import app.matterplug.sms.SmsOutcome
import app.matterplug.sms.SmsResult
import app.matterplug.heartbeat.logic.Observation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.heartbeatDataStore: DataStore<Preferences> by preferencesDataStore(name = "heartbeat")

/** The heartbeat's own DataStore file ("heartbeat"), the pattern of IpStore (docs/CONSOLE-HEARTBEAT.md section 5, decision 8). */
class HeartbeatStore(context: Context) {
    private val store = context.applicationContext.heartbeatDataStore

    val settings: Flow<HeartbeatSettings> = store.data.map { read(it) }

    suspend fun readSettings(): HeartbeatSettings = read(store.data.first())

    /** The last heartbeat SMS, for the screen; null before the first one. */
    val lastSms: Flow<SmsResult.Last?> = store.data.map { SmsResult.decode(it[LAST_SMS]) }

    suspend fun recordSms(atMs: Long, outcome: SmsOutcome, detail: String?) { store.edit { it[LAST_SMS] = SmsResult.encode(atMs, outcome, detail) } }

    /** The start SMS reached no number: the alert is closed again so the next missed check retries it (Heartbeat.startNotSent). */
    suspend fun startNotSent() {
        store.edit { p -> if (read(p).enabled) p[STATE] = Heartbeat.encode(Heartbeat.startNotSent(Heartbeat.decode(p[STATE]))) }
    }

    suspend fun readState(): HeartbeatState = Heartbeat.decode(store.data.first()[STATE])

    /**
     * Applies [transform] and writes the result. Turning the switch on starts clean; turning it off clears an open alert and the
     * count with no SMS and no request (decision "off during an alert"). Anything else leaves the count alone (decision 8).
     */
    suspend fun update(transform: (HeartbeatSettings) -> HeartbeatSettings) {
        store.edit { p ->
            val old = read(p)
            val new = transform(old)
            if (new.enabled != old.enabled) p.remove(STATE)
            p[ENABLED] = new.enabled
            p[LIMIT] = HeartbeatSettings.validLimit(new.limit)
        }
    }

    /**
     * One check, in one edit so two checks cannot lose each other's count: moves the state on and returns what to send, with the
     * limit it was judged by. A switch that is off changes nothing.
     */
    suspend fun apply(obs: Observation): HeartbeatDue {
        var due: HeartbeatDue = HeartbeatDue.Nothing
        store.edit { p ->
            val s = read(p)
            if (!s.enabled) return@edit
            val (state, sms) = Heartbeat.step(Heartbeat.decode(p[STATE]), obs, s.limit)
            p[STATE] = Heartbeat.encode(state)
            if (sms != null) due = HeartbeatDue.Send(sms, s.limit)
        }
        return due
    }

    private fun read(p: Preferences) = HeartbeatSettings(p[ENABLED] ?: false, HeartbeatSettings.validLimit(p[LIMIT]))

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val LIMIT = intPreferencesKey("limit")
        val STATE = stringPreferencesKey("state")
        val LAST_SMS = stringPreferencesKey("last_sms")
    }
}
