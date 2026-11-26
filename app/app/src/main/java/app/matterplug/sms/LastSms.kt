package app.matterplug.sms

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.matterplug.data.StoredValue

/** The "Last SMS" line of a feature, kept in that feature's DataStore: what [AlertSms.sendAndRecord] hands to its `record`. */
class LastSms(store: DataStore<Preferences>) {
    private val value = StoredValue(store, stringPreferencesKey("last_sms"), SmsResult::decode) { SmsResult.encode(it.atMs, it.outcome, it.detail) }

    /** For the screen; null before the first one. */
    val flow get() = value.flow

    suspend fun read(): SmsResult.Last? = value.read()

    suspend fun record(atMs: Long, outcome: SmsOutcome, detail: String?) { value.write(SmsResult.Last(atMs, outcome, detail)) }
}
