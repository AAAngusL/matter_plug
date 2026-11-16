package app.matterplug.guard

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.matterplug.guard.logic.PlugSighting
import app.matterplug.guard.logic.AlertKind
import app.matterplug.guard.logic.AlertState
import app.matterplug.guard.logic.SwitchOff
import app.matterplug.guard.logic.AlertStateCodec
import app.matterplug.guard.logic.BatteryStatus
import app.matterplug.guard.logic.Power
import app.matterplug.guard.logic.DeliveryPlan
import app.matterplug.guard.logic.GuardSettings
import app.matterplug.guard.logic.QueueCodec
import app.matterplug.guard.logic.coerced
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.guardDataStore: DataStore<Preferences> by preferencesDataStore(name = "guard")

/** Battery Guard's own DataStore file ("guard"). Nothing of it is kept in "plugs". */
class GuardStore(context: Context) {
    private val app: Context = context.applicationContext
    private val store = app.guardDataStore

    val settings: Flow<GuardSettings> = store.data.map { readSettings(it) }

    val status: Flow<GuardStatus> = store.data.map { p ->
        GuardStatus(
            lastCheckAt = p[LAST_CHECK_AT],
            lastLevel = p[LAST_LEVEL],
            lastPlugged = p[LAST_PLUGGED],
            lastBatteryStatus = p[LAST_BATTERY_STATUS]?.let(BatteryStatus::fromWire),
            nextCheckAt = p[NEXT_CHECK_AT],
            lastSwitchAt = p[LAST_SWITCH_AT],
            lastSwitchOn = p[LAST_SWITCH_ON],
            lastSwitchLevel = p[LAST_SWITCH_LEVEL],
            lastDeliveryOkAt = p[LAST_DELIVERY_OK_AT],
            lastDeliveryFailAt = p[LAST_DELIVERY_FAIL_AT],
            lastDeliveryError = p[LAST_DELIVERY_ERROR],
            lastRunError = p[LAST_RUN_ERROR],
            plug = PlugSighting(p[PLUG_REACHED_AT], p[PLUG_ON], p[PLUG_MISSED_SINCE]),
            queueSize = QueueCodec.decode(p[QUEUE]).size,
            alertState = AlertStateCodec.decode(p[ALERT_STATE]),
        )
    }

    suspend fun readSettings(): GuardSettings = readSettings(store.data.first())

    /**
     * Applies [transform] to the stored settings and writes the result (coerced) in one edit, together with the
     * effects of the switches (RECOVERY-SIGNALS.md 2.4, SwitchOff): Battery Guard off or Send alerts off queues one
     * cancelled request per open alert, behind the requests already waiting, which are kept; either switch off, or
     * Send alerts back on, clears the alert state. Battery Guard off also clears next_check_at and the plug sighting.
     * Returns the alerts that were cleared, so the caller can remove their notifications and start the flush job.
     */
    suspend fun update(transform: (GuardSettings) -> GuardSettings): List<AlertKind> {
        var cleared = emptyList<AlertKind>()
        store.edit { p ->
            val old = readSettings(p)
            val new = transform(old).coerced()
            writeSettings(p, new)
            val change = SwitchOff.guard(old, new)
            if (change.reset) {
                val active = AlertStateCodec.decode(p[ALERT_STATE]).active
                cleared = active.map { it.kind }
                val by = change.cancelledBy
                if (by != null && active.isNotEmpty()) {
                    val reading = try { BatteryReader.read(app) } catch (e: Exception) { null }
                    val bodies = SwitchOff.cancelled(active, by, reading?.level ?: 0,
                        reading?.power ?: Power(false, BatteryStatus.Unknown), System.currentTimeMillis()).map(HttpAlerter::body)
                    p[QUEUE] = QueueCodec.encode(DeliveryPlan.appendCapped(QueueCodec.decode(p[QUEUE]), bodies))
                }
                p.remove(ALERT_STATE)       // default state: no active alerts, both counts 0
            }
            if (old.enabled && !new.enabled) {
                p.remove(NEXT_CHECK_AT)
                clearPlug(p)        // a sighting from before is stale once checks stop
            }
        }
        return cleared
    }

    suspend fun readAlertState(): AlertState = AlertStateCodec.decode(store.data.first()[ALERT_STATE])
    suspend fun writeAlertState(s: AlertState) { store.edit { it[ALERT_STATE] = AlertStateCodec.encode(s) } }

    suspend fun readQueue(): List<String> = QueueCodec.decode(store.data.first()[QUEUE])
    suspend fun writeQueue(q: List<String>) { store.edit { it[QUEUE] = QueueCodec.encode(q) } }

    /**
     * Appends finished bodies to the waiting list in ONE edit, and only while Battery Guard and "Send alerts" are both on
     * in that same edit, so a run that is still going cannot refill a list that the settings rules have just cleared (5.2).
     * Capped at [DeliveryPlan.MAX_QUEUE] (oldest dropped). Cellular Check's requests pass [guardItems] false: its caller
     * has checked Cellular Check's own "Send cellular alerts", and Cellular Check runs with Battery Guard off.
     */
    suspend fun enqueue(bodies: List<String>, guardItems: Boolean = true) {
        if (bodies.isEmpty()) return
        store.edit { p ->
            // Battery Guard's need it on with its "Send alerts" on, in this same edit; Cellular Check checked its own switch.
            if (!guardItems || ((p[ENABLED] ?: false) && (p[ALERTS_ENABLED] ?: false))) {
                p[QUEUE] = QueueCodec.encode(DeliveryPlan.appendCapped(QueueCodec.decode(p[QUEUE]), bodies))
            }
        }
    }

    /** Removes the first entry if it is [sent], in one edit (the list may have been cleared or changed meanwhile). */
    suspend fun dequeueIfHead(sent: String) {
        store.edit { p ->
            val q = QueueCodec.decode(p[QUEUE])
            val rest = DeliveryPlan.withoutHead(q, sent)
            if (rest.size != q.size) {
                if (rest.isEmpty()) p.remove(QUEUE) else p[QUEUE] = QueueCodec.encode(rest)
            }
        }
    }

    /** A null level, power or next time removes the stored value. */
    suspend fun recordCheck(atMs: Long, level: Int?, power: Power?, nextAtMs: Long?) {
        store.edit { p ->
            p[LAST_CHECK_AT] = atMs
            putOrRemove(p, LAST_LEVEL, level)
            putOrRemove(p, LAST_PLUGGED, power?.pluggedIn)
            putOrRemove(p, LAST_BATTERY_STATUS, power?.status?.wire)
            putOrRemove(p, NEXT_CHECK_AT, nextAtMs)
        }
    }

    private fun clearPlug(p: androidx.datastore.preferences.core.MutablePreferences) {
        p.remove(PLUG_REACHED_AT); p.remove(PLUG_ON); p.remove(PLUG_MISSED_SINCE)
    }

    /** No charger plug (unpaired or changed): what was learnt about the old one is dropped. */
    suspend fun clearPlugSighting() { store.edit { clearPlug(it) } }

    /** PLUG-WATCH.md section 7: [on] is the plug's state when reached (null if unknown); ignored when not reached. */
    suspend fun recordPlug(reached: Boolean, on: Boolean?, atMs: Long) {
        store.edit { p ->
            val old = PlugSighting(p[PLUG_REACHED_AT], p[PLUG_ON], p[PLUG_MISSED_SINCE])
            val new = if (reached) old.reached(on, atMs) else old.missed(atMs)
            putOrRemove(p, PLUG_REACHED_AT, new.reachedAt)
            putOrRemove(p, PLUG_ON, new.on)
            putOrRemove(p, PLUG_MISSED_SINCE, new.missedSince)
        }
    }

    suspend fun recordSwitch(atMs: Long, on: Boolean, level: Int) {
        store.edit { p ->
            p[LAST_SWITCH_AT] = atMs
            p[LAST_SWITCH_ON] = on
            p[LAST_SWITCH_LEVEL] = level
        }
    }

    /** Only the non-null arguments are written (a success does not erase the last failure and the other way round); the error is replaced with [error], or removed when null. */
    suspend fun recordDelivery(okAtMs: Long?, failAtMs: Long?, error: String?) {
        store.edit { p ->
            if (okAtMs != null) p[LAST_DELIVERY_OK_AT] = okAtMs
            if (failAtMs != null) p[LAST_DELIVERY_FAIL_AT] = failAtMs
            putOrRemove(p, LAST_DELIVERY_ERROR, error)
        }
    }

    suspend fun recordRunError(text: String?) {
        store.edit { putOrRemove(it, LAST_RUN_ERROR, text) }
    }

    /** G7: the two permission prompts are asked once each, whatever the answer was. Not cleared when Battery Guard is switched off. */
    suspend fun readAskedNotifications(): Boolean = store.data.first()[ASKED_NOTIFICATIONS] ?: false
    suspend fun readAskedExemption(): Boolean = store.data.first()[ASKED_EXEMPTION] ?: false
    suspend fun markAskedNotifications() { store.edit { it[ASKED_NOTIFICATIONS] = true } }
    suspend fun markAskedExemption() { store.edit { it[ASKED_EXEMPTION] = true } }

    private fun readSettings(p: Preferences): GuardSettings {
        val d = GuardSettings()
        return GuardSettings(
            enabled = p[ENABLED] ?: d.enabled,
            low = p[LOW] ?: d.low,
            high = p[HIGH] ?: d.high,
            intervalMin = p[CHECK_INTERVAL] ?: d.intervalMin,      // the two old per-state intervals are not carried over
            alertsEnabled = p[ALERTS_ENABLED] ?: d.alertsEnabled,
            statusEnabled = p[STATUS_ENABLED] ?: d.statusEnabled,
            alertAddress = p[ALERT_ADDRESS] ?: d.alertAddress,
            alertToken = (p[ALERT_TOKEN] ?: d.alertToken).trim(),
        ).coerced()
    }

    private fun writeSettings(p: androidx.datastore.preferences.core.MutablePreferences, s: GuardSettings) {
        p[ENABLED] = s.enabled
        p[LOW] = s.low
        p[HIGH] = s.high
        p[CHECK_INTERVAL] = s.intervalMin
        p[ALERTS_ENABLED] = s.alertsEnabled
        p[STATUS_ENABLED] = s.statusEnabled
        p[ALERT_ADDRESS] = s.alertAddress
        p[ALERT_TOKEN] = s.alertToken.trim()      // never logged
    }

    private fun <T> putOrRemove(p: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<T>, value: T?) {
        if (value == null) p.remove(key) else p[key] = value
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val LOW = intPreferencesKey("low")
        val HIGH = intPreferencesKey("high")
        val CHECK_INTERVAL = intPreferencesKey("check_interval")
        val ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")
        val STATUS_ENABLED = booleanPreferencesKey("status_enabled")
        val ALERT_ADDRESS = stringPreferencesKey("alert_address")
        val ALERT_TOKEN = stringPreferencesKey("alert_token")
        val ALERT_STATE = stringPreferencesKey("alert_state")
        val QUEUE = stringPreferencesKey("queue")
        val LAST_CHECK_AT = longPreferencesKey("last_check_at")
        val LAST_LEVEL = intPreferencesKey("last_level")
        val LAST_PLUGGED = booleanPreferencesKey("last_plugged")
        val LAST_BATTERY_STATUS = stringPreferencesKey("last_battery_status")
        val NEXT_CHECK_AT = longPreferencesKey("next_check_at")
        val LAST_SWITCH_AT = longPreferencesKey("last_switch_at")
        val LAST_SWITCH_ON = booleanPreferencesKey("last_switch_on")
        val LAST_SWITCH_LEVEL = intPreferencesKey("last_switch_level")
        val PLUG_REACHED_AT = longPreferencesKey("plug_reached_at")
        val PLUG_ON = booleanPreferencesKey("plug_on")
        val PLUG_MISSED_SINCE = longPreferencesKey("plug_missed_since")
        val LAST_DELIVERY_OK_AT = longPreferencesKey("last_delivery_ok_at")
        val LAST_DELIVERY_FAIL_AT = longPreferencesKey("last_delivery_fail_at")
        val LAST_DELIVERY_ERROR = stringPreferencesKey("last_delivery_error")
        val LAST_RUN_ERROR = stringPreferencesKey("last_run_error")
        val ASKED_NOTIFICATIONS = booleanPreferencesKey("asked_notifications")
        val ASKED_EXEMPTION = booleanPreferencesKey("asked_exemption")
    }
}
