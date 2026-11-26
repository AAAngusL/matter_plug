package app.matterplug.fixedip

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.longPreferencesKey
import app.matterplug.fixedip.logic.FixedIpSettings
import app.matterplug.fixedip.logic.IpAlertMemo
import app.matterplug.fixedip.logic.IpAlertPolicy
import app.matterplug.fixedip.logic.PinnedNetwork
import app.matterplug.sms.SmsOutcome
import app.matterplug.sms.SmsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.fixedIpDataStore: DataStore<Preferences> by preferencesDataStore(name = "fixedip")

/** Fixed IP's own DataStore file ("fixedip"), the pattern of CellularStore. Nothing of it is kept in "guard", "cellular" or "plugs". */
class IpStore(context: Context) {
    private val store = context.applicationContext.fixedIpDataStore

    val settings: Flow<FixedIpSettings> = store.data.map { read(it) }

    suspend fun readSettings(): FixedIpSettings = read(store.data.first())

    /** The last alert SMS, for the screen (docs/FIXED-IP.md section 10); null before the first one. */
    val lastSms: Flow<SmsResult.Last?> = store.data.map { SmsResult.decode(it[LAST_SMS]) }

    /** [wasOpen]: an alert was open and the switch closed it, so the caller sends cancelled (with ip_off or ip_unpinned). */
    data class Closed(val wasOpen: Boolean)

    /**
     * Applies [transform] and writes the result, coerced, in one edit. Turning the switch on starts clean (no alert, no wait);
     * turning it off ends an open alert and forgets the wait, and the returned [Closed] says whether one was open.
     */
    suspend fun update(transform: (FixedIpSettings) -> FixedIpSettings): Closed {
        var closed = Closed(false)
        store.edit { p ->
            val old = read(p)
            val new = transform(old).coerced()
            if (new.enabled != old.enabled) {
                if (!new.enabled) closed = Closed(IpAlertPolicy.decode(p[ALERT]) != null)
                p.remove(ALERT)
                p.remove(NO_WIFI_SINCE)
            }
            p[ENABLED] = new.enabled
            p[SMS_NUMBER] = new.smsNumber
            p[BACKUP_NUMBER] = new.backupNumber
            val pin = new.pinned
            if (pin == null) {
                listOf(PIN_ADDRESS, PIN_GATEWAY, PIN_SSID, PIN_MAC).forEach { p.remove(it) }
                p.remove(PIN_PREFIX)
            } else {
                p[PIN_ADDRESS] = pin.address; p[PIN_PREFIX] = pin.prefix; p[PIN_GATEWAY] = pin.gateway
                p[PIN_SSID] = pin.ssid; p[PIN_MAC] = pin.mac
            }
        }
        return closed
    }

    suspend fun readAlert(): IpAlertMemo? = IpAlertPolicy.decode(store.data.first()[ALERT])
    suspend fun writeAlert(m: IpAlertMemo?) { store.edit { if (m == null) it.remove(ALERT) else it[ALERT] = IpAlertPolicy.encode(m) } }

    /** When Wi-Fi was first seen missing, kept so the 1-minute wait survives the process being ended; null when it is not missing. */
    suspend fun readNoWifiSince(): Long? = store.data.first()[NO_WIFI_SINCE]
    suspend fun writeNoWifiSince(at: Long?) { store.edit { if (at == null) it.remove(NO_WIFI_SINCE) else it[NO_WIFI_SINCE] = at } }

    suspend fun readLastSms(): SmsResult.Last? = SmsResult.decode(store.data.first()[LAST_SMS])
    suspend fun recordSms(atMs: Long, outcome: SmsOutcome, detail: String?) { store.edit { it[LAST_SMS] = SmsResult.encode(atMs, outcome, detail) } }

    private fun read(p: Preferences): FixedIpSettings {
        val address = p[PIN_ADDRESS]
        val pin = if (address == null) null else PinnedNetwork(
            address, p[PIN_PREFIX] ?: 24, p[PIN_GATEWAY].orEmpty(), p[PIN_SSID].orEmpty(), p[PIN_MAC].orEmpty())
        return FixedIpSettings(p[ENABLED] ?: false, pin, p[SMS_NUMBER].orEmpty(), p[BACKUP_NUMBER].orEmpty())
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val SMS_NUMBER = stringPreferencesKey("sms_number")
        val BACKUP_NUMBER = stringPreferencesKey("backup_number")
        val PIN_ADDRESS = stringPreferencesKey("pin_address")
        val PIN_PREFIX = intPreferencesKey("pin_prefix")
        val PIN_GATEWAY = stringPreferencesKey("pin_gateway")
        val PIN_SSID = stringPreferencesKey("pin_ssid")
        val PIN_MAC = stringPreferencesKey("pin_mac")
        val ALERT = stringPreferencesKey("alert")
        val NO_WIFI_SINCE = longPreferencesKey("no_wifi_since")
        val LAST_SMS = stringPreferencesKey("last_sms")
    }
}
