package app.matterplug.alerts

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.matterplug.data.guardDataStore
import app.matterplug.alerts.logic.AlertBody
import app.matterplug.alerts.logic.AlertEvent
import app.matterplug.alerts.logic.DeliveryPlan
import app.matterplug.alerts.logic.QueueCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Where the alert requests wait and how the last delivery went: the waiting list, the endpoint they go to and the delivery line. Lives in the "guard" DataStore file (see [guardDataStore]); the functions ending in `In` work inside an edit
 * that [GuardStore] also makes.
 */
class AlertOutbox(context: Context) {
    private val store = context.applicationContext.guardDataStore

    /** Where requests go: the server address and the optional bearer token (never logged). */
    data class Endpoint(val address: String, val token: String)
    data class DeliveryLine(val okAt: Long?, val failAt: Long?, val error: String?)

    val endpoint: Flow<Endpoint> = store.data.map { endpointIn(it) }

    suspend fun readEndpoint(): Endpoint = endpointIn(store.data.first())

    /** Applies [transform] to the stored endpoint and writes the result in one edit. */
    suspend fun updateEndpoint(transform: (Endpoint) -> Endpoint) {
        store.edit { p -> writeEndpointIn(p, transform(endpointIn(p))) }
    }

    suspend fun readQueue(): List<String> = QueueCodec.decode(store.data.first()[QUEUE])

    /**
     * Appends finished bodies to the waiting list in ONE edit, and only while [sender] allows it in that same edit (5.2).
     * Capped at [DeliveryPlan.MAX_QUEUE] (oldest dropped).
     */
    suspend fun enqueue(bodies: List<String>, sender: Sender) {
        if (bodies.isEmpty()) return
        store.edit { p -> if (sender.mayQueueIn(p)) appendBodiesIn(p, bodies) }
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
        private val ALERT_ADDRESS = stringPreferencesKey("alert_address")
        private val ALERT_TOKEN = stringPreferencesKey("alert_token")
        private val QUEUE = stringPreferencesKey("queue")
        private val LAST_DELIVERY_OK_AT = longPreferencesKey("last_delivery_ok_at")
        private val LAST_DELIVERY_FAIL_AT = longPreferencesKey("last_delivery_fail_at")
        private val LAST_DELIVERY_ERROR = stringPreferencesKey("last_delivery_error")

        private fun endpointIn(p: Preferences) = Endpoint(address = p[ALERT_ADDRESS] ?: "", token = (p[ALERT_TOKEN] ?: "").trim())

        private fun writeEndpointIn(p: MutablePreferences, e: Endpoint) {
            p[ALERT_ADDRESS] = e.address
            p[ALERT_TOKEN] = e.token.trim()
        }

        /** Adds [events] behind the requests already waiting, inside an edit. */
        fun appendEventsIn(p: MutablePreferences, events: List<AlertEvent>) = appendBodiesIn(p, events.map(AlertBody::of))

        internal fun appendBodiesIn(p: MutablePreferences, bodies: List<String>) {
            p[QUEUE] = QueueCodec.encode(DeliveryPlan.appendCapped(QueueCodec.decode(p[QUEUE]), bodies))
        }

        fun queueSizeIn(p: Preferences): Int = QueueCodec.decode(p[QUEUE]).size

        fun deliveryIn(p: Preferences) = DeliveryLine(p[LAST_DELIVERY_OK_AT], p[LAST_DELIVERY_FAIL_AT], p[LAST_DELIVERY_ERROR])
    }
}
