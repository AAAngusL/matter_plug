package app.matterplug.alerts

import androidx.datastore.preferences.core.Preferences

/** Whose requests a delivery carries (SIGNAL-STRENGTH.md draft 5): the switches that decide what is sent, and whether a request may join the waiting list. */
interface Sender {
    fun alertsOn(s: AlertOutbox.Settings): Boolean
    fun statusOn(s: AlertOutbox.Settings): Boolean

    /** Asked inside the edit that appends to the waiting list, so a run that is still going cannot refill a list the settings rules just cleared. */
    fun mayQueueIn(p: Preferences): Boolean
}

/** A feature that decided its own switches before it called: Cellular Check, Fixed IP. */
class FeatureSender(private val alertsOn: Boolean, private val statusOn: Boolean) : Sender {
    override fun alertsOn(s: AlertOutbox.Settings) = alertsOn
    override fun statusOn(s: AlertOutbox.Settings) = statusOn
    override fun mayQueueIn(p: Preferences) = true
}
