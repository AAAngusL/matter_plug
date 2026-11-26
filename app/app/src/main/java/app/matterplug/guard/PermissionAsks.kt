package app.matterplug.guard

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.matterplug.data.guardDataStore
import kotlinx.coroutines.flow.first

/** The two permission prompts are asked once each, whatever the answer was. Not cleared when Battery Guard is switched off. */
class PermissionAsks(context: Context) {
    private val store = context.applicationContext.guardDataStore

    suspend fun askedNotifications(): Boolean = store.data.first()[ASKED_NOTIFICATIONS] ?: false
    suspend fun askedExemption(): Boolean = store.data.first()[ASKED_EXEMPTION] ?: false
    suspend fun markNotifications() { store.edit { it[ASKED_NOTIFICATIONS] = true } }
    suspend fun markExemption() { store.edit { it[ASKED_EXEMPTION] = true } }

    private companion object {
        val ASKED_NOTIFICATIONS = booleanPreferencesKey("asked_notifications")
        val ASKED_EXEMPTION = booleanPreferencesKey("asked_exemption")
    }
}
