package app.matterplug.guard

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.matterplug.data.guardDataStore
import app.matterplug.guard.logic.PlugSighting
import app.matterplug.alerts.logic.AlertKind
import app.matterplug.guard.logic.AlertState
import app.matterplug.guard.logic.GuardSwitchOff
import app.matterplug.guard.logic.AlertStateCodec
import app.matterplug.battery.BatteryStatus
import app.matterplug.battery.Power
import app.matterplug.alerts.logic.DeliveryPlan
import app.matterplug.guard.logic.GuardSettings
import app.matterplug.alerts.logic.QueueCodec
import app.matterplug.guard.logic.coerced
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.matterplug.alerts.AlertOutbox
import app.matterplug.alerts.HttpAlerter
import app.matterplug.alerts.Sender
import app.matterplug.battery.BatteryReader

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
            lastDeliveryOkAt = AlertOutbox.deliveryIn(p).okAt,
            lastDeliveryFailAt = AlertOutbox.deliveryIn(p).failAt,
            lastDeliveryError = AlertOutbox.deliveryIn(p).error,
            lastRunError = p[LAST_RUN_ERROR],
            plug = PlugSighting(p[PLUG_REACHED_AT], p[PLUG_ON], p[PLUG_MISSED_SINCE]),
            queueSize = AlertOutbox.queueSizeIn(p),
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
            val change = GuardSwitchOff.change(old, new)
            if (change.reset) {
                val active = AlertStateCodec.decode(p[ALERT_STATE]).active
                cleared = active.map { it.kind }
                val by = change.cancelledBy
                if (by != null && active.isNotEmpty()) {
                    val reading = try { BatteryReader.read(app) } catch (e: Exception) { null }
                    val bodies = GuardSwitchOff.cancelled(active, by, reading?.level ?: 0,
                        reading?.power ?: Power(false, BatteryStatus.Unknown), System.currentTimeMillis()).map(HttpAlerter::body)
                    AlertOutbox.appendIn(p, bodies)
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

    suspend fun recordRunError(text: String?) {
        store.edit { putOrRemove(it, LAST_RUN_ERROR, text) }
    }

    /** The two permission prompts are asked once each, whatever the answer was. Not cleared when Battery Guard is switched off. */
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
        ).coerced()
    }

    private fun writeSettings(p: androidx.datastore.preferences.core.MutablePreferences, s: GuardSettings) {
        p[ENABLED] = s.enabled
        p[LOW] = s.low
        p[HIGH] = s.high
        p[CHECK_INTERVAL] = s.intervalMin
        p[ALERTS_ENABLED] = s.alertsEnabled
        p[STATUS_ENABLED] = s.statusEnabled
    }

    private fun <T> putOrRemove(p: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<T>, value: T?) {
        if (value == null) p.remove(key) else p[key] = value
    }

    companion object {
        /** Battery Guard's own rule for what may join the waiting list: it is on, with "Send alerts" on. */
        fun onWithAlertsIn(p: Preferences): Boolean = (p[ENABLED] ?: false) && (p[ALERTS_ENABLED] ?: false)

        private val ENABLED = booleanPreferencesKey("enabled")
        private val LOW = intPreferencesKey("low")
        private val HIGH = intPreferencesKey("high")
        private val CHECK_INTERVAL = intPreferencesKey("check_interval")
        private val ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")
        private val STATUS_ENABLED = booleanPreferencesKey("status_enabled")
        private val ALERT_STATE = stringPreferencesKey("alert_state")
        private val LAST_CHECK_AT = longPreferencesKey("last_check_at")
        private val LAST_LEVEL = intPreferencesKey("last_level")
        private val LAST_PLUGGED = booleanPreferencesKey("last_plugged")
        private val LAST_BATTERY_STATUS = stringPreferencesKey("last_battery_status")
        private val NEXT_CHECK_AT = longPreferencesKey("next_check_at")
        private val LAST_SWITCH_AT = longPreferencesKey("last_switch_at")
        private val LAST_SWITCH_ON = booleanPreferencesKey("last_switch_on")
        private val LAST_SWITCH_LEVEL = intPreferencesKey("last_switch_level")
        private val PLUG_REACHED_AT = longPreferencesKey("plug_reached_at")
        private val PLUG_ON = booleanPreferencesKey("plug_on")
        private val PLUG_MISSED_SINCE = longPreferencesKey("plug_missed_since")
        private val LAST_RUN_ERROR = stringPreferencesKey("last_run_error")
        private val ASKED_NOTIFICATIONS = booleanPreferencesKey("asked_notifications")
        private val ASKED_EXEMPTION = booleanPreferencesKey("asked_exemption")
    }
}

/** Battery Guard's requests: its switches decide what is sent, and they join the waiting list only while it is on with "Send alerts" on. */
class BatteryGuardSender(private val store: GuardStore) : Sender {
    override suspend fun switches() = store.readSettings().let { Sender.Switches(it.alertsEnabled, it.statusEnabled) }
    override fun mayQueueIn(p: Preferences) = GuardStore.onWithAlertsIn(p)
}
