package app.matterplug.alerts

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.matterplug.data.guardDataStore
import app.matterplug.alerts.logic.DeliveryPlan
import app.matterplug.alerts.logic.QueueCodec
import kotlinx.coroutines.flow.first

/**
 * Where the alert requests wait and how the last delivery went: the waiting list, the address and token they go to, the two "send" switches
 * and the delivery line. Lives in the "guard" DataStore file (see [guardDataStore]); the functions ending in `In` work inside an edit
 * that [GuardStore] also makes.
 */
class AlertOutbox(context: Context) {
    private val store = context.applicationContext.guardDataStore

    data class Settings(val address: String, val token: String, val alertsOn: Boolean, val statusOn: Boolean)
    data class DeliveryLine(val okAt: Long?, val failAt: Long?, val error: String?)

    suspend fun readSettings(): Settings = settingsIn(store.data.first())

    suspend fun readQueue(): List<String> = QueueCodec.decode(store.data.first()[QUEUE])

    /**
     * Appends finished bodies to the waiting list in ONE edit, and only while [sender] allows it in that same edit (5.2).
     * Capped at [DeliveryPlan.MAX_QUEUE] (oldest dropped).
     */
    suspend fun enqueue(bodies: List<String>, sender: Sender) {
        if (bodies.isEmpty()) return
        store.edit { p -> if (sender.mayQueueIn(p)) appendIn(p, bodies) }
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

    /** Only the non-null arguments are written (a success does not erase the last failure and the other way round); the error is replaced with [error], or removed when null. */
    suspend fun recordDelivery(okAtMs: Long?, failAtMs: Long?, error: String?) {
        store.edit { p ->
            if (okAtMs != null) p[LAST_DELIVERY_OK_AT] = okAtMs
            if (failAtMs != null) p[LAST_DELIVERY_FAIL_AT] = failAtMs
            if (error == null) p.remove(LAST_DELIVERY_ERROR) else p[LAST_DELIVERY_ERROR] = error
        }
    }

    companion object {
        val ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")
        private val STATUS_ENABLED = booleanPreferencesKey("status_enabled")
        private val ALERT_ADDRESS = stringPreferencesKey("alert_address")
        private val ALERT_TOKEN = stringPreferencesKey("alert_token")
        private val QUEUE = stringPreferencesKey("queue")
        private val LAST_DELIVERY_OK_AT = longPreferencesKey("last_delivery_ok_at")
        private val LAST_DELIVERY_FAIL_AT = longPreferencesKey("last_delivery_fail_at")
        private val LAST_DELIVERY_ERROR = stringPreferencesKey("last_delivery_error")

        fun settingsIn(p: Preferences) = Settings(
            address = p[ALERT_ADDRESS] ?: "",
            token = (p[ALERT_TOKEN] ?: "").trim(),
            alertsOn = p[ALERTS_ENABLED] ?: false,
            statusOn = p[STATUS_ENABLED] ?: false,
        )

        fun writeSettingsIn(p: MutablePreferences, s: Settings) {
            p[ALERTS_ENABLED] = s.alertsOn
            p[STATUS_ENABLED] = s.statusOn
            p[ALERT_ADDRESS] = s.address
            p[ALERT_TOKEN] = s.token.trim()      // never logged
        }

        fun appendIn(p: MutablePreferences, bodies: List<String>) {
            p[QUEUE] = QueueCodec.encode(DeliveryPlan.appendCapped(QueueCodec.decode(p[QUEUE]), bodies))
        }

        fun queueSizeIn(p: Preferences): Int = QueueCodec.decode(p[QUEUE]).size

        fun deliveryIn(p: Preferences) = DeliveryLine(p[LAST_DELIVERY_OK_AT], p[LAST_DELIVERY_FAIL_AT], p[LAST_DELIVERY_ERROR])
    }
}
