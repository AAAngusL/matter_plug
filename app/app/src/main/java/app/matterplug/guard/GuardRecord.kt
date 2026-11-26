package app.matterplug.guard

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.matterplug.alerts.AlertOutbox
import app.matterplug.battery.BatteryStatus
import app.matterplug.battery.Power
import app.matterplug.data.guardDataStore
import app.matterplug.guard.logic.AlertState
import app.matterplug.guard.logic.AlertStateCodec
import app.matterplug.guard.logic.PlugSighting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What Battery Guard has seen and done: the last check and switch, the plug sighting, the open alerts and the last run error. The settings
 * are [GuardStore]'s; they share one DataStore file ("guard"), so a settings change that must clear some of this (switching off) does it in
 * the same edit through the `...In` functions of the companion.
 */
class GuardRecord(context: Context) {
    private val store = context.applicationContext.guardDataStore

    val status: Flow<GuardStatus> = store.data.map { p ->
        val delivery = AlertOutbox.deliveryIn(p)
        GuardStatus(
            lastCheckAt = p[LAST_CHECK_AT],
            lastLevel = p[LAST_LEVEL],
            lastPlugged = p[LAST_PLUGGED],
            lastBatteryStatus = p[LAST_BATTERY_STATUS]?.let(BatteryStatus::fromWire),
            nextCheckAt = p[NEXT_CHECK_AT],
            lastSwitchAt = p[LAST_SWITCH_AT],
            lastSwitchOn = p[LAST_SWITCH_ON],
            lastSwitchLevel = p[LAST_SWITCH_LEVEL],
            lastDeliveryOkAt = delivery.okAt,
            lastDeliveryFailAt = delivery.failAt,
            lastDeliveryError = delivery.error,
            lastRunError = p[LAST_RUN_ERROR],
            plug = plugIn(p),
            queueSize = AlertOutbox.queueSizeIn(p),
            alertState = AlertStateCodec.decode(p[ALERT_STATE]),
        )
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

    /** No charger plug (unpaired or changed): what was learnt about the old one is dropped. */
    suspend fun clearPlugSighting() { store.edit { clearPlugIn(it) } }

    /** PLUG-WATCH.md section 7: [on] is the plug's state when reached (null if unknown); ignored when not reached. */
    suspend fun recordPlug(reached: Boolean, on: Boolean?, atMs: Long) {
        store.edit { p ->
            val old = plugIn(p)
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

    companion object {
        /** The kinds of the alerts that are open, inside an edit. */
        fun openAlertsIn(p: Preferences) = AlertStateCodec.decode(p[ALERT_STATE]).active

        /** Default state: no active alerts, both counts 0. */
        fun clearAlertsIn(p: MutablePreferences) { p.remove(ALERT_STATE) }

        /** Battery Guard has been switched off: the next check time and the plug sighting are stale once checks stop. */
        fun clearAfterSwitchOffIn(p: MutablePreferences) {
            p.remove(NEXT_CHECK_AT)
            clearPlugIn(p)
        }

        private fun plugIn(p: Preferences) = PlugSighting(p[PLUG_REACHED_AT], p[PLUG_ON], p[PLUG_MISSED_SINCE])

        private fun clearPlugIn(p: MutablePreferences) {
            p.remove(PLUG_REACHED_AT); p.remove(PLUG_ON); p.remove(PLUG_MISSED_SINCE)
        }

        private fun <T> putOrRemove(p: MutablePreferences, key: Preferences.Key<T>, value: T?) {
            if (value == null) p.remove(key) else p[key] = value
        }

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
    }
}
