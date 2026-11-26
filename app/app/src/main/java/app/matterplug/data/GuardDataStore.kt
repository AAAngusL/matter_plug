package app.matterplug.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The DataStore file "guard". Battery Guard's own values and the alert outbox share it (the outbox began there), so one edit can change both
 * (a switch that closes alerts queues their cancelled in the same edit). Moving the outbox to its own file would need a migration.
 */
internal val Context.guardDataStore: DataStore<Preferences> by preferencesDataStore(name = "guard")
