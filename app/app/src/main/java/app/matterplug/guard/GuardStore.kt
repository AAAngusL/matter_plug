package app.matterplug.guard

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.matterplug.alerts.AlertOutbox
import app.matterplug.alerts.Sender
import app.matterplug.alerts.logic.AlertKind
import app.matterplug.battery.BatteryReader
import app.matterplug.battery.BatteryStatus
import app.matterplug.battery.Power
import app.matterplug.data.guardDataStore
import app.matterplug.guard.logic.GuardSettings
import app.matterplug.guard.logic.GuardSwitchOff
import app.matterplug.guard.logic.coerced
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Battery Guard's settings in its DataStore file ("guard"), and what switching them does to the record ([GuardRecord]) and the alerts. */
class GuardStore(context: Context) {
    private val app: Context = context.applicationContext
    private val store = app.guardDataStore

    val settings: Flow<GuardSettings> = store.data.map { readSettings(it) }

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
                val active = GuardRecord.openAlertsIn(p)
                cleared = active.map { it.kind }
                val by = change.cancelledBy
                if (by != null && active.isNotEmpty()) {
                    val reading = try { BatteryReader.read(app) } catch (e: Exception) { null }
                    val events = GuardSwitchOff.cancelled(active, by, reading?.level ?: 0,
                        reading?.power ?: Power(false, BatteryStatus.Unknown), System.currentTimeMillis())
                    AlertOutbox.appendEventsIn(p, events)
                }
                GuardRecord.clearAlertsIn(p)
            }
            if (old.enabled && !new.enabled) GuardRecord.clearAfterSwitchOffIn(p)
        }
        return cleared
    }

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

    private fun writeSettings(p: MutablePreferences, s: GuardSettings) {
        p[ENABLED] = s.enabled
        p[LOW] = s.low
        p[HIGH] = s.high
        p[CHECK_INTERVAL] = s.intervalMin
        p[ALERTS_ENABLED] = s.alertsEnabled
        p[STATUS_ENABLED] = s.statusEnabled
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
    }
}

/** Battery Guard's requests: its switches decide what is sent, and they join the waiting list only while it is on with "Send alerts" on. */
class BatteryGuardSender(private val store: GuardStore) : Sender {
    override suspend fun switches() = store.readSettings().let { Sender.Switches(it.alertsEnabled, it.statusEnabled) }
    override fun mayQueueIn(p: Preferences) = GuardStore.onWithAlertsIn(p)
}
